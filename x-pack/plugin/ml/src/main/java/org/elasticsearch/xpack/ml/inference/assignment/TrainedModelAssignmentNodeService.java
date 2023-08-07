/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.inference.assignment;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.LifecycleListener;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskAwareRequest;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.ml.MlMetadata;
import org.elasticsearch.xpack.core.ml.action.StartTrainedModelDeploymentAction;
import org.elasticsearch.xpack.core.ml.action.UpdateTrainedModelAssignmentRoutingInfoAction;
import org.elasticsearch.xpack.core.ml.inference.assignment.RoutingInfo;
import org.elasticsearch.xpack.core.ml.inference.assignment.RoutingInfoUpdate;
import org.elasticsearch.xpack.core.ml.inference.assignment.RoutingState;
import org.elasticsearch.xpack.core.ml.inference.assignment.RoutingStateAndReason;
import org.elasticsearch.xpack.core.ml.inference.assignment.TrainedModelAssignment;
import org.elasticsearch.xpack.core.ml.inference.persistence.InferenceIndexConstants;
import org.elasticsearch.xpack.core.ml.inference.results.InferenceResults;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceConfig;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.inference.deployment.DeploymentManager;
import org.elasticsearch.xpack.ml.inference.deployment.ModelStats;
import org.elasticsearch.xpack.ml.inference.deployment.NlpInferenceInput;
import org.elasticsearch.xpack.ml.inference.deployment.TrainedModelDeploymentTask;
import org.elasticsearch.xpack.ml.task.AbstractJobPersistentTasksExecutor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.elasticsearch.core.Strings.format;
import static org.elasticsearch.xpack.core.ml.MlTasks.TRAINED_MODEL_ASSIGNMENT_TASK_ACTION;
import static org.elasticsearch.xpack.core.ml.MlTasks.TRAINED_MODEL_ASSIGNMENT_TASK_TYPE;
import static org.elasticsearch.xpack.ml.MachineLearning.ML_PYTORCH_MODEL_INFERENCE_FEATURE;

public class TrainedModelAssignmentNodeService implements ClusterStateListener {

    private static final String NODE_NO_LONGER_REFERENCED = "node no longer referenced in model routing table";
    private static final String ASSIGNMENT_NO_LONGER_EXISTS = "deployment assignment no longer exists";
    private static final TimeValue MODEL_LOADING_CHECK_INTERVAL = TimeValue.timeValueSeconds(1);
    private static final TimeValue CONTROL_MESSAGE_TIMEOUT = TimeValue.timeValueSeconds(60);
    private static final Logger logger = LogManager.getLogger(TrainedModelAssignmentNodeService.class);
    private final TrainedModelAssignmentService trainedModelAssignmentService;
    private final DeploymentManager deploymentManager;
    private final TaskManager taskManager;
    private final Map<String, TrainedModelDeploymentTask> deploymentIdToTask;
    /**
     * This should be treated as a set, the value is ignored. It simply contains the node ids who are currently
     * draining their tasks in preparation for shutting down.
     */
    private final Map<String, Boolean> nodesCurrentlyDraining;
    private final ThreadPool threadPool;
    private final Deque<TrainedModelDeploymentTask> loadingModels;
    private final XPackLicenseState licenseState;
    private final IndexNameExpressionResolver expressionResolver;
    private volatile Scheduler.Cancellable scheduledFuture;
    private volatile ClusterState latestState;
    private volatile boolean stopped;
    private volatile String nodeId;

    public TrainedModelAssignmentNodeService(
        TrainedModelAssignmentService trainedModelAssignmentService,
        ClusterService clusterService,
        DeploymentManager deploymentManager,
        IndexNameExpressionResolver expressionResolver,
        TaskManager taskManager,
        ThreadPool threadPool,
        XPackLicenseState licenseState
    ) {
        this.trainedModelAssignmentService = trainedModelAssignmentService;
        this.deploymentManager = deploymentManager;
        this.taskManager = taskManager;
        this.deploymentIdToTask = new ConcurrentHashMap<>();
        this.nodesCurrentlyDraining = new ConcurrentHashMap<>();
        this.loadingModels = new ConcurrentLinkedDeque<>();
        this.threadPool = threadPool;
        this.licenseState = licenseState;
        clusterService.addLifecycleListener(new LifecycleListener() {
            @Override
            public void afterStart() {
                nodeId = clusterService.localNode().getId();
                start();
            }

            @Override
            public void beforeStop() {
                stop();
            }
        });
        this.expressionResolver = expressionResolver;
    }

