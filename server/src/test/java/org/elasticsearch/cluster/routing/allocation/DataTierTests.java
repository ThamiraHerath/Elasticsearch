/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.allocation.DataTier.DataTierSettingValidator;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.NodeRoleSettings;
import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.routing.allocation.DataTier.ALL_DATA_TIERS;
import static org.elasticsearch.cluster.routing.allocation.DataTier.DATA_COLD;
import static org.elasticsearch.cluster.routing.allocation.DataTier.DATA_HOT;
import static org.elasticsearch.cluster.routing.allocation.DataTier.DATA_WARM;
import static org.elasticsearch.cluster.routing.allocation.DataTier.getPreferredTiersConfiguration;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

public class DataTierTests extends ESTestCase {

    private static final AtomicInteger idGenerator = new AtomicInteger();

    public void testNodeSelection() {
        DiscoveryNodes discoveryNodes = buildDiscoveryNodes();

        final String[] dataNodes =
            discoveryNodes.getNodes().values().stream()
                .filter(DiscoveryNode::canContainData)
                .map(DiscoveryNode::getId)
                .toArray(String[]::new);

        final String[] contentNodes =
            discoveryNodes.getNodes().values().stream()
                .filter(DataTier::isContentNode)
                .map(DiscoveryNode::getId)
                .toArray(String[]::new);

        final String[] hotNodes =
            discoveryNodes.getNodes().values().stream()
                .filter(DataTier::isHotNode)
                .map(DiscoveryNode::getId)
                .toArray(String[]::new);

        final String[] warmNodes =
            discoveryNodes.getNodes().values().stream()
                .filter(DataTier::isWarmNode)
                .map(DiscoveryNode::getId)
                .toArray(String[]::new);

        final String[] coldNodes =
            discoveryNodes.getNodes().values().stream()
                .filter(DataTier::isColdNode)
                .map(DiscoveryNode::getId)
                .toArray(String[]::new);

        final String[] frozenNodes =
            discoveryNodes.getNodes().values().stream()
                .filter(DataTier::isFrozenNode)
                .map(DiscoveryNode::getId)
                .toArray(String[]::new);

        assertThat(discoveryNodes.resolveNodes("data:true"), arrayContainingInAnyOrder(dataNodes));
        assertThat(discoveryNodes.resolveNodes("data_content:true"), arrayContainingInAnyOrder(contentNodes));
        assertThat(discoveryNodes.resolveNodes("data_hot:true"), arrayContainingInAnyOrder(hotNodes));
        assertThat(discoveryNodes.resolveNodes("data_warm:true"), arrayContainingInAnyOrder(warmNodes));
        assertThat(discoveryNodes.resolveNodes("data_cold:true"), arrayContainingInAnyOrder(coldNodes));
        assertThat(discoveryNodes.resolveNodes("data_frozen:true"), arrayContainingInAnyOrder(frozenNodes));
        Set<String> allTiers = new HashSet<>(Arrays.asList(contentNodes));
        allTiers.addAll(Arrays.asList(hotNodes));
        allTiers.addAll(Arrays.asList(warmNodes));
        allTiers.addAll(Arrays.asList(coldNodes));
        allTiers.addAll(Arrays.asList(frozenNodes));
        assertThat(discoveryNodes.resolveNodes("data:true"), arrayContainingInAnyOrder(allTiers.toArray(Strings.EMPTY_ARRAY)));
    }

    public void testDefaultRolesImpliesTieredDataRoles() {
        final DiscoveryNode node = DiscoveryNode.createLocal(Settings.EMPTY, buildNewFakeTransportAddress(), randomAlphaOfLength(8));
        assertThat(node.getRoles(), hasItem(DiscoveryNodeRole.DATA_CONTENT_NODE_ROLE));
        assertThat(node.getRoles(), hasItem(DiscoveryNodeRole.DATA_HOT_NODE_ROLE));
        assertThat(node.getRoles(), hasItem(DiscoveryNodeRole.DATA_WARM_NODE_ROLE));
        assertThat(node.getRoles(), hasItem(DiscoveryNodeRole.DATA_COLD_NODE_ROLE));
    }

