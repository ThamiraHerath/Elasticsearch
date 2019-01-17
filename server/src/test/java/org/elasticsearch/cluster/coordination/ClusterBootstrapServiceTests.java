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
package org.elasticsearch.cluster.coordination;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNode.Role;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.MockTransport;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportService;
import org.junit.Before;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.cluster.coordination.ClusterBootstrapService.INITIAL_MASTER_NODES_SETTING;
import static org.elasticsearch.cluster.coordination.ClusterBootstrapService.UNCONFIGURED_BOOTSTRAP_TIMEOUT_SETTING;
import static org.elasticsearch.common.settings.Settings.builder;
import static org.elasticsearch.discovery.DiscoveryModule.DISCOVERY_HOSTS_PROVIDER_SETTING;
import static org.elasticsearch.discovery.zen.SettingsBasedHostsProvider.DISCOVERY_ZEN_PING_UNICAST_HOSTS_SETTING;
import static org.elasticsearch.node.Node.NODE_NAME_SETTING;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

public class ClusterBootstrapServiceTests extends ESTestCase {

    private DiscoveryNode localNode, otherNode1, otherNode2;
    private DeterministicTaskQueue deterministicTaskQueue;
    private TransportService transportService;

    @Before
    public void createServices() {
        localNode = newDiscoveryNode("local");
        otherNode1 = newDiscoveryNode("other1");
        otherNode2 = newDiscoveryNode("other2");

        deterministicTaskQueue = new DeterministicTaskQueue(builder().put(NODE_NAME_SETTING.getKey(), "node").build(), random());

        final MockTransport transport = new MockTransport() {
            @Override
            protected void onSendRequest(long requestId, String action, TransportRequest request, DiscoveryNode node) {
                throw new AssertionError("unexpected " + action);
            }
        };

        transportService = transport.createTransportService(Settings.EMPTY, deterministicTaskQueue.getThreadPool(),
            TransportService.NOOP_TRANSPORT_INTERCEPTOR, boundTransportAddress -> localNode, null, emptySet());
    }

    private DiscoveryNode newDiscoveryNode(String nodeName) {
        return new DiscoveryNode(nodeName, randomAlphaOfLength(10), buildNewFakeTransportAddress(), emptyMap(), singleton(Role.MASTER),
            Version.CURRENT);
    }

    public void testBootstrapsAutomaticallyWithDefaultConfiguration() {
        final Settings.Builder settings = Settings.builder();
        final long timeout;
        if (randomBoolean()) {
            timeout = UNCONFIGURED_BOOTSTRAP_TIMEOUT_SETTING.get(Settings.EMPTY).millis();
        } else {
            timeout = randomLongBetween(1, 10000);
            settings.put(UNCONFIGURED_BOOTSTRAP_TIMEOUT_SETTING.getKey(), timeout + "ms");
        }

        final AtomicReference<Supplier<Iterable<DiscoveryNode>>> discoveredNodesSupplier = new AtomicReference<>(() -> {
            throw new AssertionError("should not be called yet");
        });

        final AtomicBoolean bootstrapped = new AtomicBoolean();
        ClusterBootstrapService clusterBootstrapService
            = new ClusterBootstrapService(settings.build(), transportService, () -> discoveredNodesSupplier.get().get(), vc -> {
            assertTrue(bootstrapped.compareAndSet(false, true));
            assertThat(vc.getNodeIds(),
                equalTo(Stream.of(localNode, otherNode1, otherNode2).map(DiscoveryNode::getId).collect(Collectors.toSet())));
            assertThat(deterministicTaskQueue.getCurrentTimeMillis(), greaterThanOrEqualTo(timeout));
        });

        deterministicTaskQueue.scheduleAt(timeout - 1,
            () -> discoveredNodesSupplier.set(() -> Stream.of(localNode, otherNode1, otherNode2).collect(Collectors.toSet())));

        transportService.start();
        clusterBootstrapService.scheduleUnconfiguredBootstrap();
        deterministicTaskQueue.runAllTasksInTimeOrder();
        assertTrue(bootstrapped.get());
    }

    public void testDoesNothingByDefaultIfHostsProviderConfigured() {
        testDoesNothingWithSettings(builder().putList(DISCOVERY_HOSTS_PROVIDER_SETTING.getKey()));
    }

