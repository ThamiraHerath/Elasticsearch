/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ilm.history;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkAction;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.TriFunction;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.client.NoOpClient;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.action.CreateDataStreamAction;
import org.elasticsearch.xpack.core.ilm.LifecycleExecutionState;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.elasticsearch.xpack.core.ilm.LifecycleSettings.LIFECYCLE_HISTORY_INDEX_ENABLED_SETTING;
import static org.elasticsearch.xpack.ilm.history.ILMHistoryStore.ILM_HISTORY_DATA_STREAM;
import static org.elasticsearch.xpack.ilm.history.ILMHistoryTemplateRegistry.ILM_TEMPLATE_NAME;
import static org.elasticsearch.xpack.ilm.history.ILMHistoryTemplateRegistry.TEMPLATE_ILM_HISTORY;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class ILMHistoryStoreTests extends ESTestCase {

    private ThreadPool threadPool;
    private VerifyingClient client;
    private ClusterService clusterService;
    private ILMHistoryStore historyStore;

    @Before
    public void setup() throws Exception {
        threadPool = new TestThreadPool(this.getClass().getName());
        client = new VerifyingClient(threadPool);
        clusterService = ClusterServiceUtils.createClusterService(threadPool);
        byte[] templateBytes = TEMPLATE_ILM_HISTORY.loadBytes();
        BytesArray templateBytesArray = new BytesArray(templateBytes, 0, templateBytes.length);
        try (XContentParser parser = XContentHelper.createParser(NamedXContentRegistry.EMPTY,
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION, templateBytesArray, XContentType.JSON)) {
            ComposableIndexTemplate template = ComposableIndexTemplate.parse(parser);
            Metadata.Builder metadataBuilder = Metadata.builder().indexTemplates(Map.of(ILM_TEMPLATE_NAME, template));
            ClusterState state = ClusterState.builder(clusterService.state()).metadata(metadataBuilder).build();
            ClusterServiceUtils.setState(clusterService, state);
        }
        historyStore = new ILMHistoryStore(client, clusterService, threadPool);
    }

    @After
    public void setdown() {
        historyStore.close();
        clusterService.close();
        client.close();
        threadPool.shutdownNow();
    }

    public void testNoActionIfDisabled() throws Exception {
        ClusterState state = clusterService.state();
        Metadata.Builder builder = Metadata.builder(state.metadata());
        builder.transientSettings(Settings.builder().put(LIFECYCLE_HISTORY_INDEX_ENABLED_SETTING.getKey(), "false").build());
        state = ClusterState.builder(state).metadata(builder).build();
        ClusterServiceUtils.setState(clusterService, state);
        try (ILMHistoryStore disabledHistoryStore = new ILMHistoryStore(client, clusterService, threadPool)) {
            String policyId = randomAlphaOfLength(5);
            final long timestamp = randomNonNegativeLong();
            ILMHistoryItem record = ILMHistoryItem.success("index", policyId, timestamp, null, null);

            CountDownLatch latch = new CountDownLatch(1);
            client.setVerifier((a, r, l) -> {
                fail("the history store is disabled, no action should have been taken");
                latch.countDown();
                return null;
            });
            disabledHistoryStore.putAsync(record);
            latch.await(10, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    public void testPut() throws Exception {
        String policyId = randomAlphaOfLength(5);
        final long timestamp = randomNonNegativeLong();
        {
            ILMHistoryItem record = ILMHistoryItem.success("index", policyId, timestamp, 10L,
                LifecycleExecutionState.builder()
                    .setPhase("phase")
                    .build());

            AtomicInteger calledTimes = new AtomicInteger(0);
            client.setVerifier((action, request, listener) -> {
                if (action instanceof CreateDataStreamAction && request instanceof CreateDataStreamAction.Request) {
                    return new AcknowledgedResponse(true);
                }
                calledTimes.incrementAndGet();
                assertThat(action, instanceOf(BulkAction.class));
                assertThat(request, instanceOf(BulkRequest.class));
                BulkRequest bulkRequest = (BulkRequest) request;
                bulkRequest.requests().forEach(dwr -> assertEquals(ILM_HISTORY_DATA_STREAM, dwr.index()));
                assertNotNull(listener);

                // The content of this BulkResponse doesn't matter, so just make it have the same number of responses
                int responses = bulkRequest.numberOfActions();
                return new BulkResponse(IntStream.range(0, responses)
                    .mapToObj(i -> new BulkItemResponse(i, DocWriteRequest.OpType.INDEX,
                        new IndexResponse(new ShardId("index", "uuid", 0), randomAlphaOfLength(10), 1, 1, 1, true)))
                    .toArray(BulkItemResponse[]::new),
                    1000L);
            });

            historyStore.putAsync(record);
            assertBusy(() -> assertThat(calledTimes.get(), equalTo(1)));
        }

        {
            final String cause = randomAlphaOfLength(9);
            Exception failureException = new RuntimeException(cause);
            ILMHistoryItem record = ILMHistoryItem.failure("index", policyId, timestamp, 10L,
                LifecycleExecutionState.builder()
                    .setPhase("phase")
                    .build(), failureException);

            AtomicInteger calledTimes = new AtomicInteger(0);
            client.setVerifier((action, request, listener) -> {
                if (action instanceof CreateDataStreamAction && request instanceof CreateDataStreamAction.Request) {
                    return new AcknowledgedResponse(true);
                }
                calledTimes.incrementAndGet();
                assertThat(action, instanceOf(BulkAction.class));
                assertThat(request, instanceOf(BulkRequest.class));
                BulkRequest bulkRequest = (BulkRequest) request;
                bulkRequest.requests().forEach(dwr -> {
                    assertEquals(ILM_HISTORY_DATA_STREAM, dwr.index());
                    assertThat(dwr, instanceOf(IndexRequest.class));
                    IndexRequest ir = (IndexRequest) dwr;
                    String indexedDocument = ir.source().utf8ToString();
                    assertThat(indexedDocument, Matchers.containsString("runtime_exception"));
                    assertThat(indexedDocument, Matchers.containsString(cause));
                });
                assertNotNull(listener);

                // The content of this BulkResponse doesn't matter, so just make it have the same number of responses with failures
                int responses = bulkRequest.numberOfActions();
                return new BulkResponse(IntStream.range(0, responses)
                    .mapToObj(i -> new BulkItemResponse(i, DocWriteRequest.OpType.INDEX,
                        new BulkItemResponse.Failure("index", i + "", failureException)))
                    .toArray(BulkItemResponse[]::new),
                    1000L);
            });

            historyStore.putAsync(record);
            assertBusy(() -> assertThat(calledTimes.get(), equalTo(1)));
        }
    }

    /**
     * A client that delegates to a verifying function for action/request/listener
     */
    public static class VerifyingClient extends NoOpClient {

        private TriFunction<ActionType<?>, ActionRequest, ActionListener<?>, ActionResponse> verifier = (a, r, l) -> {
            fail("verifier not set");
            return null;
        };

        VerifyingClient(ThreadPool threadPool) {
            super(threadPool);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <Request extends ActionRequest, Response extends ActionResponse> void doExecute(ActionType<Response> action,
                                                                                                  Request request,
                                                                                                  ActionListener<Response> listener) {
            try {
                listener.onResponse((Response) verifier.apply(action, request, listener));
            } catch (Exception e) {
                listener.onFailure(e);
            }
        }

        VerifyingClient setVerifier(TriFunction<ActionType<?>, ActionRequest, ActionListener<?>, ActionResponse> verifier) {
            this.verifier = verifier;
            return this;
        }
    }
}
