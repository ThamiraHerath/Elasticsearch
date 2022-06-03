/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.health.node.selection;

import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.NodesShutdownMetadata;
import org.elasticsearch.cluster.metadata.SingleNodeShutdownMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.util.Collections;

import static org.elasticsearch.persistent.PersistentTasksExecutor.NO_NODE_FOUND;
import static org.elasticsearch.test.ClusterServiceUtils.createClusterService;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class HealthNodeSelectorExecutorTests extends ESTestCase {

    /** Needed by {@link ClusterService} **/
    private static ThreadPool threadPool;

    private ClusterService clusterService;
    private PersistentTasksService persistentTasksService;
    private String localNodeId;

    @BeforeClass
    public static void setUpThreadPool() {
        threadPool = new TestThreadPool(HealthNodeSelectorExecutorTests.class.getSimpleName());
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        clusterService = createClusterService(threadPool);
        localNodeId = clusterService.localNode().getId();
        persistentTasksService = mock(PersistentTasksService.class);
    }

    @AfterClass
    public static void tearDownThreadPool() {
        terminate(threadPool);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        clusterService.close();
    }

    public void testTaskCreation() {
        HealthNodeSelectorTaskExecutor executor = new HealthNodeSelectorTaskExecutor(clusterService, persistentTasksService);
        executor.startTask(new ClusterChangedEvent("", initialState(), ClusterState.EMPTY_STATE));
        verify(persistentTasksService, times(1)).sendStartRequest(
            eq("health-node-selector"),
            eq("health-node-selector"),
            eq(new HealthNodeSelectorTaskParams()),
            any()
        );
    }

    public void testSkippingTaskCreationIfItExists() {
        HealthNodeSelectorTaskExecutor executor = new HealthNodeSelectorTaskExecutor(clusterService, persistentTasksService);
        executor.startTask(new ClusterChangedEvent("", stateWithHealthNodeSelectorTask(initialState()), ClusterState.EMPTY_STATE));
        verify(persistentTasksService, never()).sendStartRequest(
            eq("health-node-selector"),
            eq("health-node-selector"),
            eq(new HealthNodeSelectorTaskParams()),
            any()
        );
    }

    public void testDoNothingIfAlreadyShutdown() {
        HealthNodeSelectorTaskExecutor executor = new HealthNodeSelectorTaskExecutor(clusterService, persistentTasksService);
        HealthNodeSelector task = mock(HealthNodeSelector.class);
        PersistentTaskState state = mock(PersistentTaskState.class);
        executor.nodeOperation(task, new HealthNodeSelectorTaskParams(), state);
        ClusterState withShutdown = stateWithNodeShuttingDown(initialState());
        executor.abortTaskIfApplicable(new ClusterChangedEvent("unchanged", withShutdown, withShutdown));
        verify(task, never()).markAsLocallyAborted(anyString());
    }

    public void testAbortOnShutdown() {
        HealthNodeSelectorTaskExecutor executor = new HealthNodeSelectorTaskExecutor(clusterService, persistentTasksService);
        HealthNodeSelector task = mock(HealthNodeSelector.class);
        PersistentTaskState state = mock(PersistentTaskState.class);
        executor.nodeOperation(task, new HealthNodeSelectorTaskParams(), state);
        ClusterState initialState = initialState();
        ClusterState withShutdown = stateWithNodeShuttingDown(initialState);
        executor.abortTaskIfApplicable(new ClusterChangedEvent("shutdown node", withShutdown, initialState));
        verify(task, times(1)).markAsLocallyAborted(anyString());
    }

    private ClusterState initialState() {
        Metadata.Builder metadata = Metadata.builder();

        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder();
        nodes.add(DiscoveryNode.createLocal(Settings.EMPTY, buildNewFakeTransportAddress(), localNodeId));
        nodes.localNodeId(localNodeId);
        nodes.masterNodeId(localNodeId);

        return ClusterState.builder(ClusterName.DEFAULT).nodes(nodes).metadata(metadata).build();
    }

    private ClusterState stateWithNodeShuttingDown(ClusterState clusterState) {
        NodesShutdownMetadata nodesShutdownMetadata = new NodesShutdownMetadata(
            Collections.singletonMap(
                localNodeId,
                SingleNodeShutdownMetadata.builder()
                    .setNodeId(localNodeId)
                    .setReason("shutdown for a unit test")
                    .setType(randomBoolean() ? SingleNodeShutdownMetadata.Type.REMOVE : SingleNodeShutdownMetadata.Type.RESTART)
                    .setStartedAtMillis(randomNonNegativeLong())
                    .build()
            )
        );

        return ClusterState.builder(clusterState)
            .metadata(Metadata.builder(clusterState.metadata()).putCustom(NodesShutdownMetadata.TYPE, nodesShutdownMetadata).build())

            .build();
    }

    private ClusterState stateWithHealthNodeSelectorTask(ClusterState clusterState) {
        ClusterState.Builder builder = ClusterState.builder(clusterState);
        PersistentTasksCustomMetadata.Builder tasks = PersistentTasksCustomMetadata.builder();
        tasks.addTask(HealthNodeSelector.TASK_NAME, HealthNodeSelector.TASK_NAME, new HealthNodeSelectorTaskParams(), NO_NODE_FOUND);

        Metadata.Builder metadata = Metadata.builder(clusterState.metadata()).putCustom(PersistentTasksCustomMetadata.TYPE, tasks.build());
        return builder.metadata(metadata).build();
    }
}