    public void testDoesNothingByDefaultIfUnicastHostsConfigured() {
        testDoesNothingWithSettings(builder().putList(DISCOVERY_ZEN_PING_UNICAST_HOSTS_SETTING.getKey()));
    }

    public void testDoesNothingByDefaultIfMasterNodesConfigured() {
        testDoesNothingWithSettings(builder().putList(INITIAL_MASTER_NODES_SETTING.getKey()));
    }

    public void testDoesNothingByDefaultOnMasterIneligibleNodes() {
        localNode = new DiscoveryNode("local", randomAlphaOfLength(10), buildNewFakeTransportAddress(), emptyMap(), emptySet(),
            Version.CURRENT);
        testDoesNothingWithSettings(Settings.builder());
    }

    private void testDoesNothingWithSettings(Settings.Builder builder) {
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(builder.build(), transportService, () -> {
            throw new AssertionError("should not be called");
        }, vc -> {
            throw new AssertionError("should not be called");
        });
        transportService.start();
        clusterBootstrapService.scheduleUnconfiguredBootstrap();
        deterministicTaskQueue.runAllTasks();
    }

    public void testDoesNothingByDefaultIfZen1NodesDiscovered() {
        final DiscoveryNode zen1Node = new DiscoveryNode("zen1", buildNewFakeTransportAddress(), singletonMap("zen1", "true"),
            singleton(Role.MASTER), Version.CURRENT);
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(Settings.EMPTY, transportService, () ->
            Stream.of(localNode, zen1Node).collect(Collectors.toSet()), vc -> {
            throw new AssertionError("should not be called");
        });
        transportService.start();
        clusterBootstrapService.scheduleUnconfiguredBootstrap();
        deterministicTaskQueue.runAllTasks();
    }

    public void testBootstrapsOnDiscoverySuccess() {
        final AtomicBoolean bootstrapped = new AtomicBoolean();

        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(Settings.builder().putList(
            INITIAL_MASTER_NODES_SETTING.getKey(), localNode.getName(), otherNode1.getName(), otherNode2.getName()).build(),
            transportService, () -> Stream.of(otherNode1, otherNode2).collect(Collectors.toList()), vc -> {
            assertTrue(bootstrapped.compareAndSet(false, true));
            assertThat(vc.getNodeIds(), hasSize(3));
        });

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
        assertTrue(bootstrapped.get());

        bootstrapped.set(false);
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
        assertFalse(bootstrapped.get()); // should only bootstrap once
    }

    public void testDoesNotBootstrapsOnNonMasterNode() {
        localNode = new DiscoveryNode("local", randomAlphaOfLength(10), buildNewFakeTransportAddress(), emptyMap(), emptySet(),
            Version.CURRENT);
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(Settings.builder().putList(
            INITIAL_MASTER_NODES_SETTING.getKey(), localNode.getName(), otherNode1.getName(), otherNode2.getName()).build(),
            transportService, () ->
            Stream.of(localNode, otherNode1, otherNode2).collect(Collectors.toList()), vc -> {
            throw new AssertionError("should not be called");
        });
        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
    }

    public void testDoesNotBootstrapsIfNotConfigured() {
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(
            Settings.builder().putList(INITIAL_MASTER_NODES_SETTING.getKey()).build(), transportService,
            () -> Stream.of(localNode, otherNode1, otherNode2).collect(Collectors.toList()), vc -> {
            throw new AssertionError("should not be called");
        });
        transportService.start();
        clusterBootstrapService.scheduleUnconfiguredBootstrap();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
    }

    public void testDoesNotBootstrapsIfZen1NodesDiscovered() {
        final DiscoveryNode zen1Node = new DiscoveryNode("zen1", buildNewFakeTransportAddress(), singletonMap("zen1", "true"),
            singleton(Role.MASTER), Version.CURRENT);

        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(Settings.builder().putList(
            INITIAL_MASTER_NODES_SETTING.getKey(), localNode.getName(), otherNode1.getName(), otherNode2.getName()).build(),
            transportService, () -> Stream.of(otherNode1, otherNode2, zen1Node).collect(Collectors.toList()), vc -> {
            throw new AssertionError("should not be called");
        });

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
    }