    TrainedModelAssignmentNodeService(
        TrainedModelAssignmentService trainedModelAssignmentService,
        ClusterService clusterService,
        DeploymentManager deploymentManager,
        IndexNameExpressionResolver expressionResolver,
        TaskManager taskManager,
        ThreadPool threadPool,
        String nodeId,
        XPackLicenseState licenseState
    ) {
        this.trainedModelAssignmentService = trainedModelAssignmentService;
        this.deploymentManager = deploymentManager;
        this.taskManager = taskManager;
        this.deploymentIdToTask = new ConcurrentHashMap<>();
        this.nodesCurrentlyDraining = new ConcurrentHashMap<>();
        this.loadingModels = new ConcurrentLinkedDeque<>();
        this.threadPool = threadPool;
        this.nodeId = nodeId;
        this.licenseState = licenseState;
        clusterService.addLifecycleListener(new LifecycleListener() {
            @Override
            public void afterStart() {
                start();
            }

            @Override
            public void beforeStop() {
                stop();
            }
        });
        this.expressionResolver = expressionResolver;
    }

    void stopDeploymentAsync(TrainedModelDeploymentTask task, String reason, ActionListener<Void> listener) {
        if (stopped) {
            return;
        }
        task.markAsStopped(reason);

        threadPool.executor(MachineLearning.UTILITY_THREAD_POOL_NAME).execute(() -> {
            try {
                deploymentManager.stopDeployment(task);
                taskManager.unregister(task);
                deploymentIdToTask.remove(task.getDeploymentId());
                listener.onResponse(null);
            } catch (Exception e) {
                listener.onFailure(e);
            }
        });
    }

    void drainNodeRequestsAsync(TrainedModelDeploymentTask task, String reason, ActionListener<Void> listener) {
        if (stopped) {
            return;
        }
        task.markAsStopped(reason);

        threadPool.executor(MachineLearning.UTILITY_THREAD_POOL_NAME).execute(() -> {
            try {
                deploymentManager.stopDeployment(task);
                taskManager.unregister(task);
                deploymentIdToTask.remove(task.getDeploymentId());
                listener.onResponse(null);
            } catch (Exception e) {
                listener.onFailure(e);
            }
        });
    }

    public void start() {
        stopped = false;
        scheduledFuture = threadPool.scheduleWithFixedDelay(
            this::loadQueuedModels,
            MODEL_LOADING_CHECK_INTERVAL,
            MachineLearning.UTILITY_THREAD_POOL_NAME
        );
    }

    public void stop() {
        stopped = true;
        ThreadPool.Cancellable cancellable = this.scheduledFuture;
        if (cancellable != null) {
            cancellable.cancel();
        }
    }

    void loadQueuedModels() {
        TrainedModelDeploymentTask loadingTask;
        if (loadingModels.isEmpty()) {
            return;
        }
        if (latestState != null) {
            List<String> unassignedIndices = AbstractJobPersistentTasksExecutor.verifyIndicesPrimaryShardsAreActive(
                latestState,
                expressionResolver,
                // we allow missing as that means the index doesn't exist at all and our loading will fail for the models and we need
                // to notify as necessary
                true,
                InferenceIndexConstants.INDEX_PATTERN,
                InferenceIndexConstants.nativeDefinitionStore()
            );
            if (unassignedIndices.size() > 0) {
                logger.trace("not loading models as indices {} primary shards are unassigned", unassignedIndices);
                return;
            }
        }
        logger.trace("attempting to load all currently queued models");
        // NOTE: As soon as this method exits, the timer for the scheduler starts ticking
        Deque<TrainedModelDeploymentTask> loadingToRetry = new ArrayDeque<>();
        while ((loadingTask = loadingModels.poll()) != null) {
            final String deploymentId = loadingTask.getDeploymentId();
            if (loadingTask.isStopped()) {
                if (logger.isTraceEnabled()) {
                    String reason = loadingTask.stoppedReason().orElse("_unknown_");
                    logger.trace("[{}] attempted to load stopped task with reason [{}]", deploymentId, reason);
                }
                continue;
            }
            if (stopped) {
                return;
            }
            final PlainActionFuture<TrainedModelDeploymentTask> listener = new PlainActionFuture<>();
            try {
                deploymentManager.startDeployment(loadingTask, listener);
                // This needs to be synchronous here in the utility thread to keep queueing order
                TrainedModelDeploymentTask deployedTask = listener.actionGet();
                // kicks off asynchronous cluster state update
                handleLoadSuccess(deployedTask);
            } catch (Exception ex) {
                logger.warn(() -> "[" + deploymentId + "] Start deployment failed", ex);
                if (ExceptionsHelper.unwrapCause(ex) instanceof ResourceNotFoundException) {
                    String modelId = loadingTask.getParams().getModelId();
                    logger.debug(() -> "[" + deploymentId + "] Start deployment failed as model [" + modelId + "] was not found", ex);
                    handleLoadFailure(loadingTask, ExceptionsHelper.missingTrainedModel(modelId, ex));
                } else if (ExceptionsHelper.unwrapCause(ex) instanceof SearchPhaseExecutionException) {
                    logger.debug(() -> "[" + deploymentId + "] Start deployment failed, will retry", ex);
                    // A search phase execution failure should be retried, push task back to the queue
                    loadingToRetry.add(loadingTask);
                } else {
                    handleLoadFailure(loadingTask, ex);
                }
            }
        }
        loadingModels.addAll(loadingToRetry);
    }

