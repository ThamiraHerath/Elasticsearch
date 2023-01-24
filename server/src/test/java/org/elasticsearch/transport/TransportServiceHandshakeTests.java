/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.transport;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.Version;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.netty4.Netty4Transport;
import org.elasticsearch.transport.netty4.SharedGroupFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.elasticsearch.transport.AbstractSimpleTransportTestCase.IGNORE_DESERIALIZATION_ERRORS_SETTING;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;

public class TransportServiceHandshakeTests extends ESTestCase {

    private static ThreadPool threadPool;
    private static final TimeValue timeout = TimeValue.MAX_VALUE;

    @BeforeClass
    public static void startThreadPool() {
        threadPool = new TestThreadPool(TransportServiceHandshakeTests.class.getSimpleName());
    }

    private final List<TransportService> transportServices = new ArrayList<>();

    private TransportService startServices(
        String nodeNameAndId,
        Settings settings,
        Version version,
        TransportInterceptor transportInterceptor
    ) {
        TcpTransport transport = new Netty4Transport(
            settings,
            TransportVersion.CURRENT,
            threadPool,
            new NetworkService(Collections.emptyList()),
            PageCacheRecycler.NON_RECYCLING_INSTANCE,
            new NamedWriteableRegistry(Collections.emptyList()),
            new NoneCircuitBreakerService(),
            new SharedGroupFactory(settings)
        );
        TransportService transportService = new MockTransportService(
            settings,
            transport,
            threadPool,
            transportInterceptor,
            (boundAddress) -> new DiscoveryNode(
                nodeNameAndId,
                nodeNameAndId,
                boundAddress.publishAddress(),
                emptyMap(),
                emptySet(),
                version
            ),
            null,
            Collections.emptySet()
        );
        transportService.start();
        transportService.acceptIncomingRequests();
        transportServices.add(transportService);
        return transportService;
    }

    @After
    public void tearDown() throws Exception {
        for (TransportService transportService : transportServices) {
            transportService.close();
        }
        super.tearDown();
    }

    @AfterClass
    public static void terminateThreadPool() {
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        // since static must set to null to be eligible for collection
        threadPool = null;
    }