    public void testDataRoleDoesNotImplyTieredDataRoles() {
        final Settings settings = Settings.builder().put(NodeRoleSettings.NODE_ROLES_SETTING.getKey(), "data").build();
        final DiscoveryNode node = DiscoveryNode.createLocal(settings, buildNewFakeTransportAddress(), randomAlphaOfLength(8));
        assertThat(node.getRoles(), not(hasItem(DiscoveryNodeRole.DATA_CONTENT_NODE_ROLE)));
        assertThat(node.getRoles(), not(hasItem(DiscoveryNodeRole.DATA_HOT_NODE_ROLE)));
        assertThat(node.getRoles(), not(hasItem(DiscoveryNodeRole.DATA_WARM_NODE_ROLE)));
        assertThat(node.getRoles(), not(hasItem(DiscoveryNodeRole.DATA_COLD_NODE_ROLE)));
    }

    public void testLegacyDataRoleImpliesTieredDataRoles() {
        final Settings settings = Settings.builder().put(DiscoveryNodeRole.DATA_ROLE.legacySetting().getKey(), true).build();
        final DiscoveryNode node = DiscoveryNode.createLocal(settings, buildNewFakeTransportAddress(), randomAlphaOfLength(8));
        assertThat(node.getRoles(), hasItem(DiscoveryNodeRole.DATA_CONTENT_NODE_ROLE));
        assertThat(node.getRoles(), hasItem(DiscoveryNodeRole.DATA_HOT_NODE_ROLE));
        assertThat(node.getRoles(), hasItem(DiscoveryNodeRole.DATA_WARM_NODE_ROLE));
        assertThat(node.getRoles(), hasItem(DiscoveryNodeRole.DATA_COLD_NODE_ROLE));
        assertSettingDeprecationsAndWarnings(new Setting<?>[]{DiscoveryNodeRole.DATA_ROLE.legacySetting()});
    }

    public void testDisablingLegacyDataRoleDisablesTieredDataRoles() {
        final Settings settings = Settings.builder().put(DiscoveryNodeRole.DATA_ROLE.legacySetting().getKey(), false).build();
        final DiscoveryNode node = DiscoveryNode.createLocal(settings, buildNewFakeTransportAddress(), randomAlphaOfLength(8));
        assertThat(node.getRoles(), not(hasItem(DiscoveryNodeRole.DATA_CONTENT_NODE_ROLE)));
        assertThat(node.getRoles(), not(hasItem(DiscoveryNodeRole.DATA_HOT_NODE_ROLE)));
        assertThat(node.getRoles(), not(hasItem(DiscoveryNodeRole.DATA_WARM_NODE_ROLE)));
        assertThat(node.getRoles(), not(hasItem(DiscoveryNodeRole.DATA_COLD_NODE_ROLE)));
        assertSettingDeprecationsAndWarnings(new Setting<?>[]{DiscoveryNodeRole.DATA_ROLE.legacySetting()});
    }

