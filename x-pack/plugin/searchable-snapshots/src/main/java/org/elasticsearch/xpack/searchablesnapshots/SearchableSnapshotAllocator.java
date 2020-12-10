/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.searchablesnapshots;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.AllocateUnassignedDecision;
import org.elasticsearch.cluster.routing.allocation.AllocationDecision;
import org.elasticsearch.cluster.routing.allocation.ExistingShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.FailedShard;
import org.elasticsearch.cluster.routing.allocation.NodeAllocationResult;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.gateway.AsyncShardFetch;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.xpack.searchablesnapshots.action.cache.TransportSearchableSnapshotCacheStoresAction;
import org.elasticsearch.xpack.searchablesnapshots.action.cache.TransportSearchableSnapshotCacheStoresAction.NodeCacheFilesMetadata;
import org.elasticsearch.xpack.searchablesnapshots.action.cache.TransportSearchableSnapshotCacheStoresAction.NodesCacheFilesMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshots.SNAPSHOT_INDEX_ID_SETTING;
import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshots.SNAPSHOT_INDEX_NAME_SETTING;
import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshots.SNAPSHOT_REPOSITORY_SETTING;
import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshots.SNAPSHOT_SNAPSHOT_ID_SETTING;
import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshots.SNAPSHOT_SNAPSHOT_NAME_SETTING;

public class SearchableSnapshotAllocator implements ExistingShardsAllocator {

    private static final Logger logger = LogManager.getLogger(SearchableSnapshotAllocator.class);

    private final ConcurrentMap<ShardId, AsyncCacheStatusFetch> asyncFetchStore = ConcurrentCollections.newConcurrentMap();

    public static final String ALLOCATOR_NAME = "searchable_snapshot_allocator";

    private final Client client;

    public SearchableSnapshotAllocator(Client client) {
        this.client = client;
    }

    @Override
    public void beforeAllocation(RoutingAllocation allocation) {}

    @Override
    public void afterPrimariesBeforeReplicas(RoutingAllocation allocation) {}

    @Override
    public void allocateUnassigned(
        ShardRouting shardRouting,
        RoutingAllocation allocation,
        UnassignedAllocationHandler unassignedAllocationHandler
    ) {
        // TODO: cancel and jump to better available allocations?
        if (shardRouting.primary()
            && (shardRouting.recoverySource().getType() == RecoverySource.Type.EXISTING_STORE
                || shardRouting.recoverySource().getType() == RecoverySource.Type.EMPTY_STORE)) {
            // we always force snapshot recovery source to use the snapshot-based recovery process on the node

            final Settings indexSettings = allocation.metadata().index(shardRouting.index()).getSettings();
            final IndexId indexId = new IndexId(
                SNAPSHOT_INDEX_NAME_SETTING.get(indexSettings),
                SNAPSHOT_INDEX_ID_SETTING.get(indexSettings)
            );
            final SnapshotId snapshotId = new SnapshotId(
                SNAPSHOT_SNAPSHOT_NAME_SETTING.get(indexSettings),
                SNAPSHOT_SNAPSHOT_ID_SETTING.get(indexSettings)
            );
            final String repository = SNAPSHOT_REPOSITORY_SETTING.get(indexSettings);
            final Snapshot snapshot = new Snapshot(repository, snapshotId);

            shardRouting = unassignedAllocationHandler.updateUnassigned(
                shardRouting.unassignedInfo(),
                new RecoverySource.SnapshotRecoverySource(
                    RecoverySource.SnapshotRecoverySource.NO_API_RESTORE_UUID,
                    snapshot,
                    Version.CURRENT,
                    indexId
                ),
                allocation.changes()
            );
        }

        final AllocateUnassignedDecision allocateUnassignedDecision = decideAllocation(allocation, shardRouting);

        if (allocateUnassignedDecision.isDecisionTaken() && allocateUnassignedDecision.getAllocationDecision() != AllocationDecision.YES) {
            unassignedAllocationHandler.removeAndIgnore(allocateUnassignedDecision.getAllocationStatus(), allocation.changes());
        }
    }

