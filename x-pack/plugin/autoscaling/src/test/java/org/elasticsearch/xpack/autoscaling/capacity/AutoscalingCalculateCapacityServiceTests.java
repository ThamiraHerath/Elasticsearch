/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.autoscaling.capacity;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.DiskUsage;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.xpack.autoscaling.AutoscalingMetadata;
import org.elasticsearch.xpack.autoscaling.AutoscalingTestCase;
import org.elasticsearch.xpack.autoscaling.policy.AutoscalingPolicy;
import org.elasticsearch.xpack.autoscaling.policy.AutoscalingPolicyMetadata;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.hamcrest.Matchers.equalTo;

public class AutoscalingCalculateCapacityServiceTests extends AutoscalingTestCase {
    public void testMultiplePoliciesFixedCapacity() {
        AutoscalingCalculateCapacityService service = new AutoscalingCalculateCapacityService(Set.of(new FixedAutoscalingDeciderService()));
        Set<String> policyNames = IntStream.range(0, randomIntBetween(1, 10))
            .mapToObj(i -> "test_ " + randomAlphaOfLength(10))
            .collect(Collectors.toSet());

        SortedMap<String, AutoscalingPolicyMetadata> policies = new TreeMap<>(
            policyNames.stream()
                .map(s -> Tuple.tuple(s, new AutoscalingPolicyMetadata(new AutoscalingPolicy(s, randomRoles(), randomFixedDeciders()))))
                .collect(Collectors.toMap(Tuple::v1, Tuple::v2))
        );
        ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(Metadata.builder().putCustom(AutoscalingMetadata.NAME, new AutoscalingMetadata(policies)))
            .build();
        SortedMap<String, AutoscalingDeciderResults> resultsMap = service.calculate(state, new ClusterInfo() {
        });
        assertThat(resultsMap.keySet(), equalTo(policyNames));
        for (Map.Entry<String, AutoscalingDeciderResults> entry : resultsMap.entrySet()) {
            AutoscalingDeciderResults results = entry.getValue();
            SortedMap<String, AutoscalingDeciderConfiguration> deciders = policies.get(entry.getKey()).policy().deciders();
            assertThat(deciders.size(), equalTo(1));
            FixedAutoscalingDeciderConfiguration configuration = (FixedAutoscalingDeciderConfiguration) deciders.values().iterator().next();
            AutoscalingCapacity requiredCapacity = calculateFixedDeciderCapacity(configuration);
            assertThat(results.requiredCapacity(), equalTo(requiredCapacity));
            assertThat(results.results().size(), equalTo(1));
            AutoscalingDeciderResult deciderResult = results.results().get(deciders.firstKey());
            assertNotNull(deciderResult);
            assertThat(deciderResult.requiredCapacity(), equalTo(requiredCapacity));
            ByteSizeValue storage = configuration.storage();
            ByteSizeValue memory = configuration.memory();
            int nodes = configuration.nodes();
            assertThat(deciderResult.reason(), equalTo(new FixedAutoscalingDeciderService.FixedReason(storage, memory, nodes)));
            assertThat(
                deciderResult.reason().summary(),
                equalTo("fixed storage [" + storage + "] memory [" + memory + "] nodes [" + nodes + "]")
            );

            // there is no nodes in any tier.
            assertThat(results.currentCapacity(), equalTo(AutoscalingCapacity.ZERO));
        }
    }

    private SortedMap<String, AutoscalingDeciderConfiguration> randomFixedDeciders() {
        return new TreeMap<>(
            Map.of(
                FixedAutoscalingDeciderConfiguration.NAME,
                new FixedAutoscalingDeciderConfiguration(
                    randomNullableByteSizeValue(),
                    randomNullableByteSizeValue(),
                    randomIntBetween(1, 10)
                )
            )
        );
    }

    private AutoscalingCapacity calculateFixedDeciderCapacity(FixedAutoscalingDeciderConfiguration configuration) {
        ByteSizeValue totalStorage = configuration.storage() != null
            ? new ByteSizeValue(configuration.storage().getBytes() * configuration.nodes())
            : null;
        ByteSizeValue totalMemory = configuration.memory() != null
            ? new ByteSizeValue(configuration.memory().getBytes() * configuration.nodes())
            : null;

        if (totalStorage == null && totalMemory == null) {
            return null;
        } else {
            return new AutoscalingCapacity(
                new AutoscalingCapacity.AutoscalingResources(totalStorage, totalMemory),
                new AutoscalingCapacity.AutoscalingResources(configuration.storage(), configuration.memory())
            );
        }
    }