    public void testDoesNotRetryBootstrappingOnException() {
        final AtomicBoolean bootstrappingAttempted = new AtomicBoolean();
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(Settings.builder().putList(
            INITIAL_MASTER_NODES_SETTING.getKey(), localNode.getName(), otherNode1.getName(), otherNode2.getName()).build(),
            transportService, () -> Stream.of(otherNode1, otherNode2).collect(Collectors.toList()), vc -> {
            assertTrue(bootstrappingAttempted.compareAndSet(false, true));
        });

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
        assertTrue(bootstrappingAttempted.get());
    }

    public void testDoesNotBootstrapIfRequirementNotMet() {
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(Settings.builder().putList(
            INITIAL_MASTER_NODES_SETTING.getKey(), localNode.getName(), otherNode1.getName(), otherNode2.getName(), "not-a-node").build(),
            transportService, () -> Stream.of(otherNode1, otherNode2).collect(Collectors.toList()), vc -> {
            throw new AssertionError("should not be called");
        });

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
    }

    public void testCancelsBootstrapIfRequirementMatchesMultipleNodes() {
        AtomicReference<Iterable<DiscoveryNode>> discoveredNodes
            = new AtomicReference<>(Stream.of(otherNode1, otherNode2).collect(Collectors.toList()));
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(
            Settings.builder().putList(INITIAL_MASTER_NODES_SETTING.getKey(), localNode.getAddress().getAddress()).build(),
            transportService, discoveredNodes::get, vc -> {
            throw new AssertionError("should not be called");
        });

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();

        discoveredNodes.set(emptyList());
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
    }

    public void testCancelsBootstrapIfNodeMatchesMultipleRequirements() {
        AtomicReference<Iterable<DiscoveryNode>> discoveredNodes
            = new AtomicReference<>(Stream.of(otherNode1, otherNode2).collect(Collectors.toList()));
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(
            Settings.builder().putList(INITIAL_MASTER_NODES_SETTING.getKey(), otherNode1.getAddress().toString(), otherNode1.getName())
                .build(),
            transportService, discoveredNodes::get, vc -> {
            throw new AssertionError("should not be called");
        });

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();

        discoveredNodes.set(Stream.of(new DiscoveryNode(otherNode1.getName(), randomAlphaOfLength(10), buildNewFakeTransportAddress(),
                emptyMap(), singleton(Role.MASTER), Version.CURRENT),
            new DiscoveryNode("yet-another-node", randomAlphaOfLength(10), otherNode1.getAddress(), emptyMap(), singleton(Role.MASTER),
                Version.CURRENT)).collect(Collectors.toList()));

        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
    }

    public void testMatchesOnNodeName() {
        final AtomicBoolean bootstrapped = new AtomicBoolean();
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(
            Settings.builder().putList(INITIAL_MASTER_NODES_SETTING.getKey(), localNode.getName()).build(), transportService,
            Collections::emptyList, vc -> assertTrue(bootstrapped.compareAndSet(false, true)));

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
        assertTrue(bootstrapped.get());
    }

    public void testMatchesOnNodeAddress() {
        final AtomicBoolean bootstrapped = new AtomicBoolean();
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(
            Settings.builder().putList(INITIAL_MASTER_NODES_SETTING.getKey(), localNode.getAddress().toString()).build(), transportService,
            Collections::emptyList, vc -> assertTrue(bootstrapped.compareAndSet(false, true)));

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
        assertTrue(bootstrapped.get());
    }

    public void testMatchesOnNodeHostAddress() {
        final AtomicBoolean bootstrapped = new AtomicBoolean();
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(
            Settings.builder().putList(INITIAL_MASTER_NODES_SETTING.getKey(), localNode.getAddress().getAddress()).build(),
            transportService, Collections::emptyList, vc -> assertTrue(bootstrapped.compareAndSet(false, true)));

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
        assertTrue(bootstrapped.get());
    }

    public void testDoesNotJustMatchEverything() {
        ClusterBootstrapService clusterBootstrapService = new ClusterBootstrapService(
            Settings.builder().putList(INITIAL_MASTER_NODES_SETTING.getKey(), randomAlphaOfLength(10)).build(), transportService,
            Collections::emptyList, vc -> {
            throw new AssertionError("should not be called");
        });

        transportService.start();
        clusterBootstrapService.onFoundPeersUpdated();
        deterministicTaskQueue.runAllTasks();
    }
}
