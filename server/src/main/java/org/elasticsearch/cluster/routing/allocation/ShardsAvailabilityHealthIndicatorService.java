/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.NodesShutdownMetadata;
import org.elasticsearch.cluster.metadata.SingleNodeShutdownMetadata;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.FilterAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.SameShardAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.ShardsLimitAllocationDecider;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.health.HealthIndicatorDetails;
import org.elasticsearch.health.HealthIndicatorImpact;
import org.elasticsearch.health.HealthIndicatorResult;
import org.elasticsearch.health.HealthIndicatorService;
import org.elasticsearch.health.HealthStatus;
import org.elasticsearch.health.ImpactArea;
import org.elasticsearch.health.SimpleHealthIndicatorDetails;
import org.elasticsearch.health.UserAction;
import org.elasticsearch.snapshots.SnapshotShardSizeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static org.elasticsearch.cluster.health.ClusterShardHealth.getInactivePrimaryHealth;
import static org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE_SETTING;
import static org.elasticsearch.health.HealthStatus.GREEN;
import static org.elasticsearch.health.HealthStatus.RED;
import static org.elasticsearch.health.HealthStatus.YELLOW;
import static org.elasticsearch.health.ServerHealthComponents.DATA;

/**
 * This indicator reports health for shards.
 * <p>
 * Indicator will report:
 * * RED when one or more primary shards are not available
 * * YELLOW when one or more replica shards are not available
 * * GREEN otherwise
 * <p>
 * Each shard needs to be available and replicated in order to guarantee high availability and prevent data loses.
 * Shards allocated on nodes scheduled for restart (using nodes shutdown API) will not degrade this indicator health.
 */
public class ShardsAvailabilityHealthIndicatorService implements HealthIndicatorService {

    private static final Logger LOGGER = LogManager.getLogger(ShardsAvailabilityHealthIndicatorService.class);

    public static final String NAME = "shards_availability";

    private static final String DATA_TIER_ALLOCATION_DECIDER_NAME = "data_tier";

    private final ClusterService clusterService;
    private final AllocationService allocationService;

