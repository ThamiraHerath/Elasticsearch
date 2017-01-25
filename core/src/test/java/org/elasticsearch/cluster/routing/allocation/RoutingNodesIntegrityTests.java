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

package org.elasticsearch.cluster.routing.allocation;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.decider.ClusterRebalanceAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.ThrottlingAllocationDecider;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;

import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.STARTED;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class RoutingNodesIntegrityTests extends ESAllocationTestCase {
    private final Logger logger = Loggers.getLogger(IndexBalanceTests.class);

    public void testBalanceAllNodesStarted() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1).build());

        logger.info("Building initial routing table");

        MetaData metaData = MetaData.builder().put(IndexMetaData.builder("test").settings(settings(Version.CURRENT)).numberOfShards(3).numberOfReplicas(1))
                .put(IndexMetaData.builder("test1").settings(settings(Version.CURRENT)).numberOfShards(3).numberOfReplicas(1)).build();

        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metaData.index("test")).addAsNew(metaData.index("test1")).build();

        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)).metaData(metaData).routingTable(initialRoutingTable).build();

        logger.info("Adding three node and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
                .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")).add(newNode("node3"))).build();
        RoutingNodes routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        // all shards are unassigned. so no inactive shards or primaries.
        assertThat(routingNodes.hasInactiveShards(), equalTo(false));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(true));

        clusterState = strategy.reroute(clusterState, "reroute");
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(true));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(true));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));

        logger.info("Another round of rebalancing");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        routingNodes = clusterState.getRoutingNodes();
        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));

        logger.info("Reroute, nothing should change");
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));

        logger.info("Start the more shards");
        routingNodes = clusterState.getRoutingNodes();
        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(false));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));

        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));

    }

    public void testBalanceIncrementallyStartNodes() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1).build());

        logger.info("Building initial routing table");

        MetaData metaData = MetaData.builder().put(IndexMetaData.builder("test").settings(settings(Version.CURRENT)).numberOfShards(3).numberOfReplicas(1))
                .put(IndexMetaData.builder("test1").settings(settings(Version.CURRENT)).numberOfShards(3).numberOfReplicas(1)).build();

        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metaData.index("test")).addAsNew(metaData.index("test1")).build();

        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)).metaData(metaData).routingTable(initialRoutingTable).build();

        logger.info("Adding one node and performing rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder().add(newNode("node1"))).build();

        clusterState = strategy.reroute(clusterState, "reroute");

        logger.info("Add another node and perform rerouting, nothing will happen since primary not started");
        clusterState = ClusterState.builder(clusterState)
                .nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node2"))).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        logger.info("Start the primary shard");
        RoutingNodes routingNodes = clusterState.getRoutingNodes();
        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));

        logger.info("Reroute, nothing should change");
        clusterState = strategy.reroute(clusterState, "reroute");

        logger.info("Start the backup shard");
        routingNodes = clusterState.getRoutingNodes();
        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        routingNodes = clusterState.getRoutingNodes();

        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        routingNodes = clusterState.getRoutingNodes();

        logger.info("Add another node and perform rerouting, nothing will happen since primary not started");
        clusterState = ClusterState.builder(clusterState)
                .nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node3"))).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        logger.info("Reroute, nothing should change");
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));

        logger.info("Start the backup shard");
        routingNodes = clusterState.getRoutingNodes();
        newState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingNodes = clusterState.getRoutingNodes();

        assertThat(clusterState.routingTable().index("test").shards().size(), equalTo(3));

        newState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingNodes = clusterState.getRoutingNodes();

        assertThat(clusterState.routingTable().index("test1").shards().size(), equalTo(3));

        assertThat(routingNodes.node("node1").numberOfShardsWithState(STARTED), equalTo(4));
        assertThat(routingNodes.node("node2").numberOfShardsWithState(STARTED), equalTo(4));
        assertThat(routingNodes.node("node3").numberOfShardsWithState(STARTED), equalTo(4));

        assertThat(routingNodes.node("node1").shardsWithState("test", STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node2").shardsWithState("test", STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node3").shardsWithState("test", STARTED).size(), equalTo(2));

        assertThat(routingNodes.node("node1").shardsWithState("test1", STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node2").shardsWithState("test1", STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node3").shardsWithState("test1", STARTED).size(), equalTo(2));
    }

    public void testBalanceAllNodesStartedAddIndex() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 1)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 3)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_CONCURRENT_OUTGOING_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1).build());

        logger.info("Building initial routing table");

        MetaData metaData = MetaData.builder().put(IndexMetaData.builder("test").settings(settings(Version.CURRENT)).numberOfShards(3).numberOfReplicas(1)).build();

        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metaData.index("test")).build();

        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)).metaData(metaData).routingTable(initialRoutingTable).build();

        logger.info("Adding three node and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
                .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")).add(newNode("node3"))).build();

        RoutingNodes routingNodes = clusterState.getRoutingNodes();
        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(false));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(true));

        clusterState = strategy.reroute(clusterState, "reroute");
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(true));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(true));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));

        logger.info("Another round of rebalancing");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())).build();
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));

        routingNodes = clusterState.getRoutingNodes();
        assertThat(routingNodes.node("node1").numberOfShardsWithState(INITIALIZING), equalTo(1));
        assertThat(routingNodes.node("node2").numberOfShardsWithState(INITIALIZING), equalTo(1));
        assertThat(routingNodes.node("node3").numberOfShardsWithState(INITIALIZING), equalTo(1));

        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(true));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));
        assertThat(routingNodes.node("node1").numberOfShardsWithState(STARTED), equalTo(1));
        assertThat(routingNodes.node("node2").numberOfShardsWithState(STARTED), equalTo(1));
        assertThat(routingNodes.node("node3").numberOfShardsWithState(STARTED), equalTo(1));

        logger.info("Reroute, nothing should change");
        newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));

        logger.info("Start the more shards");
        routingNodes = clusterState.getRoutingNodes();
        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(false));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));

        assertThat(routingNodes.node("node1").numberOfShardsWithState(STARTED), equalTo(2));
        assertThat(routingNodes.node("node2").numberOfShardsWithState(STARTED), equalTo(2));
        assertThat(routingNodes.node("node3").numberOfShardsWithState(STARTED), equalTo(2));

        assertThat(routingNodes.node("node1").shardsWithState("test", STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node2").shardsWithState("test", STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node3").shardsWithState("test", STARTED).size(), equalTo(2));

        logger.info("Add new index 3 shards 1 replica");

        metaData = MetaData.builder(clusterState.metaData())
                .put(IndexMetaData.builder("test1").settings(settings(Version.CURRENT)
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 3)
                        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                ))
                .build();
        RoutingTable updatedRoutingTable = RoutingTable.builder(clusterState.routingTable())
            .addAsNew(metaData.index("test1"))
            .build();
        clusterState = ClusterState.builder(clusterState).metaData(metaData).routingTable(updatedRoutingTable).build();
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(false));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(true));

        assertThat(clusterState.routingTable().index("test1").shards().size(), equalTo(3));

        clusterState = strategy.reroute(clusterState, "reroute");

        logger.info("Reroute, assign");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())).build();
        newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(true));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(true));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));


        logger.info("Reroute, start the primaries");
        routingNodes = clusterState.getRoutingNodes();
        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(true));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));

        logger.info("Reroute, start the replicas");
        routingNodes = clusterState.getRoutingNodes();
        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(false));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));


        assertThat(routingNodes.node("node1").numberOfShardsWithState(STARTED), equalTo(4));
        assertThat(routingNodes.node("node2").numberOfShardsWithState(STARTED), equalTo(4));
        assertThat(routingNodes.node("node3").numberOfShardsWithState(STARTED), equalTo(4));

        assertThat(routingNodes.node("node1").shardsWithState("test1", STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node2").shardsWithState("test1", STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node3").shardsWithState("test1", STARTED).size(), equalTo(2));

        logger.info("kill one node");
        IndexShardRoutingTable indexShardRoutingTable = clusterState.routingTable().index("test").shard(0);
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).remove(indexShardRoutingTable.primaryShard().currentNodeId())).build();
        clusterState = strategy.deassociateDeadNodes(clusterState, true, "reroute");
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(true));
        // replica got promoted to primary
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));

        logger.info("Start Recovering shards round 1");
        routingNodes = clusterState.getRoutingNodes();
        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(true));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));

        logger.info("Start Recovering shards round 2");
        routingNodes = clusterState.getRoutingNodes();
        clusterState = strategy.applyStartedShards(clusterState, routingNodes.shardsWithState(INITIALIZING));
        routingNodes = clusterState.getRoutingNodes();

        assertThat(assertShardStats(routingNodes), equalTo(true));
        assertThat(routingNodes.hasInactiveShards(), equalTo(false));
        assertThat(routingNodes.hasInactivePrimaries(), equalTo(false));
        assertThat(routingNodes.hasUnassignedPrimaries(), equalTo(false));

    }

    private boolean assertShardStats(RoutingNodes routingNodes) {
        return RoutingNodes.assertShardStats(routingNodes);
    }
}