    public void testGetPreferredTiersConfiguration() {
        assertThat(getPreferredTiersConfiguration(DATA_HOT), is(DATA_HOT));
        assertThat(getPreferredTiersConfiguration(DATA_WARM), is(DATA_WARM + "," + DATA_HOT));
        assertThat(getPreferredTiersConfiguration(DATA_COLD), is(DATA_COLD + "," + DATA_WARM + "," + DATA_HOT));
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class, () -> getPreferredTiersConfiguration("no_tier"));
        assertThat(exception.getMessage(), is("invalid data tier [no_tier]"));
    }

    public void testDataNodesWithoutAllDataRoles() {
        ClusterState clusterState = clusterStateWithoutAllDataRoles();
        Set<DiscoveryNode> nodes = DataTier.dataNodesWithoutAllDataRoles(clusterState);
        assertThat(nodes, hasSize(2));
        assertThat(nodes, hasItem(both(hasProperty("name", is("name_3")))
            .and(hasProperty("roles", contains(DiscoveryNodeRole.DATA_FROZEN_NODE_ROLE)))));
        assertThat(nodes, hasItem(hasProperty("name", is("name_4"))));
    }

    public static ClusterState clusterStateWithoutAllDataRoles() {
        Set<DiscoveryNodeRole> allDataRoles = new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES).stream()
            .filter(role -> ALL_DATA_TIERS.contains(role.roleName())).collect(Collectors.toSet());

        Collection<String> allButOneDataTiers = randomValueOtherThan(ALL_DATA_TIERS,
            () -> randomSubsetOf(randomIntBetween(1, ALL_DATA_TIERS.size() - 1), ALL_DATA_TIERS));
        Set<DiscoveryNodeRole> allButOneDataRoles = new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES).stream()
            .filter(role -> allButOneDataTiers.contains(role.roleName())).collect(Collectors.toSet());

        DiscoveryNodes.Builder discoBuilder = DiscoveryNodes.builder();
        List<DiscoveryNode> nodesList = org.elasticsearch.core.List.of(
            newNode(0, org.elasticsearch.core.Map.of(), org.elasticsearch.core.Set.of(DiscoveryNodeRole.MASTER_ROLE)),
            newNode(1, org.elasticsearch.core.Map.of(), org.elasticsearch.core.Set.of(DiscoveryNodeRole.DATA_ROLE)),
            newNode(2, org.elasticsearch.core.Map.of(), allDataRoles),
            newNode(3, org.elasticsearch.core.Map.of(), org.elasticsearch.core.Set.of(DiscoveryNodeRole.DATA_FROZEN_NODE_ROLE)),
            newNode(4, org.elasticsearch.core.Map.of(), allButOneDataRoles)
        );
        for (DiscoveryNode node : nodesList) {
            discoBuilder = discoBuilder.add(node);
        }
        discoBuilder.localNodeId(randomFrom(nodesList).getId());
        discoBuilder.masterNodeId(randomFrom(nodesList).getId());

        return ClusterState.builder(ClusterState.EMPTY_STATE).nodes(discoBuilder.build()).build();
    }

    private static DiscoveryNodes buildDiscoveryNodes() {
        int numNodes = randomIntBetween(3, 15);
        DiscoveryNodes.Builder discoBuilder = DiscoveryNodes.builder();
        List<DiscoveryNode> nodesList = randomNodes(numNodes);
        for (DiscoveryNode node : nodesList) {
            discoBuilder = discoBuilder.add(node);
        }
        discoBuilder.localNodeId(randomFrom(nodesList).getId());
        discoBuilder.masterNodeId(randomFrom(nodesList).getId());
        return discoBuilder.build();
    }

    private static DiscoveryNode newNode(int nodeId, Map<String, String> attributes, Set<DiscoveryNodeRole> roles) {
        return new DiscoveryNode("name_" + nodeId, "node_" + nodeId, buildNewFakeTransportAddress(), attributes, roles,
            Version.CURRENT);
    }

    private static List<DiscoveryNode> randomNodes(final int numNodes) {
        Set<DiscoveryNodeRole> allRoles = new HashSet<>(DiscoveryNodeRole.BUILT_IN_ROLES);
        allRoles.remove(DiscoveryNodeRole.DATA_ROLE);
        List<DiscoveryNode> nodesList = new ArrayList<>();
        for (int i = 0; i < numNodes; i++) {
            Map<String, String> attributes = new HashMap<>();
            if (frequently()) {
                attributes.put("custom", randomBoolean() ? "match" : randomAlphaOfLengthBetween(3, 5));
            }
            final Set<DiscoveryNodeRole> roles = new HashSet<>(randomSubsetOf(allRoles));
            if (frequently()) {
                roles.add(new DiscoveryNodeRole("custom_role", "cr") {

                    @Override
                    public Setting<Boolean> legacySetting() {
                        return null;
                    }

                });
            }
            final DiscoveryNode node = newNode(idGenerator.getAndIncrement(), attributes, roles);
            nodesList.add(node);
        }
        return nodesList;
    }

    public void testDataTierSettingValidator() {
        DataTierSettingValidator validator = new DataTierSettingValidator();

        // good values
        validator.validate(null);
        validator.validate("");
        validator.validate(" "); // a little surprising
        validator.validate(DATA_WARM);
        validator.validate(DATA_WARM + "," + DATA_HOT);
        validator.validate(DATA_WARM + ","); // a little surprising

        // bad values
        expectThrows(IllegalArgumentException.class, () -> validator.validate(" " + DATA_WARM));
        expectThrows(IllegalArgumentException.class, () -> validator.validate(DATA_WARM + " "));
        expectThrows(IllegalArgumentException.class, () -> validator.validate(DATA_WARM + ", "));
        expectThrows(IllegalArgumentException.class, () -> validator.validate(DATA_WARM + ", " + DATA_HOT));
    }
}