    private AllocateUnassignedDecision decideAllocation(RoutingAllocation allocation, ShardRouting shardRouting) {
        assert shardRouting.unassigned();
        assert ExistingShardsAllocator.EXISTING_SHARDS_ALLOCATOR_SETTING.get(
            allocation.metadata().getIndexSafe(shardRouting.index()).getSettings()
        ).equals(ALLOCATOR_NAME);

        if (shardRouting.recoverySource().getType() == RecoverySource.Type.SNAPSHOT
            && allocation.snapshotShardSizeInfo().getShardSize(shardRouting) == null) {
            return AllocateUnassignedDecision.no(UnassignedInfo.AllocationStatus.FETCHING_SHARD_DATA, null);
        }

        final AsyncShardFetch.FetchResult<NodeCacheFilesMetadata> fetchedCacheData = fetchData(shardRouting, allocation);
        if (fetchedCacheData.hasData() == false) {
            return AllocateUnassignedDecision.no(UnassignedInfo.AllocationStatus.FETCHING_SHARD_DATA, null);
        }

        final boolean explain = allocation.debugDecision();
        final MatchingNodes matchingNodes = findMatchingNodes(shardRouting, allocation, fetchedCacheData, explain);
        assert explain == false || matchingNodes.nodeDecisions != null : "in explain mode, we must have individual node decisions";

        // pre-check if it can be allocated to any node that currently exists, so we won't list the store for it for nothing
        Tuple<Decision, Map<String, NodeAllocationResult>> result = canBeAllocatedToAtLeastOneNode(shardRouting, allocation);
        Decision allocateDecision = result.v1();
        if (allocateDecision.type() != Decision.Type.YES && (explain == false || asyncFetchStore.get(shardRouting.shardId()) == null)) {
            // only return early if we are not in explain mode, or we are in explain mode but we have not
            // yet attempted to fetch any shard data
            logger.trace("{}: ignoring allocation, can't be allocated on any node", shardRouting);
            return AllocateUnassignedDecision.no(
                UnassignedInfo.AllocationStatus.fromDecision(allocateDecision.type()),
                result.v2() != null ? new ArrayList<>(result.v2().values()) : null
            );
        }

        List<NodeAllocationResult> nodeDecisions = augmentExplanationsWithStoreInfo(result.v2(), matchingNodes.nodeDecisions);
        if (allocateDecision.type() != Decision.Type.YES) {
            return AllocateUnassignedDecision.no(UnassignedInfo.AllocationStatus.fromDecision(allocateDecision.type()), nodeDecisions);
        } else if (matchingNodes.getNodeWithHighestMatch() != null) {
            RoutingNode nodeWithHighestMatch = allocation.routingNodes().node(matchingNodes.getNodeWithHighestMatch().getId());
            // we only check on THROTTLE since we checked before on NO
            Decision decision = allocation.deciders().canAllocate(shardRouting, nodeWithHighestMatch, allocation);
            if (decision.type() == Decision.Type.THROTTLE) {
                // TODO: does this make sense? Unlike with the store we could evict the cache concurrently and wait for nothing?
                logger.debug(
                    "[{}][{}]: throttling allocation [{}] to [{}] in order to reuse its unallocated persistent cache",
                    shardRouting.index(),
                    shardRouting.id(),
                    shardRouting,
                    nodeWithHighestMatch.node()
                );
                return AllocateUnassignedDecision.throttle(nodeDecisions);
            } else {
                logger.debug(
                    "[{}][{}]: allocating [{}] to [{}] in order to reuse its persistent cache",
                    shardRouting.index(),
                    shardRouting.id(),
                    shardRouting,
                    nodeWithHighestMatch.node()
                );
                return AllocateUnassignedDecision.yes(nodeWithHighestMatch.node(), null, nodeDecisions, true);
            }
        }
        // TODO: do we need handling of delayed allocation for leaving replicas here?
        return AllocateUnassignedDecision.NOT_TAKEN;
    }

    @Override
    public AllocateUnassignedDecision explainUnassignedShardAllocation(ShardRouting shardRouting, RoutingAllocation routingAllocation) {
        assert shardRouting.unassigned();
        assert routingAllocation.debugDecision();
        return decideAllocation(routingAllocation, shardRouting);
    }

    @Override
    public void cleanCaches() {
        asyncFetchStore.clear();
    }

    @Override
    public void applyStartedShards(List<ShardRouting> startedShards, RoutingAllocation allocation) {
        for (ShardRouting startedShard : startedShards) {
            asyncFetchStore.remove(startedShard.shardId());
        }
    }