    public void testConnectToNodeLight() {
        Settings settings = Settings.builder().put("cluster.name", "test").build();

        TransportService transportServiceA = startServices("TS_A", settings, Version.CURRENT, TransportService.NOOP_TRANSPORT_INTERCEPTOR);
        TransportService transportServiceB = startServices(
            "TS_B",
            settings,
            VersionUtils.randomVersionBetween(random(), Version.CURRENT.minimumCompatibilityVersion(), Version.CURRENT),
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        DiscoveryNode discoveryNode = new DiscoveryNode(
            "",
            transportServiceB.getLocalNode().getAddress(),
            emptyMap(),
            emptySet(),
            Version.CURRENT.minimumCompatibilityVersion()
        );
        try (
            Transport.Connection connection = AbstractSimpleTransportTestCase.openConnection(
                transportServiceA,
                discoveryNode,
                TestProfiles.LIGHT_PROFILE
            )
        ) {
            DiscoveryNode connectedNode = PlainActionFuture.get(fut -> transportServiceA.handshake(connection, timeout, fut));
            assertNotNull(connectedNode);
            // the name and version should be updated
            assertEquals(connectedNode.getName(), "TS_B");
            assertEquals(connectedNode.getVersion(), transportServiceB.getLocalNode().getVersion());
            assertFalse(transportServiceA.nodeConnected(discoveryNode));
        }
    }

    public void testMismatchedClusterName() {

        TransportService transportServiceA = startServices(
            "TS_A",
            Settings.builder().put("cluster.name", "a").build(),
            Version.CURRENT,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        TransportService transportServiceB = startServices(
            "TS_B",
            Settings.builder().put("cluster.name", "b").build(),
            Version.CURRENT,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        DiscoveryNode discoveryNode = new DiscoveryNode(
            "",
            transportServiceB.getLocalNode().getAddress(),
            emptyMap(),
            emptySet(),
            Version.CURRENT.minimumCompatibilityVersion()
        );
        IllegalStateException ex = expectThrows(IllegalStateException.class, () -> {
            try (
                Transport.Connection connection = AbstractSimpleTransportTestCase.openConnection(
                    transportServiceA,
                    discoveryNode,
                    TestProfiles.LIGHT_PROFILE
                )
            ) {
                PlainActionFuture.get(fut -> transportServiceA.handshake(connection, timeout, fut.map(x -> null)));
            }
        });
        assertThat(
            ex.getMessage(),
            containsString("handshake with [" + discoveryNode + "] failed: remote cluster name [b] does not match local cluster name [a]")
        );
        assertFalse(transportServiceA.nodeConnected(discoveryNode));
    }

    public void testIncompatibleVersions() {
        Settings settings = Settings.builder().put("cluster.name", "test").build();
        TransportService transportServiceA = startServices("TS_A", settings, Version.CURRENT, TransportService.NOOP_TRANSPORT_INTERCEPTOR);
        TransportService transportServiceB = startServices(
            "TS_B",
            settings,
            VersionUtils.getPreviousVersion(Version.CURRENT.minimumCompatibilityVersion()),
            TransportService.NOOP_TRANSPORT_INTERCEPTOR
        );
        DiscoveryNode discoveryNode = new DiscoveryNode(
            "",
            transportServiceB.getLocalNode().getAddress(),
            emptyMap(),
            emptySet(),
            Version.CURRENT.minimumCompatibilityVersion()
        );
        IllegalStateException ex = expectThrows(IllegalStateException.class, () -> {
            try (
                Transport.Connection connection = AbstractSimpleTransportTestCase.openConnection(
                    transportServiceA,
                    discoveryNode,
                    TestProfiles.LIGHT_PROFILE
                )
            ) {
                PlainActionFuture.get(fut -> transportServiceA.handshake(connection, timeout, fut.map(x -> null)));
            }
        });
        assertThat(
            ex.getMessage(),
            containsString(
                "handshake with ["
                    + discoveryNode
                    + "] failed: remote node version ["
                    + transportServiceB.getLocalNode().getVersion()
                    + "] is incompatible with local node version ["
                    + Version.CURRENT
                    + "]"
            )
        );
        assertFalse(transportServiceA.nodeConnected(discoveryNode));
    }

    public void testNodeConnectWithDifferentNodeId() {
        Settings settings = Settings.builder().put("cluster.name", "test").build();
        TransportService transportServiceA = startServices("TS_A", settings, Version.CURRENT, TransportService.NOOP_TRANSPORT_INTERCEPTOR);
        TransportService transportServiceB = startServices("TS_B", settings, Version.CURRENT, TransportService.NOOP_TRANSPORT_INTERCEPTOR);
        DiscoveryNode discoveryNode = new DiscoveryNode(
            randomAlphaOfLength(10),
            transportServiceB.getLocalNode().getAddress(),
            emptyMap(),
            emptySet(),
            transportServiceB.getLocalNode().getVersion()
        );
        ConnectTransportException ex = expectThrows(
            ConnectTransportException.class,
            () -> AbstractSimpleTransportTestCase.connectToNode(transportServiceA, discoveryNode, TestProfiles.LIGHT_PROFILE)
        );
        assertThat(ex.getMessage(), containsString("unexpected remote node"));
        assertFalse(transportServiceA.nodeConnected(discoveryNode));
    }

    public void testRejectsMismatchedBuildHash() {
        final DisruptingTransportInterceptor transportInterceptorA = new DisruptingTransportInterceptor();
        final DisruptingTransportInterceptor transportInterceptorB = new DisruptingTransportInterceptor();
        transportInterceptorA.setModifyBuildHash(true);
        transportInterceptorB.setModifyBuildHash(true);
        final Settings settings = Settings.builder()
            .put("cluster.name", "a")
            .put(IGNORE_DESERIALIZATION_ERRORS_SETTING.getKey(), true) // suppress assertions to test production error-handling
            .build();
        final TransportService transportServiceA = startServices("TS_A", settings, Version.CURRENT, transportInterceptorA);
        final TransportService transportServiceB = startServices("TS_B", settings, Version.CURRENT, transportInterceptorB);
        final DiscoveryNode discoveryNode = new DiscoveryNode(
            "",
            transportServiceB.getLocalNode().getAddress(),
            emptyMap(),
            emptySet(),
            Version.CURRENT.minimumCompatibilityVersion()
        );
        TransportSerializationException ex = expectThrows(TransportSerializationException.class, () -> {
            try (
                Transport.Connection connection = AbstractSimpleTransportTestCase.openConnection(
                    transportServiceA,
                    discoveryNode,
                    TestProfiles.LIGHT_PROFILE
                )
            ) {
                PlainActionFuture.get(fut -> transportServiceA.handshake(connection, timeout, fut.map(x -> null)));
            }
        });
        assertThat(
            ExceptionsHelper.unwrap(ex, IllegalArgumentException.class).getMessage(),
            containsString("which has an incompatible wire format")
        );
        assertFalse(transportServiceA.nodeConnected(discoveryNode));
    }

    public void testAcceptsMismatchedBuildHashFromDifferentVersion() {
        final DisruptingTransportInterceptor transportInterceptorA = new DisruptingTransportInterceptor();
        final DisruptingTransportInterceptor transportInterceptorB = new DisruptingTransportInterceptor();
        transportInterceptorA.setModifyBuildHash(true);
        transportInterceptorB.setModifyBuildHash(true);
        final TransportService transportServiceA = startServices(
            "TS_A",
            Settings.builder().put("cluster.name", "a").build(),
            Version.CURRENT,
            transportInterceptorA
        );
        final TransportService transportServiceB = startServices(
            "TS_B",
            Settings.builder().put("cluster.name", "a").build(),
            Version.CURRENT.minimumCompatibilityVersion(),
            transportInterceptorB
        );
        AbstractSimpleTransportTestCase.connectToNode(transportServiceA, transportServiceB.getLocalNode(), TestProfiles.LIGHT_PROFILE);
        assertTrue(transportServiceA.nodeConnected(transportServiceB.getLocalNode()));
    }

    private static class DisruptingTransportInterceptor implements TransportInterceptor {

        private boolean modifyBuildHash;

        public void setModifyBuildHash(boolean modifyBuildHash) {
            this.modifyBuildHash = modifyBuildHash;
        }

        @Override
        public <T extends TransportRequest> TransportRequestHandler<T> interceptHandler(
            String action,
            String executor,
            boolean forceExecution,
            TransportRequestHandler<T> actualHandler
        ) {

            if (TransportService.HANDSHAKE_ACTION_NAME.equals(action)) {
                return (request, channel, task) -> actualHandler.messageReceived(request, new TransportChannel() {
                    @Override
                    public String getProfileName() {
                        return channel.getProfileName();
                    }

                    @Override
                    public String getChannelType() {
                        return channel.getChannelType();
                    }

                    @Override
                    public void sendResponse(TransportResponse response) throws IOException {
                        assertThat(response, instanceOf(TransportService.HandshakeResponse.class));
                        if (modifyBuildHash) {
                            final TransportService.HandshakeResponse handshakeResponse = (TransportService.HandshakeResponse) response;
                            channel.sendResponse(
                                new TransportService.HandshakeResponse(
                                    handshakeResponse.getVersion(),
                                    handshakeResponse.getBuildHash() + "-modified",
                                    handshakeResponse.getDiscoveryNode(),
                                    handshakeResponse.getClusterName()
                                )
                            );
                        } else {
                            channel.sendResponse(response);
                        }
                    }

                    @Override
                    public void sendResponse(Exception exception) throws IOException {
                        channel.sendResponse(exception);

                    }
                }, task);
            } else {
                return actualHandler;
            }
        }
    }

}
