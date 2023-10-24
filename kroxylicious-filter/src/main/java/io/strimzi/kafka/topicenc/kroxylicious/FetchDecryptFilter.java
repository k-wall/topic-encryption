package io.strimzi.kafka.topicenc.kroxylicious;

import io.kroxylicious.proxy.filter.FetchRequestFilter;
import io.kroxylicious.proxy.filter.FetchResponseFilter;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.MetadataResponseFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilterResult;
import io.strimzi.kafka.topicenc.EncryptionModule;
import io.strimzi.kafka.topicenc.kms.KmsDefinition;
import io.strimzi.kafka.topicenc.kms.test.TestKms;
import io.strimzi.kafka.topicenc.policy.InMemoryPolicyRepository;
import io.strimzi.kafka.topicenc.policy.TopicPolicy;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.message.FetchRequestData;
import org.apache.kafka.common.message.FetchResponseData;
import org.apache.kafka.common.message.MetadataRequestData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.MetadataResponseDataJsonConverter;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

import static java.util.stream.Collectors.toSet;

public class FetchDecryptFilter implements FetchRequestFilter, FetchResponseFilter, MetadataResponseFilter {

    private static final Logger log = LoggerFactory.getLogger(FetchDecryptFilter.class);
    public static final short METADATA_VERSION_SUPPORTING_TOPIC_IDS = (short) 10;
    private final Map<Uuid, String> topicUuidToName = new HashMap<>();

    private final EncryptionModule module = new EncryptionModule(new InMemoryPolicyRepository(List.of(new TopicPolicy().setTopic(TopicPolicy.ALL_TOPICS).setKms(new TestKms(new KmsDefinition())))));

    @Override
    public CompletionStage<RequestFilterResult> onFetchRequest(short apiVersion, RequestHeaderData header, FetchRequestData request, FilterContext context) {
        boolean allTopicsResolvableToName = request.topics().stream().allMatch(this::isResolvable);
        if (!allTopicsResolvableToName) {
            Set<Uuid> topicIdsToResolve = request.topics().stream().filter(Predicate.not(this::isResolvable)).map(FetchRequestData.FetchTopic::topicId).collect(toSet());
            // send a background metadata request to resolve topic ids, preparing for fetch response
            resolveAndCache(context, topicIdsToResolve);
        }
        return context.forwardRequest(header, request);
    }

    @Override
    public CompletionStage<ResponseFilterResult> onFetchResponse(short apiVersion, ResponseHeaderData header, FetchResponseData response, FilterContext context) {
        var unresolvedTopicIds = getUnresolvedTopicIds(response);
        if (unresolvedTopicIds.isEmpty()) {
            return decryptFetchResponse(header, response, context);
        } else {
            log.warn("We did not know all topic names for {} topic ids within a fetch response, requesting metadata and returning error response", unresolvedTopicIds.size());
            log.debug("We did not know all topic names for topic ids {} within a fetch response, requesting metadata and returning error response", unresolvedTopicIds);
            // we return an error rather than delaying the response to prevent out-of-order responses to the Consumer client.
            // The Filter API only supports synchronous work currently.
            return resolveTopicsAndReturnError(header, context, unresolvedTopicIds);
        }
    }

    private Set<Uuid> getUnresolvedTopicIds(FetchResponseData response) {
        return response.responses().stream()
                .filter(Predicate.not(this::isResolvable))
                .map(FetchResponseData.FetchableTopicResponse::topicId)
                .collect(toSet());
    }

    /**
     * We should know the topic names by the time we get the response, because the fetch request sends a metadata request
     * for unknown topic ids before sending the fetch request. This is a safeguard in case that request fails somehow.
     *
     * @return
     */
    private CompletionStage<ResponseFilterResult> resolveTopicsAndReturnError(ResponseHeaderData header, FilterContext context, Set<Uuid> topicIdsToResolve) {
        // send a background metadata request to resolve topic ids, preparing for future fetches
        resolveAndCache(context, topicIdsToResolve);
        FetchResponseData data = new FetchResponseData();
        data.setErrorCode(Errors.UNKNOWN_SERVER_ERROR.code());
        return context.forwardResponse(header, data);
    }

    private void resolveAndCache(FilterContext context, Set<Uuid> topicIdsToResolve) {
        MetadataRequestData request = new MetadataRequestData();
        topicIdsToResolve.forEach(uuid -> {
            MetadataRequestData.MetadataRequestTopic e = new MetadataRequestData.MetadataRequestTopic();
            e.setTopicId(uuid);
            request.topics().add(e);
        });
        // if the client is sending topic ids we will assume the broker can support at least the lowest metadata apiVersion
        // supporting topicIds
        CompletionStage<MetadataResponseData> stage = context.sendRequest(new RequestHeaderData().setRequestApiVersion(METADATA_VERSION_SUPPORTING_TOPIC_IDS), request);
        stage.thenAccept(response -> cacheTopicIdToName(response, METADATA_VERSION_SUPPORTING_TOPIC_IDS));
    }

    private CompletionStage<ResponseFilterResult> decryptFetchResponse(ResponseHeaderData header, FetchResponseData response, FilterContext context) {
        for (FetchResponseData.FetchableTopicResponse fetchResponse : response.responses()) {
            Uuid originalUuid = fetchResponse.topicId();
            String originalName = fetchResponse.topic();
            if (Strings.isNullOrBlank(originalName)) {
                fetchResponse.setTopic(topicUuidToName.get(originalUuid));
                fetchResponse.setTopicId(null);
            }
            try {
                module.decrypt(fetchResponse);
            } catch (Exception e) {
                log.error("Failed to decrypt a fetchResponse for topic: " + fetchResponse.topic(), e);
                throw new RuntimeException(e);
            }
            fetchResponse.setTopic(originalName);
            fetchResponse.setTopicId(originalUuid);
        }
        return context.forwardResponse(header, response);
    }


    private boolean isResolvable(FetchResponseData.FetchableTopicResponse fetchableTopicResponse) {
        return !Strings.isNullOrBlank(fetchableTopicResponse.topic()) || topicUuidToName.containsKey(fetchableTopicResponse.topicId());
    }

    private boolean isResolvable(FetchRequestData.FetchTopic fetchTopic) {
        return !Strings.isNullOrBlank(fetchTopic.topic()) || topicUuidToName.containsKey(fetchTopic.topicId());
    }

    @Override
    public CompletionStage<ResponseFilterResult> onMetadataResponse(short apiVersion, ResponseHeaderData header, MetadataResponseData response, FilterContext context) {
        cacheTopicIdToName(response, apiVersion);
        return context.forwardResponse(header, response);
    }

    private void cacheTopicIdToName(MetadataResponseData response, short apiVersion) {
        if (log.isTraceEnabled()) {
            log.trace("received metadata response: {}", MetadataResponseDataJsonConverter.write(response, apiVersion));
        }
        response.topics().forEach(topic -> topicUuidToName.put(topic.topicId(), topic.name()));
    }
}