    @Override
    public void applyFailedShards(List<FailedShard> failedShards, RoutingAllocation allocation) {
        for (FailedShard failedShard : failedShards) {
            asyncFetchStore.remove(failedShard.getRoutingEntry().shardId());
        }
    }

    @Override
    public int getNumberOfInFlightFetches() {
        int count = 0;
        for (AsyncCacheStatusFetch fetch : asyncFetchStore.values()) {
            count += fetch.numberOfInFlightFetches();
        }
        return count;
    }

    private AsyncShardFetch.FetchResult<NodeCacheFilesMetadata> fetchData(ShardRouting shard, RoutingAllocation allocation) {
        final ShardId shardId = shard.shardId();
        final Settings indexSettings = allocation.metadata().index(shard.index()).getSettings();
        final IndexId indexId = new IndexId(SNAPSHOT_INDEX_NAME_SETTING.get(indexSettings), SNAPSHOT_INDEX_ID_SETTING.get(indexSettings));
        final SnapshotId snapshotId = new SnapshotId(
            SNAPSHOT_SNAPSHOT_NAME_SETTING.get(indexSettings),
            SNAPSHOT_SNAPSHOT_ID_SETTING.get(indexSettings)
        );
        final DiscoveryNodes nodes = allocation.nodes();
        final AsyncCacheStatusFetch asyncFetch = asyncFetchStore.computeIfAbsent(shardId, sid -> {
            final AsyncCacheStatusFetch fetch = new AsyncCacheStatusFetch();
            client.execute(
                TransportSearchableSnapshotCacheStoresAction.TYPE,
                new TransportSearchableSnapshotCacheStoresAction.Request(
                    snapshotId,
                    indexId,
                    shardId,
                    nodes.getDataNodes().values().toArray(DiscoveryNode.class)
                ),
                ActionListener.runAfter(new ActionListener<>() {
                    @Override
                    public void onResponse(NodesCacheFilesMetadata nodesCacheFilesMetadata) {
                        final Map<DiscoveryNode, NodeCacheFilesMetadata> res = new HashMap<>(nodesCacheFilesMetadata.getNodesMap().size());
                        for (Map.Entry<String, NodeCacheFilesMetadata> entry : nodesCacheFilesMetadata.getNodesMap().entrySet()) {
                            res.put(nodes.get(entry.getKey()), entry.getValue());
                        }
                        fetch.data = Collections.unmodifiableMap(res);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        // TODO: I guess this is the best we can do?
                        fetch.data = Collections.emptyMap();
                    }
                }, () -> client.admin().cluster().prepareReroute().execute(new ActionListener<>() {
                    @Override
                    public void onResponse(ClusterRerouteResponse clusterRerouteResponse) {
                        logger.trace("reroute succeeded after loading snapshot cache information");
                    }

                    @Override
                    public void onFailure(Exception e) {
                        logger.warn("reroute failed", e);
                    }
                }))
            );
            return fetch;
        });
        return new AsyncShardFetch.FetchResult<>(shardId, asyncFetch.data(), Collections.emptySet());
    }

    /**
     * Takes the store info for nodes that have a shard store and adds them to the node decisions,
     * leaving the node explanations untouched for those nodes that do not have any store information.
     */
    private static List<NodeAllocationResult> augmentExplanationsWithStoreInfo(
        Map<String, NodeAllocationResult> nodeDecisions,
        Map<String, NodeAllocationResult> withShardStores
    ) {
        if (nodeDecisions == null || withShardStores == null) {
            return null;
        }
        List<NodeAllocationResult> augmented = new ArrayList<>();
        for (Map.Entry<String, NodeAllocationResult> entry : nodeDecisions.entrySet()) {
            if (withShardStores.containsKey(entry.getKey())) {
                augmented.add(withShardStores.get(entry.getKey()));
            } else {
                augmented.add(entry.getValue());
            }
        }
        return augmented;
    }