    public void stopDeploymentAndNotify(TrainedModelDeploymentTask task, String reason, ActionListener<AcknowledgedResponse> listener) {
        final RoutingInfoUpdate updateToStopped = RoutingInfoUpdate.updateStateAndReason(
            new RoutingStateAndReason(RoutingState.STOPPED, reason)
        );

        ActionListener<Void> notifyDeploymentOfStopped = ActionListener.wrap(
            _void -> updateStoredState(task.getDeploymentId(), updateToStopped, listener),
            failed -> { // if we failed to stop the process, something strange is going on, but we should still notify of stop
                logger.warn(() -> "[" + task.getDeploymentId() + "] failed to stop due to error", failed);
                updateStoredState(task.getDeploymentId(), updateToStopped, listener);
            }
        );
        updateStoredState(
            task.getDeploymentId(),
            RoutingInfoUpdate.updateStateAndReason(new RoutingStateAndReason(RoutingState.STOPPING, reason)),
            ActionListener.wrap(success -> stopDeploymentAsync(task, reason, notifyDeploymentOfStopped), e -> {
                if (ExceptionsHelper.unwrapCause(e) instanceof ResourceNotFoundException) {
                    logger.debug(
                        () -> format("[%s] failed to set routing state to stopping as assignment already removed", task.getDeploymentId()),
                        e
                    );
                } else {
                    // this is an unexpected error
                    // TODO this means requests may still be routed here, should we not stop deployment?
                    logger.warn(() -> "[" + task.getDeploymentId() + "] failed to set routing state to stopping due to error", e);
                }
                stopDeploymentAsync(task, reason, notifyDeploymentOfStopped);
            })
        );
    }

    public void infer(
        TrainedModelDeploymentTask task,
        InferenceConfig config,
        NlpInferenceInput input,
        boolean skipQueue,
        TimeValue timeout,
        CancellableTask parentActionTask,
        ActionListener<InferenceResults> listener
    ) {
        deploymentManager.infer(task, config, input, skipQueue, timeout, parentActionTask, listener);
    }

    public Optional<ModelStats> modelStats(TrainedModelDeploymentTask task) {
        return deploymentManager.getStats(task);
    }

    public void clearCache(TrainedModelDeploymentTask task, ActionListener<AcknowledgedResponse> listener) {
        deploymentManager.clearCache(task, CONTROL_MESSAGE_TIMEOUT, listener);
    }

