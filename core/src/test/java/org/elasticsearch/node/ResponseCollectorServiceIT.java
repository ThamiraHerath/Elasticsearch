/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.node;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import org.apache.lucene.util.English;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESSingleNodeTestCase;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Test that response stats are collected for queries executed on a real node
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0)
public class ResponseCollectorServiceIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            // If the queue settings are not changed, we currently use a regular
            // FixedThreadPoolExecutor instead of the QueueResizing one, which
            // captures the information like service time EWMA
            .put("thread_pool.search.min_queue_size", 10)
            .build();
    }

    public void testStatsAreCollected() throws Exception {
        int nodes = internalCluster().startNodes(randomIntBetween(2, 3)).size();
        prepareCreate("test",
            Settings.builder().put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, randomIntBetween(1, 3))
                .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING, "0-all")
                .put("index.unassigned.node_left.delayed_timeout", "0"));
        final int numDocs = between(20, 30);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex("test", "doc", "" + i).setSource("test", English.intToEnglish(i)).get();
        }
        refresh("test");
        ResponseCollectorService collector = internalCluster().getInstance(ResponseCollectorService.class);
        Map<String, Double> avgQueues = collector.getAvgQueueSize();
        Map<String, Double> avgService = collector.getAvgServiceTime();
        Map<String, Double> avgResponse = collector.getAvgResponseTime();
        assertThat(avgQueues.size(), equalTo(0));
        assertThat(avgService.size(), equalTo(0));
        assertThat(avgResponse.size(), equalTo(0));

        // perform enough searches that each node will have some response data collected
        for (int i = 0; i < 30; i++) {
            client().prepareSearch().setQuery(QueryBuilders.matchAllQuery()).get();
        }

        Set<String> nodeIds = new HashSet<>();
        final ClusterService clusterService = internalCluster().getInstance(ClusterService.class);
        Iterable<ResponseCollectorService> collectors = internalCluster().getInstances(ResponseCollectorService.class);
        collectors.forEach(c -> {

            Map<String, Double> queues = c.getAvgQueueSize();
            Map<String, Double> service = c.getAvgServiceTime();
            Map<String, Double> response = c.getAvgResponseTime();
            logger.info("--> queue sizes {}", queues);
            logger.info("--> service EWMAs {}", service);
            logger.info("--> response EWMAs {}", response);

            assertThat(queues.size(), greaterThan(0));
            assertThat(service.size(), greaterThan(0));
            assertThat(response.size(), greaterThan(0));
            nodeIds.addAll(queues.keySet());

            clusterService.state().getNodes().getDataNodes().values().forEach((Consumer<? super ObjectCursor<DiscoveryNode>>) n -> {
                String nodeId = n.value.getId();
                assertThat(nodeId, queues.get(nodeId), greaterThanOrEqualTo(0.0));
                assertThat(nodeId, service.get(nodeId), greaterThan(0.0));
                assertThat(nodeId, response.get(nodeId), greaterThan(0.0));
            });
        });

        logger.info("--> stopping data node");
        assertTrue(internalCluster().stopRandomDataNode());
        ensureYellow("test");

        final ClusterService newClusterService = internalCluster().getInstance(ClusterService.class);
        logger.info("--> there are now {} nodes", newClusterService.state().getNodes().getDataNodes().size());
        collectors = internalCluster().getInstances(ResponseCollectorService.class);
        collectors.forEach(c -> {

            Map<String, Double> queues = c.getAvgQueueSize();
            Map<String, Double> service = c.getAvgServiceTime();
            Map<String, Double> response = c.getAvgResponseTime();
            logger.info("--> queue sizes {}", queues);
            logger.info("--> service EWMAs {}", service);
            logger.info("--> response EWMAs {}", response);

            assertThat(queues.size(), greaterThan(0));
            assertThat(service.size(), greaterThan(0));
            assertThat(response.size(), greaterThan(0));

            newClusterService.state().getNodes().getDataNodes().values().forEach((Consumer<? super ObjectCursor<DiscoveryNode>>) n -> {
                String nodeId = n.value.getId();
                assertThat(nodeId, queues.get(nodeId), greaterThanOrEqualTo(0.0));
                assertThat(nodeId, service.get(nodeId), greaterThan(0.0));
                assertThat(nodeId, response.get(nodeId), greaterThan(0.0));
            });
        });

        assertBusy(() -> {
            logger.info("--> checking that node was removed");
            Set<String> newNodeIds = new HashSet<>();
            Iterable<ResponseCollectorService> innerCollectors = internalCluster().getInstances(ResponseCollectorService.class);
            innerCollectors.forEach(c -> {
                Map<String, Double> queues = c.getAvgQueueSize();
                newNodeIds.addAll(queues.keySet());
            });
            logger.info("--> original nodes: {}, new nodes: {}", nodeIds, newNodeIds);
            assertThat(Sets.difference(nodeIds, newNodeIds).size(), equalTo(1));
        });
    }
}
