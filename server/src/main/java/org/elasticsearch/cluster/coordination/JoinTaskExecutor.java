/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.cluster.coordination;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.NotMasterException;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RerouteService;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.elasticsearch.gateway.GatewayService.STATE_NOT_RECOVERED_BLOCK;

public class JoinTaskExecutor implements ClusterStateTaskExecutor<JoinTask> {

    private static final Logger logger = LogManager.getLogger(JoinTaskExecutor.class);

    private final AllocationService allocationService;
    private final RerouteService rerouteService;

    public JoinTaskExecutor(AllocationService allocationService, RerouteService rerouteService) {
        this.allocationService = allocationService;
        this.rerouteService = rerouteService;
    }

    @Override
    public ClusterTasksResult<JoinTask> execute(ClusterState currentState, List<JoinTask> joinTasks) {
        final ClusterTasksResult.Builder<JoinTask> results = ClusterTasksResult.builder();

        final boolean isBecomingMaster = joinTasks.stream().anyMatch(JoinTask::isBecomingMaster);

        final DiscoveryNodes currentNodes = currentState.nodes();
        boolean nodesChanged = false;
        ClusterState.Builder newState;

        if (currentNodes.getMasterNode() == null && isBecomingMaster) {
            // use these joins to try and become the master.
            // Note that we don't have to do any validation of the amount of joining nodes - the commit
            // during the cluster state publishing guarantees that we have enough
            newState = becomeMasterAndTrimConflictingNodes(currentState, joinTasks);
            nodesChanged = true;
        } else if (currentNodes.isLocalNodeElectedMaster() == false) {
            logger.trace("processing node joins, but we are not the master. current master: {}", currentNodes.getMasterNode());
            throw new NotMasterException("Node [" + currentNodes.getLocalNode() + "] not master for join request");
        } else {
            newState = ClusterState.builder(currentState);
        }

        DiscoveryNodes.Builder nodesBuilder = DiscoveryNodes.builder(newState.nodes());

        assert nodesBuilder.isLocalNodeElectedMaster();

        Version minClusterNodeVersion = newState.nodes().getMinNodeVersion();
        Version maxClusterNodeVersion = newState.nodes().getMaxNodeVersion();
        // if the cluster is not fully-formed then the min version is not meaningful
        final boolean enforceVersionBarrier = currentState.getBlocks().hasGlobalBlock(STATE_NOT_RECOVERED_BLOCK) == false;
        // processing any joins
        Map<String, String> joiniedNodeNameIds = new HashMap<>();
        for (final JoinTask joinTask : joinTasks) {
            final List<Runnable> onTaskSuccess = new ArrayList<>(joinTask.nodeCount());
            for (final JoinTask.NodeJoinTask nodeJoinTask : joinTask.nodeJoinTasks()) {
                final DiscoveryNode node = nodeJoinTask.node();
                if (currentNodes.nodeExistsWithSameRoles(node)) {
                    logger.debug("received a join request for an existing node [{}]", node);
                } else {
                    try {
                        if (enforceVersionBarrier) {
                            ensureVersionBarrier(node.getVersion(), minClusterNodeVersion);
                        }
                        ensureNodesCompatibility(node.getVersion(), minClusterNodeVersion, maxClusterNodeVersion);
                        // we do this validation quite late to prevent race conditions between nodes joining and importing dangling indices
                        // we have to reject nodes that don't support all indices we have in this cluster
                        ensureIndexCompatibility(node.getVersion(), currentState.getMetadata());
                        nodesBuilder.add(node);
                        nodesChanged = true;
                        minClusterNodeVersion = Version.min(minClusterNodeVersion, node.getVersion());
                        maxClusterNodeVersion = Version.max(maxClusterNodeVersion, node.getVersion());
                        if (node.isMasterNode()) {
                            joiniedNodeNameIds.put(node.getName(), node.getId());
                        }
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        onTaskSuccess.add(() -> nodeJoinTask.listener().onFailure(e));
                        continue;
                    }
                }
                onTaskSuccess.add(() -> nodeJoinTask.listener().onResponse(null));
            }
            results.success(joinTask, new ActionListener<>() {
                @Override
                public void onResponse(ClusterState clusterState) {
                    for (Runnable joinCompleter : onTaskSuccess) {
                        joinCompleter.run();
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    joinTask.onFailure(e);
                }
            });
        }

        if (nodesChanged) {
            rerouteService.reroute(
                "post-join reroute",
                Priority.HIGH,
                ActionListener.wrap(r -> logger.trace("post-join reroute completed"), e -> logger.debug("post-join reroute failed", e))
            );

            if (joiniedNodeNameIds.isEmpty() == false) {
                Set<CoordinationMetadata.VotingConfigExclusion> currentVotingConfigExclusions = currentState.getVotingConfigExclusions();
                Set<CoordinationMetadata.VotingConfigExclusion> newVotingConfigExclusions = currentVotingConfigExclusions.stream()
                    .map(e -> {
                        // Update nodeId in VotingConfigExclusion when a new node with excluded node name joins
                        if (CoordinationMetadata.VotingConfigExclusion.MISSING_VALUE_MARKER.equals(e.getNodeId())
                            && joiniedNodeNameIds.containsKey(e.getNodeName())) {
                            return new CoordinationMetadata.VotingConfigExclusion(joiniedNodeNameIds.get(e.getNodeName()), e.getNodeName());
                        } else {
                            return e;
                        }
                    })
                    .collect(Collectors.toSet());

                // if VotingConfigExclusions did get updated
                if (newVotingConfigExclusions.equals(currentVotingConfigExclusions) == false) {
                    CoordinationMetadata.Builder coordMetadataBuilder = CoordinationMetadata.builder(currentState.coordinationMetadata())
                        .clearVotingConfigExclusions();
                    newVotingConfigExclusions.forEach(coordMetadataBuilder::addVotingConfigExclusion);
                    Metadata newMetadata = Metadata.builder(currentState.metadata())
                        .coordinationMetadata(coordMetadataBuilder.build())
                        .build();
                    return results.build(
                        allocationService.adaptAutoExpandReplicas(newState.nodes(nodesBuilder).metadata(newMetadata).build())
                    );
                }
            }

            final ClusterState updatedState = allocationService.adaptAutoExpandReplicas(newState.nodes(nodesBuilder).build());
            assert enforceVersionBarrier == false
                || updatedState.nodes().getMinNodeVersion().onOrAfter(currentState.nodes().getMinNodeVersion())
                : "min node version decreased from ["
                    + currentState.nodes().getMinNodeVersion()
                    + "] to ["
                    + updatedState.nodes().getMinNodeVersion()
                    + "]";
            return results.build(updatedState);
        } else {
            // we must return a new cluster state instance to force publishing. This is important
            // for the joining node to finalize its join and set us as a master
            return results.build(newState.build());
        }
    }

    protected ClusterState.Builder becomeMasterAndTrimConflictingNodes(ClusterState currentState, List<JoinTask> joinTasks) {
        assert currentState.nodes().getMasterNodeId() == null : currentState;
        DiscoveryNodes currentNodes = currentState.nodes();
        DiscoveryNodes.Builder nodesBuilder = DiscoveryNodes.builder(currentNodes);
        nodesBuilder.masterNodeId(currentState.nodes().getLocalNodeId());

        for (final JoinTask joinTask : joinTasks) {
            for (final DiscoveryNode joiningNode : joinTask.nodes()) {
                final DiscoveryNode nodeWithSameId = nodesBuilder.get(joiningNode.getId());
                if (nodeWithSameId != null && nodeWithSameId.equals(joiningNode) == false) {
                    logger.debug("removing existing node [{}], which conflicts with incoming join from [{}]", nodeWithSameId, joiningNode);
                    nodesBuilder.remove(nodeWithSameId.getId());
                }
                final DiscoveryNode nodeWithSameAddress = currentNodes.findByAddress(joiningNode.getAddress());
                if (nodeWithSameAddress != null && nodeWithSameAddress.equals(joiningNode) == false) {
                    logger.debug(
                        "removing existing node [{}], which conflicts with incoming join from [{}]",
                        nodeWithSameAddress,
                        joiningNode
                    );
                    nodesBuilder.remove(nodeWithSameAddress.getId());
                }
            }
        }

        // now trim any left over dead nodes - either left there when the previous master stepped down
        // or removed by us above
        ClusterState tmpState = ClusterState.builder(currentState)
            .nodes(nodesBuilder)
            .blocks(ClusterBlocks.builder().blocks(currentState.blocks()).removeGlobalBlock(NoMasterBlockService.NO_MASTER_BLOCK_ID))
            .build();
        logger.trace("becomeMasterAndTrimConflictingNodes: {}", tmpState.nodes());
        allocationService.cleanCaches();
        tmpState = PersistentTasksCustomMetadata.disassociateDeadNodes(tmpState);
        return ClusterState.builder(allocationService.disassociateDeadNodes(tmpState, false, "removed dead nodes on election"));
    }

    @Override
    public boolean runOnlyOnMaster() {
        // we validate that we are allowed to change the cluster state during cluster state processing
        return false;
    }

    /**
     * Ensures that all indices are compatible with the given node version. This will ensure that all indices in the given metadata
     * will not be created with a newer version of elasticsearch as well as that all indices are newer or equal to the minimum index
     * compatibility version.
     * @see Version#minimumIndexCompatibilityVersion()
     * @throws IllegalStateException if any index is incompatible with the given version
     */
    public static void ensureIndexCompatibility(final Version nodeVersion, Metadata metadata) {
        Version supportedIndexVersion = nodeVersion.minimumIndexCompatibilityVersion();
        // we ensure that all indices in the cluster we join are compatible with us no matter if they are
        // closed or not we can't read mappings of these indices so we need to reject the join...
        for (IndexMetadata idxMetadata : metadata) {
            if (idxMetadata.getCompatibilityVersion().after(nodeVersion)) {
                throw new IllegalStateException(
                    "index "
                        + idxMetadata.getIndex()
                        + " version not supported: "
                        + idxMetadata.getCompatibilityVersion()
                        + " the node version is: "
                        + nodeVersion
                );
            }
            if (idxMetadata.getCompatibilityVersion().before(supportedIndexVersion)) {
                throw new IllegalStateException(
                    "index "
                        + idxMetadata.getIndex()
                        + " version not supported: "
                        + idxMetadata.getCompatibilityVersion()
                        + " minimum compatible index version is: "
                        + supportedIndexVersion
                );
            }
        }
    }

    /** ensures that the joining node has a version that's compatible with all current nodes*/
    public static void ensureNodesCompatibility(final Version joiningNodeVersion, DiscoveryNodes currentNodes) {
        final Version minNodeVersion = currentNodes.getMinNodeVersion();
        final Version maxNodeVersion = currentNodes.getMaxNodeVersion();
        ensureNodesCompatibility(joiningNodeVersion, minNodeVersion, maxNodeVersion);
    }

    /** ensures that the joining node has a version that's compatible with a given version range */
    public static void ensureNodesCompatibility(Version joiningNodeVersion, Version minClusterNodeVersion, Version maxClusterNodeVersion) {
        assert minClusterNodeVersion.onOrBefore(maxClusterNodeVersion) : minClusterNodeVersion + " > " + maxClusterNodeVersion;
        if (joiningNodeVersion.isCompatible(maxClusterNodeVersion) == false) {
            throw new IllegalStateException(
                "node version ["
                    + joiningNodeVersion
                    + "] is not supported. "
                    + "The cluster contains nodes with version ["
                    + maxClusterNodeVersion
                    + "], which is incompatible."
            );
        }
        if (joiningNodeVersion.isCompatible(minClusterNodeVersion) == false) {
            throw new IllegalStateException(
                "node version ["
                    + joiningNodeVersion
                    + "] is not supported."
                    + "The cluster contains nodes with version ["
                    + minClusterNodeVersion
                    + "], which is incompatible."
            );
        }
    }

    /**
     * ensures that the joining node's version is equal or higher to the minClusterNodeVersion. This is needed
     * to ensure that if the master is already fully operating under the new version, it doesn't go back to mixed
     * version mode
     **/
    public static void ensureVersionBarrier(Version joiningNodeVersion, Version minClusterNodeVersion) {
        if (joiningNodeVersion.before(minClusterNodeVersion)) {
            throw new IllegalStateException(
                "node version ["
                    + joiningNodeVersion
                    + "] may not join a cluster comprising only nodes of version ["
                    + minClusterNodeVersion
                    + "] or greater"
            );
        }
    }

    public static Collection<BiConsumer<DiscoveryNode, ClusterState>> addBuiltInJoinValidators(
        Collection<BiConsumer<DiscoveryNode, ClusterState>> onJoinValidators
    ) {
        final Collection<BiConsumer<DiscoveryNode, ClusterState>> validators = new ArrayList<>();
        validators.add((node, state) -> {
            ensureNodesCompatibility(node.getVersion(), state.getNodes());
            ensureIndexCompatibility(node.getVersion(), state.getMetadata());
        });
        validators.addAll(onJoinValidators);
        return Collections.unmodifiableCollection(validators);
    }
}