    private TaskAwareRequest taskAwareRequest(StartTrainedModelDeploymentAction.TaskParams params) {
        final TrainedModelAssignmentNodeService trainedModelAssignmentNodeService = this;
        return new TaskAwareRequest() {
            @Override
            public void setParentTask(TaskId taskId) {
                throw new UnsupportedOperationException("parent task id for model deployment tasks shouldn't change");
            }

            @Override
            public void setRequestId(long requestId) {
                throw new UnsupportedOperationException("does not have request ID");
            }

            @Override
            public TaskId getParentTask() {
                return TaskId.EMPTY_TASK_ID;
            }

            @Override
            public Task createTask(long id, String type, String action, TaskId parentTaskId, Map<String, String> headers) {
                return new TrainedModelDeploymentTask(
                    id,
                    type,
                    action,
                    parentTaskId,
                    headers,
                    params,
                    trainedModelAssignmentNodeService,
                    licenseState,
                    ML_PYTORCH_MODEL_INFERENCE_FEATURE
                );
            }
        };
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        latestState = event.state();
        if (event.metadataChanged()) {
            final boolean isResetMode = MlMetadata.getMlMetadata(event.state()).isResetMode();
            TrainedModelAssignmentMetadata modelAssignmentMetadata = TrainedModelAssignmentMetadata.fromState(event.state());
            final String currentNode = event.state().nodes().getLocalNodeId();
            final boolean isNewAllocationSupported = event.state()
                .getMinTransportVersion()
                .onOrAfter(TrainedModelAssignmentClusterService.DISTRIBUTED_MODEL_ALLOCATION_TRANSPORT_VERSION);

            if (isResetMode == false && isNewAllocationSupported) {
                updateNumberOfAllocations(modelAssignmentMetadata);
            }

            for (TrainedModelAssignment trainedModelAssignment : modelAssignmentMetadata.allAssignments().values()) {
                RoutingInfo routingInfo = trainedModelAssignment.getNodeRoutingTable().get(currentNode);
                // Add new models to start loading
                if (routingInfo != null) {
                    if (isNewAllocationSupported) {
                        if (routingInfo.getState() == RoutingState.STARTING
                            && deploymentIdToTask.containsKey(trainedModelAssignment.getDeploymentId())
                            && deploymentIdToTask.get(trainedModelAssignment.getDeploymentId()).isFailed()) {
                            // This is a failed assignment and we are restarting it. For this we need to remove the task first.
                            taskManager.unregister(deploymentIdToTask.get(trainedModelAssignment.getDeploymentId()));
                            deploymentIdToTask.remove(trainedModelAssignment.getDeploymentId());
                        }
                        if (routingInfo.getState().isAnyOf(RoutingState.STARTING, RoutingState.STARTED) // periodic retries of `failed`
                                                                                                        // should
                            // be handled in a separate process
                            // This means we don't already have a task and should attempt creating one and starting the model loading
                            // If we don't have a task but are STARTED, this means the cluster state had a started assignment,
                            // the node crashed and then started again
                            && deploymentIdToTask.containsKey(trainedModelAssignment.getDeploymentId()) == false
                            // If we are in reset mode, don't start loading a new model on this node.
                            && isResetMode == false) {
                            prepareModelToLoad(
                                new StartTrainedModelDeploymentAction.TaskParams(
                                    trainedModelAssignment.getTaskParams().getModelId(),
                                    trainedModelAssignment.getDeploymentId(),
                                    trainedModelAssignment.getTaskParams().getModelBytes(),
                                    routingInfo.getCurrentAllocations(),
                                    trainedModelAssignment.getTaskParams().getThreadsPerAllocation(),
                                    trainedModelAssignment.getTaskParams().getQueueCapacity(),
                                    trainedModelAssignment.getTaskParams().getCacheSize().orElse(null),
                                    trainedModelAssignment.getTaskParams().getPriority()
                                )
                            );
                        }
                    }

                    // The route is stopping and we are shutting down means the node is going to die but we need to drain the pending
                    // requests
                    if (routingInfo.getState() == RoutingState.STOPPING
                        && event.state().metadata().nodeShutdowns().contains(currentNode)
                        && nodesCurrentlyDraining.containsKey(currentNode) == false) {
                        nodesCurrentlyDraining.put(currentNode, false);
                        logger.error(format("going to start draining queue for %s", currentNode));

                        // TODO kick off async draining work
                        // TODO remove this
                        TrainedModelDeploymentTask task = deploymentIdToTask.remove(trainedModelAssignment.getDeploymentId());
                        if (task != null) {
                            stopDeploymentAsync(
                                task,
                                NODE_NO_LONGER_REFERENCED,
                                ActionListener.wrap(
                                    r -> logger.error(() -> "[" + task.getDeploymentId() + "] stopped deployment after draining queue"),
                                    e -> logger.error(
                                        () -> "[" + task.getDeploymentId() + "] failed to fully stop deployment after draining queue",
                                        e
                                    )
                                )
                            );
                        }
                    }
                }

                // This model is not routed to the current node at all
                if (routingInfo == null) {

                    logger.error("routing info was null");
                    TrainedModelDeploymentTask task = deploymentIdToTask.remove(trainedModelAssignment.getDeploymentId());
                    if (task != null) {
                        stopDeploymentAsync(
                            task,
                            NODE_NO_LONGER_REFERENCED,
                            ActionListener.wrap(
                                r -> logger.trace(() -> "[" + task.getDeploymentId() + "] stopped deployment"),
                                e -> logger.warn(() -> "[" + task.getDeploymentId() + "] failed to fully stop deployment", e)
                            )
                        );
                    }
                }
            }
            List<TrainedModelDeploymentTask> toCancel = new ArrayList<>();
            for (String deploymentIds : Sets.difference(deploymentIdToTask.keySet(), modelAssignmentMetadata.allAssignments().keySet())) {
                toCancel.add(deploymentIdToTask.remove(deploymentIds));
            }
            // should all be stopped in the same executor thread?
            for (TrainedModelDeploymentTask t : toCancel) {
                stopDeploymentAsync(
                    t,
                    ASSIGNMENT_NO_LONGER_EXISTS,
                    ActionListener.wrap(
                        r -> logger.trace(() -> "[" + t.getDeploymentId() + "] stopped deployment"),
                        e -> logger.warn(() -> "[" + t.getDeploymentId() + "] failed to fully stop deployment", e)
                    )
                );
            }
        }
    }