    public ShardsAvailabilityHealthIndicatorService(ClusterService clusterService, AllocationService allocationService) {
        this.clusterService = clusterService;
        this.allocationService = allocationService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String component() {
        return DATA;
    }

    @Override
    public HealthIndicatorResult calculate(boolean includeDetails) {
        var state = clusterService.state();
        var shutdown = state.getMetadata().custom(NodesShutdownMetadata.TYPE, NodesShutdownMetadata.EMPTY);
        var status = new ShardAllocationStatus(state.getMetadata());

        for (IndexRoutingTable indexShardRouting : state.routingTable()) {
            for (int i = 0; i < indexShardRouting.size(); i++) {
                IndexShardRoutingTable shardRouting = indexShardRouting.shard(i);
                status.addPrimary(shardRouting.primaryShard(), state, shutdown, includeDetails);
                for (ShardRouting replicaShard : shardRouting.replicaShards()) {
                    status.addReplica(replicaShard, state, shutdown, includeDetails);
                }
            }
        }
        return createIndicator(
            status.getStatus(),
            status.getSummary(),
            status.getDetails(includeDetails),
            status.getImpacts(),
            status.getUserActions(includeDetails)
        );
    }

    // TODO: #85572 Fill in help URLs once they are finalized
    public static final UserAction.Definition ACTION_RESTORE_FROM_SNAPSHOT = new UserAction.Definition(
        "restore_from_snapshot",
        Explanations.Allocation.NO_COPIES,
        null
    );
    public static final UserAction.Definition ACTION_CHECK_ALLOCATION_EXPLAIN_API = new UserAction.Definition(
        "explain_allocations",
        "Elasticsearch isn't allowed to allocate some shards from these indices to any of the nodes in the cluster. Diagnose the issue by "
            + "calling the allocation explain api for an index [GET _cluster/allocation/explain]. Choose a node to which you expect a "
            + "shard to be allocated, find this node in the node-by-node explanation, and address the reasons which prevent Elasticsearch "
            + "from allocating a shard there.",
        null
    );

    public static final UserAction.Definition ACTION_ENABLE_INDEX_ROUTING_ALLOCATION = new UserAction.Definition(
        "enable_index_allocations",
        "Elasticsearch isn't allowed to allocate some shards from these indices because allocation for those shards has been disabled. "
            + "Check that the ["
            + INDEX_ROUTING_ALLOCATION_ENABLE_SETTING.getKey()
            + "] index settings are set to ["
            + EnableAllocationDecider.Allocation.ALL.toString().toLowerCase(Locale.getDefault())
            + "].",
        null
    );
    public static final UserAction.Definition ACTION_ENABLE_CLUSTER_ROUTING_ALLOCATION = new UserAction.Definition(
        "enable_cluster_allocations",
        "Elasticsearch isn't allowed to allocate some shards from these indices because allocation for those shards has been disabled. "
            + "Check that the ["
            + EnableAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ENABLE_SETTING.getKey()
            + "] cluster setting is set to ["
            + EnableAllocationDecider.Allocation.ALL.toString().toLowerCase(Locale.getDefault())
            + "].",
        null
    );

    public static final Map<String, UserAction.Definition> ACTION_ENABLE_TIERS_LOOKUP;
    static {
        Map<String, UserAction.Definition> lookup = DataTier.ALL_DATA_TIERS.stream()
            .collect(
                Collectors.toMap(
                    tier -> tier,
                    tier -> new UserAction.Definition(
                        "enable_data_tiers_" + tier,
                        "Elasticsearch isn't allowed to allocate shards from these indices because the indices expect to be allocated to "
                            + "data tier nodes, but there were not any nodes with the expected tiers found in the cluster. Add nodes with "
                            + "the ["
                            + tier
                            + "] role to the cluster.",
                        null
                    )
                )
            );
        ACTION_ENABLE_TIERS_LOOKUP = Collections.unmodifiableMap(lookup);
    }

    public static final UserAction.Definition ACTION_SHARD_LIMIT = new UserAction.Definition(
        "increase_shard_limit",
        "Elasticsearch isn't allowed to allocate some shards from these indices to any of the nodes in its data tier because each node in "
            + "the tier has reached its shard limit. Increase the values for the ["
            + ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING.getKey()
            + "] index setting on each index or add more nodes to the target tiers.",
        null
    );
    public static final UserAction.Definition ACTION_MIGRATE_TIERS = new UserAction.Definition(
        "migrate_data_tiers",
        "Elasticsearch isn't allowed to allocate some shards from these indices to any of the nodes in their data tiers because no nodes "
            + "in the tiers are compatible with the allocation filters in the index settings. Remove the conflicting allocation filters "
            + "from each index's settings or try migrating to data tiers using the data tier migration action "
            + "[POST /_ilm/migrate_to_data_tiers].",
        null
    );
    public static final UserAction.Definition ACTION_INCREASE_TIER_CAPACITY = new UserAction.Definition(
        "increase_tier_capacity_for_allocations",
        "Elasticsearch isn't allowed to allocate some shards from these indices to any of the nodes in their data tiers because there are "
            + "not enough nodes in the tier to allocate each shard copy on a different node. Increase the number of nodes in this tier or "
            + "decrease the number of replicas your indices are using.",
        null

    );

    private class ShardAllocationCounts {
        private boolean available = true; // This will be true even if no replicas are expected, as long as none are unavailable
        private int unassigned = 0;
        private int unassigned_new = 0;
        private int unassigned_restarting = 0;
        private int initializing = 0;
        private int started = 0;
        private int relocating = 0;
        private final Set<String> indicesWithUnavailableShards = new HashSet<>();
        private final Map<UserAction.Definition, Set<String>> userActions = new HashMap<>();

        public void increment(ShardRouting routing, ClusterState state, NodesShutdownMetadata shutdowns, boolean includeDetails) {
            boolean isNew = isUnassignedDueToNewInitialization(routing);
            boolean isRestarting = isUnassignedDueToTimelyRestart(routing, shutdowns);
            available &= routing.active() || isRestarting || isNew;
            if ((routing.active() || isRestarting || isNew) == false) {
                indicesWithUnavailableShards.add(routing.getIndexName());
            }

            switch (routing.state()) {
                case UNASSIGNED -> {
                    if (isNew) {
                        unassigned_new++;
                    } else if (isRestarting) {
                        unassigned_restarting++;
                    } else {
                        unassigned++;
                        if (includeDetails) {
                            diagnoseUnassignedShardRouting(routing, state).forEach(
                                definition -> addUserAction(definition, routing.getIndexName())
                            );
                        }
                    }
                }
                case INITIALIZING -> initializing++;
                case STARTED -> started++;
                case RELOCATING -> relocating++;
            }
        }

        private void addUserAction(UserAction.Definition actionDef, String indexName) {
            userActions.computeIfAbsent(actionDef, (k) -> new HashSet<>()).add(indexName);
        }
    }

    private static boolean isUnassignedDueToTimelyRestart(ShardRouting routing, NodesShutdownMetadata shutdowns) {
        var info = routing.unassignedInfo();
        if (info == null || info.getReason() != UnassignedInfo.Reason.NODE_RESTARTING) {
            return false;
        }
        var shutdown = shutdowns.getAllNodeMetadataMap().get(info.getLastAllocatedNodeId());
        if (shutdown == null || shutdown.getType() != SingleNodeShutdownMetadata.Type.RESTART) {
            return false;
        }
        var now = System.nanoTime();
        var restartingAllocationDelayExpiration = info.getUnassignedTimeInNanos() + shutdown.getAllocationDelay().nanos();
        return now <= restartingAllocationDelayExpiration;
    }

    private static boolean isUnassignedDueToNewInitialization(ShardRouting routing) {
        return routing.primary() && routing.active() == false && getInactivePrimaryHealth(routing) == ClusterHealthStatus.YELLOW;
    }

    /**
     * Generate a list of actions for a user to take that should allow this shard to be assigned.
     * @param shardRouting An unassigned shard routing
     * @param state State of the cluster
     * @return A list of actions for the user to take for this shard
     */
    List<UserAction.Definition> diagnoseUnassignedShardRouting(ShardRouting shardRouting, ClusterState state) {
        List<UserAction.Definition> actions = new ArrayList<>();
        switch (shardRouting.unassignedInfo().getLastAllocationStatus()) {
            case NO_VALID_SHARD_COPY:
                if (UnassignedInfo.Reason.NODE_LEFT == shardRouting.unassignedInfo().getReason()) {
                    actions.add(ACTION_RESTORE_FROM_SNAPSHOT);
                }
                break;
            case DECIDERS_NO:
                explainAllocationsAndDiagnoseDeciders(actions, shardRouting, state);
                break;
            default:
                break;
        }
        return actions;
    }

    /**
     * For a shard that is unassigned due to a DECIDERS_NO result, this will explain the allocation and attempt to generate
     * user actions that should allow the shard to be assigned.
     * @param actions A list to collect user actions in
     * @param shardRouting The shard routing that is unassigned with a last status of DECIDERS_NO
     * @param state Current cluster state
     */
    private void explainAllocationsAndDiagnoseDeciders(List<UserAction.Definition> actions, ShardRouting shardRouting, ClusterState state) {
        LOGGER.trace("Diagnosing shard [{}]", shardRouting.shardId());
        RoutingAllocation allocation = new RoutingAllocation(
            allocationService.getAllocationDeciders(),
            state,
            ClusterInfo.EMPTY,
            SnapshotShardSizeInfo.EMPTY,
            System.nanoTime()
        );
        allocation.setDebugMode(RoutingAllocation.DebugMode.ON);
        ShardAllocationDecision shardAllocationDecision = allocationService.explainShardAllocation(shardRouting, allocation);
        AllocateUnassignedDecision allocateDecision = shardAllocationDecision.getAllocateDecision();
        LOGGER.trace(
            "[{}]: Obtained decision: [{}/{}]",
            shardRouting.shardId(),
            allocateDecision.isDecisionTaken(),
            allocateDecision.getAllocationDecision()
        );
        if (allocateDecision.isDecisionTaken() && AllocationDecision.NO == allocateDecision.getAllocationDecision()) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace(
                    "[{}]: Working with decisions: [{}]",
                    shardRouting.shardId(),
                    allocateDecision.getNodeDecisions()
                        .stream()
                        .map(
                            n -> n.getCanAllocateDecision()
                                .getDecisions()
                                .stream()
                                .map(d -> d.label() + ": " + d.type())
                                .collect(Collectors.toList())
                        )
                        .collect(Collectors.toList())
                );
            }
            List<NodeAllocationResult> nodeAllocationResults = allocateDecision.getNodeDecisions();
            diagnoseAllocationResults(actions, shardRouting, state, nodeAllocationResults);
        }
    }

    /**
     * Generates a list of user actions to take for an unassigned shard by inspecting a list of NodeAllocationResults for
     * well known problems.
     * @param actions A list to collect the user actions in.
     * @param shardRouting The unassigned shard.
     * @param state Current cluster state.
     * @param nodeAllocationResults A list of results for each node in the cluster from the allocation explain api
     */
    void diagnoseAllocationResults(
        List<UserAction.Definition> actions,
        ShardRouting shardRouting,
        ClusterState state,
        List<NodeAllocationResult> nodeAllocationResults
    ) {
        IndexMetadata index = state.metadata().index(shardRouting.index());
        if (index != null) {
            checkIsAllocationDisabled(actions, index, nodeAllocationResults);
            checkDataTierRelatedIssues(actions, index, nodeAllocationResults);
        }
        if (actions.isEmpty()) {
            actions.add(ACTION_CHECK_ALLOCATION_EXPLAIN_API);
        }
    }

    /**
     * Convenience method for filtering node allocation results by decider outcomes.
     * @param deciderName The decider that is being checked
     * @param outcome The outcome expected
     * @return A predicate that returns true if the decision exists and matches the expected outcome, false otherwise.
     */
    private static Predicate<NodeAllocationResult> hasDeciderResult(String deciderName, Decision.Type outcome) {
        return (nodeResult) -> nodeResult.getCanAllocateDecision()
            .getDecisions()
            .stream()
            .anyMatch(decision -> deciderName.equals(decision.label()) && outcome == decision.type());
    }

    /**
     * Generates a user action if a shard cannot be allocated anywhere because allocation is disabled for that shard
     * @param actions Any user actions generated from this method will be added to this list.
     * @param nodeAllocationResults allocation decision results for all nodes in the cluster.
     */
    void checkIsAllocationDisabled(
        List<UserAction.Definition> actions,
        IndexMetadata indexMetadata,
        List<NodeAllocationResult> nodeAllocationResults
    ) {
        if (nodeAllocationResults.stream().allMatch(hasDeciderResult(EnableAllocationDecider.NAME, Decision.Type.NO))) {
            // Check the routing settings for index
            Settings indexSettings = indexMetadata.getSettings();
            EnableAllocationDecider.Allocation indexLevelAllocation = INDEX_ROUTING_ALLOCATION_ENABLE_SETTING.get(indexSettings);
            ClusterSettings clusterSettings = clusterService.getClusterSettings();
            EnableAllocationDecider.Allocation clusterLevelAllocation = clusterSettings.get(
                EnableAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ENABLE_SETTING
            );
            if (EnableAllocationDecider.Allocation.ALL != indexLevelAllocation) {
                // Index setting is not ALL
                actions.add(ACTION_ENABLE_INDEX_ROUTING_ALLOCATION);
            }
            if (EnableAllocationDecider.Allocation.ALL != clusterLevelAllocation) {
                // Cluster setting is not ALL
                actions.add(ACTION_ENABLE_CLUSTER_ROUTING_ALLOCATION);
            }
        }
    }

    /**
     * Generates user actions for common problems that keep a shard from allocating to nodes in a data tier.
     * @param actions Any user actions generated from this method will be added to this list.
     * @param indexMetadata Index metadata for the shard being diagnosed.
     * @param nodeAllocationResults allocation decision results for all nodes in the cluster.
     */
    void checkDataTierRelatedIssues(
        List<UserAction.Definition> actions,
        IndexMetadata indexMetadata,
        List<NodeAllocationResult> nodeAllocationResults
    ) {
        if (indexMetadata.getTierPreference().size() > 0) {
            List<NodeAllocationResult> dataTierAllocationResults = nodeAllocationResults.stream()
                .filter(hasDeciderResult(DATA_TIER_ALLOCATION_DECIDER_NAME, Decision.Type.YES))
                .toList();
            if (dataTierAllocationResults.isEmpty()) {
                // Shard must be allocated on specific tiers but no nodes were enabled for those tiers.
                for (String tier : indexMetadata.getTierPreference()) {
                    Optional.ofNullable(ACTION_ENABLE_TIERS_LOOKUP.get(tier)).ifPresent(actions::add);
                }
            } else {
                // All tier nodes at shards limit?
                if (dataTierAllocationResults.stream().allMatch(hasDeciderResult(ShardsLimitAllocationDecider.NAME, Decision.Type.NO))) {
                    actions.add(ACTION_SHARD_LIMIT);
                }

                // All tier nodes conflict with allocation filters?
                if (dataTierAllocationResults.stream().allMatch(hasDeciderResult(FilterAllocationDecider.NAME, Decision.Type.NO))) {
                    actions.add(ACTION_MIGRATE_TIERS);
                }

                // Not enough tier nodes to hold shards on different nodes?
                if (dataTierAllocationResults.stream().allMatch(hasDeciderResult(SameShardAllocationDecider.NAME, Decision.Type.NO))) {
                    actions.add(ACTION_INCREASE_TIER_CAPACITY);
                }
            }
        }
    }

    private class ShardAllocationStatus {
        private final ShardAllocationCounts primaries = new ShardAllocationCounts();
        private final ShardAllocationCounts replicas = new ShardAllocationCounts();
        private final Metadata clusterMetadata;

        ShardAllocationStatus(Metadata clusterMetadata) {
            this.clusterMetadata = clusterMetadata;
        }

        public void addPrimary(ShardRouting routing, ClusterState state, NodesShutdownMetadata shutdowns, boolean includeDetails) {
            primaries.increment(routing, state, shutdowns, includeDetails);
        }

        public void addReplica(ShardRouting routing, ClusterState state, NodesShutdownMetadata shutdowns, boolean includeDetails) {
            replicas.increment(routing, state, shutdowns, includeDetails);
        }

        public HealthStatus getStatus() {
            if (primaries.available == false) {
                return RED;
            } else if (replicas.available == false) {
                return YELLOW;
            } else {
                return GREEN;
            }
        }

        public String getSummary() {
            var builder = new StringBuilder("This cluster has ");
            if (primaries.unassigned > 0
                || primaries.unassigned_new > 0
                || primaries.unassigned_restarting > 0
                || replicas.unassigned > 0
                || replicas.unassigned_restarting > 0) {
                builder.append(
                    Stream.of(
                        createMessage(primaries.unassigned, "unavailable primary", " unavailable primaries"),
                        createMessage(primaries.unassigned_new, "creating primary", " creating primaries"),
                        createMessage(primaries.unassigned_restarting, "restarting primary", " restarting primaries"),
                        createMessage(replicas.unassigned, "unavailable replica", "unavailable replicas"),
                        createMessage(replicas.unassigned_restarting, "restarting replica", "restarting replicas")
                    ).flatMap(Function.identity()).collect(joining(", "))
                ).append(".");
            } else {
                builder.append("all shards available.");
            }
            return builder.toString();
        }

        private static Stream<String> createMessage(int count, String singular, String plural) {
            return switch (count) {
                case 0 -> Stream.empty();
                case 1 -> Stream.of("1 " + singular);
                default -> Stream.of(count + " " + plural);
            };
        }

        public HealthIndicatorDetails getDetails(boolean includeDetails) {
            if (includeDetails) {
                return new SimpleHealthIndicatorDetails(
                    Map.of(
                        "unassigned_primaries",
                        primaries.unassigned,
                        "initializing_primaries",
                        primaries.initializing,
                        "creating_primaries",
                        primaries.unassigned_new,
                        "restarting_primaries",
                        primaries.unassigned_restarting,
                        "started_primaries",
                        primaries.started + primaries.relocating,
                        "unassigned_replicas",
                        replicas.unassigned,
                        "initializing_replicas",
                        replicas.initializing,
                        "restarting_replicas",
                        replicas.unassigned_restarting,
                        "started_replicas",
                        replicas.started + replicas.relocating
                    )
                );
            } else {
                return HealthIndicatorDetails.EMPTY;
            }
        }

        public List<HealthIndicatorImpact> getImpacts() {
            final List<HealthIndicatorImpact> impacts = new ArrayList<>();
            if (primaries.indicesWithUnavailableShards.isEmpty() == false) {
                String impactDescription = String.format(
                    Locale.ROOT,
                    "Cannot add data to %d %s [%s]. Searches might return incomplete results.",
                    primaries.indicesWithUnavailableShards.size(),
                    primaries.indicesWithUnavailableShards.size() == 1 ? "index" : "indices",
                    getTruncatedIndicesString(primaries.indicesWithUnavailableShards, clusterMetadata)
                );
                impacts.add(new HealthIndicatorImpact(1, impactDescription, List.of(ImpactArea.INGEST, ImpactArea.SEARCH)));
            }
            /*
             * It is possible that we're working with an intermediate cluster state, and that for an index we have no primary but a replica
             * that is reported as unavailable. That replica is likely being promoted to primary. The only impact that matters at this
             * point is the one above, which has already been reported for this index.
             */
            Set<String> indicesWithUnavailableReplicasOnly = new HashSet<>(replicas.indicesWithUnavailableShards);
            indicesWithUnavailableReplicasOnly.removeAll(primaries.indicesWithUnavailableShards);
            if (indicesWithUnavailableReplicasOnly.isEmpty() == false) {
                String impactDescription = String.format(
                    Locale.ROOT,
                    "Searches might return slower than usual. Fewer redundant copies of the data exist on %d %s [%s].",
                    indicesWithUnavailableReplicasOnly.size(),
                    indicesWithUnavailableReplicasOnly.size() == 1 ? "index" : "indices",
                    getTruncatedIndicesString(indicesWithUnavailableReplicasOnly, clusterMetadata)
                );
                impacts.add(new HealthIndicatorImpact(2, impactDescription, List.of(ImpactArea.SEARCH)));
            }
            return impacts;
        }

        /**
         * Summarizes the user actions that are needed to solve unassigned primary and replica shards.
         * @param includeDetails true if user actions should be generated, false if they should be omitted.
         * @return A summary of user actions. Alternatively, an empty list if none were found or includeDetails is false.
         */
        public List<UserAction> getUserActions(boolean includeDetails) {
            if (includeDetails) {
                Map<UserAction.Definition, Set<String>> actionsToAffectedIndices = new HashMap<>(primaries.userActions);
                replicas.userActions.forEach((actionDefinition, indicesWithReplicasUnassigned) -> {
                    Set<String> indicesWithPrimariesUnassigned = actionsToAffectedIndices.get(actionDefinition);
                    if (indicesWithPrimariesUnassigned == null) {
                        actionsToAffectedIndices.put(actionDefinition, indicesWithReplicasUnassigned);
                    } else {
                        indicesWithPrimariesUnassigned.addAll(indicesWithReplicasUnassigned);
                    }
                });
                if (actionsToAffectedIndices.isEmpty()) {
                    return Collections.emptyList();
                } else {
                    return actionsToAffectedIndices.entrySet()
                        .stream()
                        .map(
                            e -> new UserAction(
                                e.getKey(),
                                e.getValue().stream().sorted(byPriorityThenByName(clusterMetadata)).collect(Collectors.toList())
                            )
                        )
                        .collect(Collectors.toList());
                }
            } else {
                return Collections.emptyList();
            }
        }
    }

    private static String getTruncatedIndicesString(Set<String> indices, Metadata clusterMetadata) {
        final int maxIndices = 10;
        String truncatedIndicesString = indices.stream()
            .sorted(byPriorityThenByName(clusterMetadata))
            .limit(maxIndices)
            .collect(joining(", "));
        if (maxIndices < indices.size()) {
            truncatedIndicesString = truncatedIndicesString + ", ...";
        }
        return truncatedIndicesString;
    }

    /**
     * Sorts index names by their priority first, then alphabetically by name. If the priority cannot be determined for an index then
     * a priority of -1 is used to sort it behind other index names.
     * @param clusterMetadata Used to look up index priority.
     * @return Comparator instance
     */
    private static Comparator<String> byPriorityThenByName(Metadata clusterMetadata) {
        // We want to show indices with a numerically higher index.priority first (since lower priority ones might get truncated):
        return Comparator.comparingInt((String indexName) -> {
            IndexMetadata indexMetadata = clusterMetadata.index(indexName);
            return indexMetadata == null ? -1 : indexMetadata.priority();
        }).reversed().thenComparing(Comparator.naturalOrder());
    }
}
