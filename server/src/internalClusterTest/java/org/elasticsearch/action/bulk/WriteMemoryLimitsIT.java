/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.action.bulk;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalSettingsPlugin;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.transport.TransportService;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.SUITE, numDataNodes = 2)
public class WriteMemoryLimitsIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            // Need at least two threads because we are going to block one
            .put("thread_pool.write.size", 2)
            .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(MockTransportService.TestPlugin.class, InternalSettingsPlugin.class);
    }

    @Override
    protected int numberOfReplicas() {
        return 1;
    }

    @Override
    protected int numberOfShards() {
        return 1;
    }

    public void testWriteBytesAreIncremented() throws Exception {
        final String index = "test";
        assertAcked(prepareCreate(index, Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)));
        ensureGreen(index);

        IndicesStatsResponse response = client().admin().indices().prepareStats(index).get();
        String primaryId = Stream.of(response.getShards())
            .map(ShardStats::getShardRouting)
            .filter(ShardRouting::primary)
            .findAny()
            .get()
            .currentNodeId();
        String replicaId = Stream.of(response.getShards())
            .map(ShardStats::getShardRouting)
            .filter(sr -> sr.primary() == false)
            .findAny()
            .get()
            .currentNodeId();
        String primaryName = client().admin().cluster().prepareState().get().getState().nodes().get(primaryId).getName();
        String replicaName = client().admin().cluster().prepareState().get().getState().nodes().get(replicaId).getName();

        final CountDownLatch replicationSendPointReached = new CountDownLatch(1);
        final CountDownLatch latchBlockingReplicationSend = new CountDownLatch(1);
        final CountDownLatch newActionsSendPointReached = new CountDownLatch(2);
        final CountDownLatch latchBlockingReplication = new CountDownLatch(1);

        TransportService primaryService = internalCluster().getInstance(TransportService.class, primaryName);
        final MockTransportService primaryTransportService = (MockTransportService) primaryService;
        TransportService replicaService = internalCluster().getInstance(TransportService.class, replicaName);
        final MockTransportService replicaTransportService = (MockTransportService) replicaService;

        primaryTransportService.addSendBehavior((connection, requestId, action, request, options) -> {
            if (action.equals(TransportShardBulkAction.ACTION_NAME + "[r]")) {
                try {
                    replicationSendPointReached.countDown();
                    latchBlockingReplicationSend.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            connection.sendRequest(requestId, action, request, options);
        });

        final BulkRequest lessThan1KB = new BulkRequest();
        int totalSourceLength = 0;
        for (int i = 0; i < 80; ++i) {
            IndexRequest request = new IndexRequest(index).source(Collections.singletonMap("key", randomAlphaOfLength(50)));
            totalSourceLength += request.source().length();
            lessThan1KB.add(request);
        }



        final int operationSize = totalSourceLength + WriteMemoryLimits.WRITE_REQUEST_BYTES_OVERHEAD;

        try {
            final ActionFuture<BulkResponse> successFuture = client(replicaName).bulk(lessThan1KB);
            replicationSendPointReached.await();

            WriteMemoryLimits primaryWriteLimits = internalCluster().getInstance(WriteMemoryLimits.class, primaryName);
            WriteMemoryLimits replicaWriteLimits = internalCluster().getInstance(WriteMemoryLimits.class, replicaName);

            assertEquals(operationSize, primaryWriteLimits.getCoordinatingBytes());
            assertEquals(operationSize, primaryWriteLimits.getPrimaryBytes());
            assertEquals(0, primaryWriteLimits.getReplicaBytes());
            assertEquals(operationSize, replicaWriteLimits.getCoordinatingBytes());
            assertEquals(0, replicaWriteLimits.getPrimaryBytes());
            assertEquals(0, replicaWriteLimits.getReplicaBytes());

            replicaTransportService.addSendBehavior((connection, requestId, action, request, options) -> {
                if (action.equals(TransportShardBulkAction.ACTION_NAME)) {
                    try {
                        newActionsSendPointReached.countDown();
                        latchBlockingReplication.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                connection.sendRequest(requestId, action, request, options);
            });

            IndexRequest request1 = new IndexRequest(index).source(Collections.singletonMap("key", randomAlphaOfLength(50)));
            IndexRequest request2 = new IndexRequest(index).source(Collections.singletonMap("key", randomAlphaOfLength(50)));
            ActionFuture<IndexResponse> future1 = client(replicaName).index(request1);
            ActionFuture<IndexResponse> future2 = client(replicaName).index(request2);

            newActionsSendPointReached.await();
            latchBlockingReplicationSend.countDown();

            int newOperationSizes = request1.source().length() + request2.source().length() +
                (WriteMemoryLimits.WRITE_REQUEST_BYTES_OVERHEAD * 2);

            assertEquals(operationSize + newOperationSizes, replicaWriteLimits.getCoordinatingBytes());
            assertBusy(() -> assertEquals(operationSize, replicaWriteLimits.getReplicaBytes()));

            latchBlockingReplication.countDown();

            successFuture.actionGet();
            future1.actionGet();
            future2.actionGet();

            assertEquals(0, primaryWriteLimits.getCoordinatingBytes());
            assertEquals(0, primaryWriteLimits.getPrimaryBytes());
            assertEquals(0, primaryWriteLimits.getReplicaBytes());
            assertEquals(0, replicaWriteLimits.getCoordinatingBytes());
            assertEquals(0, replicaWriteLimits.getPrimaryBytes());
            assertEquals(0, replicaWriteLimits.getReplicaBytes());
        } finally {
            if (latchBlockingReplicationSend.getCount() > 0) {
                latchBlockingReplicationSend.countDown();
            }
            if (latchBlockingReplication.getCount() > 0) {
                latchBlockingReplicationSend.countDown();
            }
            primaryTransportService.clearAllRules();
        }
    }
}