    private void updateNumberOfAllocations(TrainedModelAssignmentMetadata assignments) {
        List<TrainedModelAssignment> assignmentsToUpdate = assignments.allAssignments()
            .values()
            .stream()
            .filter(a -> hasStartingAssignments(a) == false)
            .filter(a -> a.isRoutedToNode(nodeId))
            .filter(a -> {
                RoutingInfo routingInfo = a.getNodeRoutingTable().get(nodeId);
                return routingInfo.getState() == RoutingState.STARTED
                    && routingInfo.getCurrentAllocations() != routingInfo.getTargetAllocations();
            })
            .toList();

        for (TrainedModelAssignment assignment : assignmentsToUpdate) {
            TrainedModelDeploymentTask task = deploymentIdToTask.get(assignment.getDeploymentId());
            if (task == null) {
                logger.debug(() -> format("[%s] task was removed whilst updating number of allocations", assignment.getDeploymentId()));
                continue;
            }
            RoutingInfo routingInfo = assignment.getNodeRoutingTable().get(nodeId);
            deploymentManager.updateNumAllocations(
                task,
                assignment.getNodeRoutingTable().get(nodeId).getTargetAllocations(),
                CONTROL_MESSAGE_TIMEOUT,
                ActionListener.wrap(threadSettings -> {
                    logger.debug(
                        "[{}] Updated number of allocations to [{}]",
                        assignment.getDeploymentId(),
                        threadSettings.numAllocations()
                    );
                    task.updateNumberOfAllocations(threadSettings.numAllocations());
                    updateStoredState(
                        assignment.getDeploymentId(),
                        RoutingInfoUpdate.updateNumberOfAllocations(threadSettings.numAllocations()),
                        ActionListener.noop()
                    );
                },
                    e -> logger.error(
                        format(
                            "[%s] Could not update number of allocations to [%s]",
                            assignment.getDeploymentId(),
                            routingInfo.getTargetAllocations()
                        ),
                        e
                    )
                )
            );
        }
    }

    private boolean hasStartingAssignments(TrainedModelAssignment assignment) {
        return assignment.getNodeRoutingTable()
            .values()
            .stream()
            .anyMatch(routingInfo -> routingInfo.getState().isAnyOf(RoutingState.STARTING));
    }

    // For testing purposes
    TrainedModelDeploymentTask getTask(String deploymentId) {
        return deploymentIdToTask.get(deploymentId);
    }

    void prepareModelToLoad(StartTrainedModelDeploymentAction.TaskParams taskParams) {
        logger.debug(
            () -> format(
                "[%s] preparing to load model [%s] with task params: %s",
                taskParams.getDeploymentId(),
                taskParams.getModelId(),
                taskParams
            )
        );
        TrainedModelDeploymentTask task = (TrainedModelDeploymentTask) taskManager.register(
            TRAINED_MODEL_ASSIGNMENT_TASK_TYPE,
            TRAINED_MODEL_ASSIGNMENT_TASK_ACTION,
            taskAwareRequest(taskParams),
            false
        );
        // threadsafe check to verify we are not loading/loaded the model
        if (deploymentIdToTask.putIfAbsent(taskParams.getDeploymentId(), task) == null) {
            loadingModels.add(task);
        } else {
            // If there is already a task for the deployment, unregister the new task
            taskManager.unregister(task);
        }
    }

