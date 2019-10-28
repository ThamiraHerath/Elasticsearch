/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.transform.transforms;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.LatchedActionListener;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.indexing.IndexerState;
import org.elasticsearch.xpack.core.scheduler.SchedulerEngine;
import org.elasticsearch.xpack.core.transform.TransformField;
import org.elasticsearch.xpack.core.transform.TransformMessages;
import org.elasticsearch.xpack.core.transform.action.StartTransformAction;
import org.elasticsearch.xpack.core.transform.transforms.TransformTaskParams;
import org.elasticsearch.xpack.core.transform.transforms.persistence.TransformInternalIndexConstants;
import org.elasticsearch.xpack.core.transform.transforms.TransformCheckpoint;
import org.elasticsearch.xpack.core.transform.transforms.TransformConfig;
import org.elasticsearch.xpack.core.transform.transforms.TransformState;
import org.elasticsearch.xpack.core.transform.transforms.TransformStoredDoc;
import org.elasticsearch.xpack.transform.Transform;
import org.elasticsearch.xpack.transform.checkpoint.TransformCheckpointService;
import org.elasticsearch.xpack.transform.notifications.TransformAuditor;
import org.elasticsearch.xpack.transform.persistence.TransformInternalIndex;
import org.elasticsearch.xpack.transform.persistence.TransformConfigManager;
import org.elasticsearch.xpack.transform.persistence.SeqNoPrimaryTermAndIndex;
import org.elasticsearch.xpack.transform.transforms.pivot.SchemaUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TransformPersistentTasksExecutor extends PersistentTasksExecutor<TransformTaskParams> {

    private static final Logger logger = LogManager.getLogger(TransformPersistentTasksExecutor.class);

    // The amount of time we wait for the cluster state to respond when being marked as failed
    private static final int MARK_AS_FAILED_TIMEOUT_SEC = 90;
    private final Client client;
    private final TransformConfigManager transformsConfigManager;
    private final TransformCheckpointService transformCheckpointService;
    private final SchedulerEngine schedulerEngine;
    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final TransformAuditor auditor;
    private volatile int numFailureRetries;

    public TransformPersistentTasksExecutor(Client client,
                                            TransformConfigManager transformsConfigManager,
                                            TransformCheckpointService transformsCheckpointService,
                                            SchedulerEngine schedulerEngine,
                                            TransformAuditor auditor,
                                            ThreadPool threadPool,
                                            ClusterService clusterService,
                                            Settings settings) {
        super(TransformField.TASK_NAME, Transform.TASK_THREAD_POOL_NAME);
        this.client = client;
        this.transformsConfigManager = transformsConfigManager;
        this.transformCheckpointService = transformsCheckpointService;
        this.schedulerEngine = schedulerEngine;
        this.auditor = auditor;
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.numFailureRetries = TransformTask.NUM_FAILURE_RETRIES_SETTING.get(settings);
        clusterService.getClusterSettings()
            .addSettingsUpdateConsumer(TransformTask.NUM_FAILURE_RETRIES_SETTING, this::setNumFailureRetries);
    }

    @Override
    public PersistentTasksCustomMetaData.Assignment getAssignment(TransformTaskParams params, ClusterState clusterState) {
        List<String> unavailableIndices = verifyIndicesPrimaryShardsAreActive(clusterState);
        if (unavailableIndices.size() != 0) {
            String reason = "Not starting transform [" + params.getId() + "], " +
                "because not all primary shards are active for the following indices [" +
                String.join(",", unavailableIndices) + "]";
            logger.debug(reason);
            return new PersistentTasksCustomMetaData.Assignment(null, reason);
        }
        DiscoveryNode discoveryNode = selectLeastLoadedNode(clusterState, (node) ->
            node.isDataNode() && node.getVersion().onOrAfter(params.getVersion())
        );
        return discoveryNode == null ? NO_NODE_FOUND : new PersistentTasksCustomMetaData.Assignment(discoveryNode.getId(), "");
    }

    static List<String> verifyIndicesPrimaryShardsAreActive(ClusterState clusterState) {
        IndexNameExpressionResolver resolver = new IndexNameExpressionResolver();
        String[] indices = resolver.concreteIndexNames(clusterState,
            IndicesOptions.lenientExpandOpen(),
            TransformInternalIndexConstants.INDEX_NAME_PATTERN,
            TransformInternalIndexConstants.INDEX_NAME_PATTERN_DEPRECATED);
        List<String> unavailableIndices = new ArrayList<>(indices.length);
        for (String index : indices) {
            IndexRoutingTable routingTable = clusterState.getRoutingTable().index(index);
            if (routingTable == null || !routingTable.allPrimaryShardsActive()) {
                unavailableIndices.add(index);
            }
        }
        return unavailableIndices;
    }

    @Override
    protected void nodeOperation(AllocatedPersistentTask task, @Nullable TransformTaskParams params, PersistentTaskState state) {
        final String transformId = params.getId();
        final TransformTask buildTask = (TransformTask) task;
        // NOTE: TransformPersistentTasksExecutor#createTask pulls in the stored task state from the ClusterState when the object
        // is created. TransformTask#ctor takes into account setting the task as failed if that is passed in with the
        // persisted state.
        // TransformPersistentTasksExecutor#startTask will fail as TransformTask#start, when force == false, will return
        // a failure indicating that a failed task cannot be started.
        //
        // We want the rest of the state to be populated in the task when it is loaded on the node so that users can force start it again
        // later if they want.

        final ClientTransformIndexerBuilder indexerBuilder =
            new ClientTransformIndexerBuilder()
                .setAuditor(auditor)
                .setClient(client)
                .setTransformsCheckpointService(transformCheckpointService)
                .setTransformsConfigManager(transformsConfigManager);

        final SetOnce<TransformState> stateHolder = new SetOnce<>();

        ActionListener<StartTransformAction.Response> startTaskListener = ActionListener.wrap(
            response -> logger.info("[{}] successfully completed and scheduled task in node operation", transformId),
            failure -> {
                auditor.error(transformId, "Failed to start transform. " +
                    "Please stop and attempt to start again. Failure: " + failure.getMessage());
                logger.error("Failed to start task ["+ transformId +"] in node operation", failure);
            }
        );

        // <7> load next checkpoint
        ActionListener<TransformCheckpoint> getTransformNextCheckpointListener = ActionListener.wrap(
                nextCheckpoint -> {

                    if (nextCheckpoint.isEmpty()) {
                        // extra safety: reset position and progress if next checkpoint is empty
                        // prevents a failure if for some reason the next checkpoint has been deleted
                        indexerBuilder.setInitialPosition(null);
                        indexerBuilder.setProgress(null);
                    } else {
                        logger.trace("[{}] Loaded next checkpoint [{}] found, starting the task", transformId,
                                nextCheckpoint.getCheckpoint());
                        indexerBuilder.setNextCheckpoint(nextCheckpoint);
                    }

                    final long lastCheckpoint = stateHolder.get().getCheckpoint();

                    startTask(buildTask, indexerBuilder, lastCheckpoint, startTaskListener);
                },
                error -> {
                    // TODO: do not use the same error message as for loading the last checkpoint
                    String msg = TransformMessages.getMessage(TransformMessages.FAILED_TO_LOAD_TRANSFORM_CHECKPOINT, transformId);
                    logger.error(msg, error);
                    markAsFailed(buildTask, msg);
                }
        );

        // <6> load last checkpoint
        ActionListener<TransformCheckpoint> getTransformLastCheckpointListener = ActionListener.wrap(
                lastCheckpoint -> {
                    indexerBuilder.setLastCheckpoint(lastCheckpoint);

                    logger.trace("[{}] Loaded last checkpoint [{}], looking for next checkpoint", transformId,
                            lastCheckpoint.getCheckpoint());
                    transformsConfigManager.getTransformCheckpoint(transformId, lastCheckpoint.getCheckpoint() + 1,
                            getTransformNextCheckpointListener);
                },
                error -> {
                    String msg = TransformMessages.getMessage(TransformMessages.FAILED_TO_LOAD_TRANSFORM_CHECKPOINT, transformId);
                    logger.error(msg, error);
                    markAsFailed(buildTask, msg);
                }
        );

        // <5> Set the previous stats (if they exist), initialize the indexer, start the task (If it is STOPPED)
        // Since we don't create the task until `_start` is called, if we see that the task state is stopped, attempt to start
        // Schedule execution regardless
        ActionListener<Tuple<TransformStoredDoc, SeqNoPrimaryTermAndIndex>> transformStatsActionListener = ActionListener.wrap(
            stateAndStatsAndSeqNoPrimaryTermAndIndex -> {
                TransformStoredDoc stateAndStats = stateAndStatsAndSeqNoPrimaryTermAndIndex.v1();
                SeqNoPrimaryTermAndIndex seqNoPrimaryTermAndIndex = stateAndStatsAndSeqNoPrimaryTermAndIndex.v2();
                // Since we have not set the value for this yet, it SHOULD be null
                buildTask.updateSeqNoPrimaryTermAndIndex(null, seqNoPrimaryTermAndIndex);
                logger.trace("[{}] initializing state and stats: [{}]", transformId, stateAndStats.toString());
                TransformState transformState = stateAndStats.getTransformState();
                indexerBuilder.setInitialStats(stateAndStats.getTransformStats())
                    .setInitialPosition(stateAndStats.getTransformState().getPosition())
                    .setProgress(stateAndStats.getTransformState().getProgress())
                    .setIndexerState(currentIndexerState(transformState))
                    .setShouldStopAtCheckpoint(transformState.shouldStopAtNextCheckpoint());
                logger.debug("[{}] Loading existing state: [{}], position [{}]",
                    transformId,
                    stateAndStats.getTransformState(),
                    stateAndStats.getTransformState().getPosition());

                stateHolder.set(transformState);
                final long lastCheckpoint = stateHolder.get().getCheckpoint();

                if (lastCheckpoint == 0) {
                    logger.trace("[{}] No last checkpoint found, looking for next checkpoint", transformId);
                    transformsConfigManager.getTransformCheckpoint(transformId, lastCheckpoint + 1, getTransformNextCheckpointListener);
                } else {
                    logger.trace ("[{}] Restore last checkpoint: [{}]", transformId, lastCheckpoint);
                    transformsConfigManager.getTransformCheckpoint(transformId, lastCheckpoint, getTransformLastCheckpointListener);
                }
            },
            error -> {
                if (!(error instanceof ResourceNotFoundException)) {
                    String msg = TransformMessages.getMessage(TransformMessages.FAILED_TO_LOAD_TRANSFORM_STATE, transformId);
                    logger.error(msg, error);
                    markAsFailed(buildTask, msg);
                } else {
                    logger.trace("[{}] No stats found (new transform), starting the task", transformId);
                    startTask(buildTask, indexerBuilder, null, startTaskListener);
                }
            }
        );

        // <4> set fieldmappings for the indexer, get the previous stats (if they exist)
        ActionListener<Map<String, String>> getFieldMappingsListener = ActionListener.wrap(
            fieldMappings -> {
                indexerBuilder.setFieldMappings(fieldMappings);
                transformsConfigManager.getTransformStoredDoc(transformId, transformStatsActionListener);
            },
            error -> {
                String msg = TransformMessages.getMessage(TransformMessages.UNABLE_TO_GATHER_FIELD_MAPPINGS,
                    indexerBuilder.getTransformConfig().getDestination().getIndex());
                logger.error(msg, error);
                markAsFailed(buildTask, msg);
            }
        );

        // <3> Validate the transform, assigning it to the indexer, and get the field mappings
        ActionListener<TransformConfig> getTransformConfigListener = ActionListener.wrap(
            config -> {
                if (config.isValid()) {
                    indexerBuilder.setTransformConfig(config);
                    SchemaUtil.getDestinationFieldMappings(client, config.getDestination().getIndex(), getFieldMappingsListener);
                } else {
                    markAsFailed(buildTask,
                        TransformMessages.getMessage(TransformMessages.TRANSFORM_CONFIGURATION_INVALID, transformId));
                }
            },
            error -> {
                String msg = TransformMessages.getMessage(TransformMessages.FAILED_TO_LOAD_TRANSFORM_CONFIGURATION, transformId);
                logger.error(msg, error);
                markAsFailed(buildTask, msg);
            }
        );

        // <2> Get the transform config
        ActionListener<Void> templateCheckListener = ActionListener.wrap(
            aVoid -> transformsConfigManager.getTransformConfiguration(transformId, getTransformConfigListener),
            error -> {
                String msg = "Failed to create internal index mappings";
                logger.error(msg, error);
                markAsFailed(buildTask, msg);
            }
        );

        // <1> Check the internal index template is installed
        TransformInternalIndex.installLatestVersionedIndexTemplateIfRequired(clusterService, client, templateCheckListener);
    }

    private static IndexerState currentIndexerState(TransformState previousState) {
        if (previousState == null) {
            return IndexerState.STOPPED;
        }
        switch(previousState.getIndexerState()){
            // If it is STARTED or INDEXING we want to make sure we revert to started
            // Otherwise, the internal indexer will never get scheduled and execute
            case STARTED:
            case INDEXING:
                return IndexerState.STARTED;
            // If we are STOPPED, STOPPING, or ABORTING and just started executing on this node,
            //  then it is safe to say we should be STOPPED
            case STOPPED:
            case STOPPING:
            case ABORTING:
            default:
                return IndexerState.STOPPED;
        }
    }

    private void markAsFailed(TransformTask task, String reason) {
        CountDownLatch latch = new CountDownLatch(1);

        task.markAsFailed(reason, new LatchedActionListener<>(ActionListener.wrap(
            nil -> {},
            failure -> logger.error("Failed to set task [" + task.getTransformId() +"] to failed", failure)
        ), latch));
        try {
            latch.await(MARK_AS_FAILED_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.error("Timeout waiting for task [" + task.getTransformId() + "] to be marked as failed in cluster state", e);
        }
    }

    private void startTask(TransformTask buildTask,
                           ClientTransformIndexerBuilder indexerBuilder,
                           Long previousCheckpoint,
                           ActionListener<StartTransformAction.Response> listener) {
        buildTask.initializeIndexer(indexerBuilder);
        // TransformTask#start will fail if the task state is FAILED
        buildTask.setNumFailureRetries(numFailureRetries).start(previousCheckpoint, listener);
    }

    private void setNumFailureRetries(int numFailureRetries) {
        this.numFailureRetries = numFailureRetries;
    }

    @Override
    protected AllocatedPersistentTask createTask(long id, String type, String action, TaskId parentTaskId,
            PersistentTasksCustomMetaData.PersistentTask<TransformTaskParams> persistentTask, Map<String, String> headers) {
        return new TransformTask(id, type, action, parentTaskId, persistentTask.getParams(),
            (TransformState) persistentTask.getState(), schedulerEngine, auditor, threadPool, headers);
    }
}