    public void testContext() {
        ClusterState state = ClusterState.builder(ClusterName.DEFAULT).build();
        ClusterInfo info = ClusterInfo.EMPTY;
        SortedSet<String> roleNames = randomRoles();
        AutoscalingCalculateCapacityService.DefaultAutoscalingDeciderContext context =
            new AutoscalingCalculateCapacityService.DefaultAutoscalingDeciderContext(roleNames, state, info);

        assertSame(state, context.state());

        assertThat(context.nodes(), equalTo(Set.of()));
        assertThat(context.currentCapacity(), equalTo(AutoscalingCapacity.ZERO));

        Set<DiscoveryNodeRole> roles = roleNames.stream().map(DiscoveryNode::getRoleFromRoleName).collect(Collectors.toSet());
        Set<DiscoveryNodeRole> otherRoles = mutateRoles(roleNames).stream()
            .map(DiscoveryNode::getRoleFromRoleName)
            .collect(Collectors.toSet());
        state = ClusterState.builder(ClusterName.DEFAULT)
            .nodes(
                DiscoveryNodes.builder()
                    .add(new DiscoveryNode("nodeId", buildNewFakeTransportAddress(), Map.of(), roles, Version.CURRENT))
            )
            .build();
        context = new AutoscalingCalculateCapacityService.DefaultAutoscalingDeciderContext(roleNames, state, info);

        assertThat(context.nodes().size(), equalTo(1));
        assertThat(context.nodes(), equalTo(StreamSupport.stream(state.nodes().spliterator(), false).collect(Collectors.toSet())));
        assertNull(context.currentCapacity());

        ImmutableOpenMap.Builder<String, DiskUsage> leastUsages = ImmutableOpenMap.<String, DiskUsage>builder();
        ImmutableOpenMap.Builder<String, DiskUsage> mostUsages = ImmutableOpenMap.<String, DiskUsage>builder();
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder();
        Set<DiscoveryNode> expectedNodes = new HashSet<>();
        long sumTotal = 0;
        long maxTotal = 0;
        for (int i = 0; i < randomIntBetween(1, 5); ++i) {
            String nodeId = "nodeId" + i;
            boolean useOtherRoles = randomBoolean();
            DiscoveryNode node = new DiscoveryNode(
                nodeId,
                buildNewFakeTransportAddress(),
                Map.of(),
                useOtherRoles ? otherRoles : roles,
                Version.CURRENT
            );
            nodes.add(node);

            long total = randomLongBetween(1, 1L << 40);
            long total1 = randomBoolean() ? total : randomLongBetween(0, total);
            long total2 = total1 != total ? total : randomLongBetween(0, total);
            leastUsages.fPut(nodeId, new DiskUsage(nodeId, null, null, total1, randomLongBetween(0, total)));
            mostUsages.fPut(nodeId, new DiskUsage(nodeId, null, null, total2, randomLongBetween(0, total)));
            if (useOtherRoles == false) {
                sumTotal += total;
                maxTotal = Math.max(total, maxTotal);
                expectedNodes.add(node);
            }
        }
        state = ClusterState.builder(ClusterName.DEFAULT).nodes(nodes).build();
        info = new ClusterInfo(leastUsages.build(), mostUsages.build(), null, null, null);
        context = new AutoscalingCalculateCapacityService.DefaultAutoscalingDeciderContext(roleNames, state, info);

        assertThat(context.nodes(), equalTo(expectedNodes));
        AutoscalingCapacity capacity = context.currentCapacity();
        assertThat(capacity.node().storage(), equalTo(new ByteSizeValue(maxTotal)));
        assertThat(capacity.tier().storage(), equalTo(new ByteSizeValue(sumTotal)));
        // todo: fix these once we know memory of all nodes on master.
        assertThat(capacity.node().memory(), equalTo(ByteSizeValue.ZERO));
        assertThat(capacity.tier().memory(), equalTo(ByteSizeValue.ZERO));
    }
}