    private void handleLoadSuccess(TrainedModelDeploymentTask task) {
        logger.debug(
            () -> "["
                + task.getParams().getDeploymentId()
                + "] model ["
                + task.getParams().getModelId()
                + "] successfully loaded and ready for inference. Notifying master node"
        );
        if (task.isStopped()) {
            logger.debug(
                () -> format(
                    "[%s] model [%s] loaded successfully, but stopped before routing table was updated; reason [%s]",
                    task.getDeploymentId(),
                    task.getParams().getModelId(),
                    task.stoppedReason().orElse("_unknown_")
                )
            );
            return;
        }

        updateStoredState(
            task.getDeploymentId(),
            RoutingInfoUpdate.updateStateAndReason(new RoutingStateAndReason(RoutingState.STARTED, "")),
            ActionListener.wrap(r -> logger.debug(() -> "[" + task.getDeploymentId() + "] model loaded and accepting routes"), e -> {
                // This means that either the assignment has been deleted, or this node's particular route has been removed
                if (ExceptionsHelper.unwrapCause(e) instanceof ResourceNotFoundException) {
                    logger.debug(
                        () -> format(
                            "[%s] model [%s] loaded but failed to start accepting routes as assignment to this node was removed",
                            task.getDeploymentId(),
                            task.getParams().getModelId()
                        ),
                        e
                    );
                } else {
                    // this is an unexpected error
                    logger.warn(
                        () -> "["
                            + task.getDeploymentId()
                            + "] model ["
                            + task.getParams().getModelId()
                            + "] loaded but failed to start accepting routes",
                        e
                    );
                }
            })
        );
    }

    private void updateStoredState(String deploymentId, RoutingInfoUpdate update, ActionListener<AcknowledgedResponse> listener) {
        if (stopped) {
            return;
        }
        trainedModelAssignmentService.updateModelAssignmentState(
            new UpdateTrainedModelAssignmentRoutingInfoAction.Request(nodeId, deploymentId, update),
            ActionListener.wrap(success -> {
                logger.debug(() -> format("[%s] deployment routing info was updated with [%s] and master notified", deploymentId, update));
                listener.onResponse(AcknowledgedResponse.TRUE);
            }, error -> {
                logger.warn(() -> format("[%s] failed to update deployment routing info with [%s]", deploymentId, update), error);
                listener.onFailure(error);
            })
        );
    }

    private void handleLoadFailure(TrainedModelDeploymentTask task, Exception ex) {
        logger.error(() -> "[" + task.getDeploymentId() + "] model [" + task.getParams().getModelId() + "] failed to load", ex);
        if (task.isStopped()) {
            logger.debug(
                () -> format(
                    "[%s] model [" + task.getParams().getModelId() + "] failed to load, but is now stopped; reason [%s]",
                    task.getDeploymentId(),
                    task.getParams().getModelId(),
                    task.stoppedReason().orElse("_unknown_")
                )
            );
        }
        // TODO: Do we want to stop the task? This would cause it to be reloaded by state updates on INITIALIZING
        // We should stop the local task so that future task actions won't get routed to the older one.
        Runnable stopTask = () -> stopDeploymentAsync(
            task,
            "model failed to load; reason [" + ex.getMessage() + "]",
            ActionListener.noop()
        );
        updateStoredState(
            task.getDeploymentId(),
            RoutingInfoUpdate.updateStateAndReason(
                new RoutingStateAndReason(RoutingState.FAILED, ExceptionsHelper.unwrapCause(ex).getMessage())
            ),
            ActionListener.wrap(r -> stopTask.run(), e -> stopTask.run())
        );
    }

    public void failAssignment(TrainedModelDeploymentTask task, String reason) {
        updateStoredState(
            task.getDeploymentId(),
            RoutingInfoUpdate.updateStateAndReason(new RoutingStateAndReason(RoutingState.FAILED, reason)),
            ActionListener.wrap(
                r -> logger.debug(
                    () -> format(
                        "[%s] Successfully updating assignment state to [%s] with reason [%s]",
                        task.getDeploymentId(),
                        RoutingState.FAILED,
                        reason
                    )
                ),
                e -> logger.error(
                    () -> format(
                        "[%s] Error while updating assignment state to [%s] with reason [%s]",
                        task.getDeploymentId(),
                        RoutingState.FAILED,
                        reason
                    ),
                    e
                )
            )
        );
    }
}