    /**
     * Determines if the shard can be allocated on at least one node based on the allocation deciders.
     *
     * Returns the best allocation decision for allocating the shard on any node (i.e. YES if at least one
     * node decided YES, THROTTLE if at least one node decided THROTTLE, and NO if none of the nodes decided
     * YES or THROTTLE).  If in explain mode, also returns the node-level explanations as the second element
     * in the returned tuple.
     */
    private static Tuple<Decision, Map<String, NodeAllocationResult>> canBeAllocatedToAtLeastOneNode(
        ShardRouting shard,
        RoutingAllocation allocation
    ) {
        Decision madeDecision = Decision.NO;
        final boolean explain = allocation.debugDecision();
        Map<String, NodeAllocationResult> nodeDecisions = explain ? new HashMap<>() : null;
        for (ObjectCursor<DiscoveryNode> cursor : allocation.nodes().getDataNodes().values()) {
            RoutingNode node = allocation.routingNodes().node(cursor.value.getId());
            if (node == null) {
                continue;
            }
            // if we can't allocate it on a node, ignore it, for example, this handles
            // cases for only allocating a replica after a primary
            Decision decision = allocation.deciders().canAllocate(shard, node, allocation);
            if (decision.type() == Decision.Type.YES && madeDecision.type() != Decision.Type.YES) {
                if (explain) {
                    madeDecision = decision;
                } else {
                    return Tuple.tuple(decision, null);
                }
            } else if (madeDecision.type() == Decision.Type.NO && decision.type() == Decision.Type.THROTTLE) {
                madeDecision = decision;
            }
            if (explain) {
                nodeDecisions.put(node.nodeId(), new NodeAllocationResult(node.node(), null, decision));
            }
        }
        return Tuple.tuple(madeDecision, nodeDecisions);
    }

    private MatchingNodes findMatchingNodes(
        ShardRouting shard,
        RoutingAllocation allocation,
        AsyncShardFetch.FetchResult<NodeCacheFilesMetadata> data,
        boolean explain
    ) {
        Map<DiscoveryNode, Long> matchingNodes = new HashMap<>();
        Map<String, NodeAllocationResult> nodeDecisions = explain ? new HashMap<>() : null;
        for (Map.Entry<DiscoveryNode, NodeCacheFilesMetadata> nodeStoreEntry : data.getData().entrySet()) {
            DiscoveryNode discoNode = nodeStoreEntry.getKey();
            NodeCacheFilesMetadata nodeCacheFilesMetadata = nodeStoreEntry.getValue();
            // we don't have any existing cached bytes at all
            if (nodeCacheFilesMetadata.bytesCached() == 0L) {
                continue;
            }

            RoutingNode node = allocation.routingNodes().node(discoNode.getId());
            if (node == null) {
                continue;
            }

            // check if we can allocate on the node
            Decision decision = allocation.deciders().canAllocate(shard, node, allocation);
            Long matchingBytes = null;
            if (explain) {
                matchingBytes = nodeCacheFilesMetadata.bytesCached();
                NodeAllocationResult.ShardStoreInfo shardStoreInfo = new NodeAllocationResult.ShardStoreInfo(matchingBytes);
                nodeDecisions.put(node.nodeId(), new NodeAllocationResult(discoNode, shardStoreInfo, decision));
            }

            if (decision.type() == Decision.Type.NO) {
                continue;
            }

            if (matchingBytes == null) {
                matchingBytes = nodeCacheFilesMetadata.bytesCached();
            }
            matchingNodes.put(discoNode, matchingBytes);
            if (logger.isTraceEnabled()) {
                logger.trace(
                    "{}: node [{}] has [{}/{}] bytes of re-usable data",
                    shard,
                    discoNode.getName(),
                    new ByteSizeValue(matchingBytes),
                    matchingBytes
                );
            }
        }

        return new MatchingNodes(matchingNodes, nodeDecisions);
    }

    private static final class AsyncCacheStatusFetch {

        private volatile Map<DiscoveryNode, NodeCacheFilesMetadata> data;

        @Nullable
        Map<DiscoveryNode, NodeCacheFilesMetadata> data() {
            return data;
        }

        int numberOfInFlightFetches() {
            // TODO: give real number
            return 0;
        }
    }

    static class MatchingNodes {
        private final DiscoveryNode nodeWithHighestMatch;
        @Nullable
        private final Map<String, NodeAllocationResult> nodeDecisions;

        MatchingNodes(Map<DiscoveryNode, Long> matchingNodes, @Nullable Map<String, NodeAllocationResult> nodeDecisions) {
            this.nodeDecisions = nodeDecisions;
            this.nodeWithHighestMatch = matchingNodes.entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        }

        /**
         * Returns the node with the highest "non zero byte" match compared to
         * the primary.
         */
        @Nullable
        public DiscoveryNode getNodeWithHighestMatch() {
            return this.nodeWithHighestMatch;
        }
    }
}
