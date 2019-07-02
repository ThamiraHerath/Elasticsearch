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
package org.elasticsearch.discovery;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.Coordinator;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RerouteService;
import org.elasticsearch.cluster.service.ClusterApplier;
import org.elasticsearch.cluster.service.MasterService;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.gateway.GatewayMetaState;
import org.elasticsearch.plugins.DiscoveryPlugin;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DiscoveryModuleTests extends ESTestCase {

    private TransportService transportService;
    private NamedWriteableRegistry namedWriteableRegistry;
    private MasterService masterService;
    private ClusterApplier clusterApplier;
    private ThreadPool threadPool;
    private ClusterSettings clusterSettings;
    private GatewayMetaState gatewayMetaState;

    public interface DummyHostsProviderPlugin extends DiscoveryPlugin {
        Map<String, Supplier<SeedHostsProvider>> impl();
        @Override
        default Map<String, Supplier<SeedHostsProvider>> getSeedHostProviders(TransportService transportService,
                                                                              NetworkService networkService) {
            return impl();
        }
    }

    @Before
    public void setupDummyServices() {
        threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        transportService = MockTransportService.createNewService(Settings.EMPTY, Version.CURRENT, threadPool, null);
        masterService = mock(MasterService.class);
        namedWriteableRegistry = new NamedWriteableRegistry(Collections.emptyList());
        clusterApplier = mock(ClusterApplier.class);
        clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        gatewayMetaState = mock(GatewayMetaState.class);
    }

    @After
    public void clearDummyServices() throws IOException {
        IOUtils.close(transportService);
    }

    private DiscoveryModule newModule(Settings settings, List<DiscoveryPlugin> plugins) {
        return new DiscoveryModule(settings, threadPool, transportService, namedWriteableRegistry, null, masterService,
            clusterApplier, clusterSettings, plugins, null, createTempDir().toAbsolutePath(), gatewayMetaState,
            mock(RerouteService.class));
    }

    public void testDefaults() {
        DiscoveryModule module = newModule(Settings.EMPTY, Collections.emptyList());
        assertTrue(module.getDiscovery() instanceof Coordinator);
    }

    public void testUnknownDiscovery() {
        Settings settings = Settings.builder().put(DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey(), "dne").build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            newModule(settings, Collections.emptyList()));
        assertEquals("Unknown discovery type [dne]", e.getMessage());
    }

    public void testSeedProviders() {
        Settings settings = Settings.builder().put(DiscoveryModule.DISCOVERY_SEED_PROVIDERS_SETTING.getKey(), "custom").build();
        AtomicBoolean created = new AtomicBoolean(false);
        DummyHostsProviderPlugin plugin = () -> Collections.singletonMap("custom", () -> {
            created.set(true);
            return hostsResolver -> Collections.emptyList();
        });
        newModule(settings, Collections.singletonList(plugin));
        assertTrue(created.get());
    }

    public void testUnknownSeedsProvider() {
        Settings settings = Settings.builder().put(DiscoveryModule.DISCOVERY_SEED_PROVIDERS_SETTING.getKey(), "dne").build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            newModule(settings, Collections.emptyList()));
        assertEquals("Unknown seed providers [dne]", e.getMessage());
    }

    public void testDuplicateSeedsProvider() {
        DummyHostsProviderPlugin plugin1 = () -> Collections.singletonMap("dup", () -> null);
        DummyHostsProviderPlugin plugin2 = () -> Collections.singletonMap("dup", () -> null);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            newModule(Settings.EMPTY, Arrays.asList(plugin1, plugin2)));
        assertEquals("Cannot register seed provider [dup] twice", e.getMessage());
    }

    public void testSettingsSeedsProvider() {
        DummyHostsProviderPlugin plugin = () -> Collections.singletonMap("settings", () -> null);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            newModule(Settings.EMPTY, Arrays.asList(plugin)));
        assertEquals("Cannot register seed provider [settings] twice", e.getMessage());
    }

    public void testMultipleSeedsProviders() {
        AtomicBoolean created1 = new AtomicBoolean(false);
        DummyHostsProviderPlugin plugin1 = () -> Collections.singletonMap("provider1", () -> {
            created1.set(true);
            return hostsResolver -> Collections.emptyList();
        });
        AtomicBoolean created2 = new AtomicBoolean(false);
        DummyHostsProviderPlugin plugin2 = () -> Collections.singletonMap("provider2", () -> {
            created2.set(true);
            return hostsResolver -> Collections.emptyList();
        });
        AtomicBoolean created3 = new AtomicBoolean(false);
        DummyHostsProviderPlugin plugin3 = () -> Collections.singletonMap("provider3", () -> {
            created3.set(true);
            return hostsResolver -> Collections.emptyList();
        });
        Settings settings = Settings.builder().putList(DiscoveryModule.DISCOVERY_SEED_PROVIDERS_SETTING.getKey(),
            "provider1", "provider3").build();
        newModule(settings, Arrays.asList(plugin1, plugin2, plugin3));
        assertTrue(created1.get());
        assertFalse(created2.get());
        assertTrue(created3.get());
    }

    public void testLazyConstructionSeedsProvider() {
        DummyHostsProviderPlugin plugin = () -> Collections.singletonMap("custom",
            () -> {
                throw new AssertionError("created hosts provider which was not selected");
            });
        newModule(Settings.EMPTY, Collections.singletonList(plugin));
    }

    public void testJoinValidator() {
        BiConsumer<DiscoveryNode, ClusterState> consumer = (a, b) -> {};
        DiscoveryModule module = newModule(Settings.builder().put(DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey(),
            DiscoveryModule.ZEN2_DISCOVERY_TYPE).build(), Collections.singletonList(new DiscoveryPlugin() {
            @Override
            public BiConsumer<DiscoveryNode, ClusterState> getJoinValidator() {
                return consumer;
            }
        }));
        Coordinator discovery = (Coordinator) module.getDiscovery();
        Collection<BiConsumer<DiscoveryNode, ClusterState>> onJoinValidators = discovery.getOnJoinValidators();
        assertEquals(2, onJoinValidators.size());
        assertTrue(onJoinValidators.contains(consumer));
    }
}
