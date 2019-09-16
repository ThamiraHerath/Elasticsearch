/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.transform.transforms;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.indexing.IndexerState;
import org.elasticsearch.xpack.core.scheduler.SchedulerEngine;
import org.elasticsearch.xpack.core.scheduler.SchedulerEngine.Event;
import org.elasticsearch.xpack.core.transform.TransformField;
import org.elasticsearch.xpack.core.transform.TransformMessages;
import org.elasticsearch.xpack.core.transform.action.StartTransformTaskAction;
import org.elasticsearch.xpack.core.transform.action.StartTransformTaskAction.Response;
import org.elasticsearch.xpack.core.transform.transforms.TransformIndexerPosition;
import org.elasticsearch.xpack.core.transform.transforms.TransformIndexerStats;
import org.elasticsearch.xpack.core.transform.transforms.Transform;
import org.elasticsearch.xpack.core.transform.transforms.TransformCheckpointingInfo;
import org.elasticsearch.xpack.core.transform.transforms.TransformState;
import org.elasticsearch.xpack.core.transform.transforms.TransformTaskState;
import org.elasticsearch.xpack.transform.checkpoint.DataFrameTransformsCheckpointService;
import org.elasticsearch.xpack.transform.notifications.DataFrameAuditor;
import org.elasticsearch.xpack.transform.persistence.SeqNoPrimaryTermAndIndex;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.xpack.core.transform.TransformMessages.DATA_FRAME_CANNOT_START_FAILED_TRANSFORM;
import static org.elasticsearch.xpack.core.transform.TransformMessages.DATA_FRAME_CANNOT_STOP_FAILED_TRANSFORM;


public class DataFrameTransformTask extends AllocatedPersistentTask implements SchedulerEngine.Listener {

    // Default interval the scheduler sends an event if the config does not specify a frequency
    private static final long SCHEDULER_NEXT_MILLISECONDS = 60000;
    private static final Logger logger = LogManager.getLogger(DataFrameTransformTask.class);
    private static final int DEFAULT_FAILURE_RETRIES = 10;
    private volatile int numFailureRetries = DEFAULT_FAILURE_RETRIES;
    // How many times the transform task can retry on an non-critical failure
    public static final Setting<Integer> NUM_FAILURE_RETRIES_SETTING = Setting.intSetting(
        "xpack.data_frame.num_transform_failure_retries",
        DEFAULT_FAILURE_RETRIES,
        0,
        100,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic);
    private static final IndexerState[] RUNNING_STATES = new IndexerState[]{IndexerState.STARTED, IndexerState.INDEXING};
    public static final String SCHEDULE_NAME = TransformField.TASK_NAME + "/schedule";

    private final Transform transform;
    private final SchedulerEngine schedulerEngine;
    private final ThreadPool threadPool;
    private final DataFrameAuditor auditor;
    private final TransformIndexerPosition initialPosition;
    private final IndexerState initialIndexerState;

    private final SetOnce<ClientDataFrameIndexer> indexer = new SetOnce<>();

    private final AtomicReference<TransformTaskState> taskState;
    private final AtomicReference<String> stateReason;
    private final AtomicReference<SeqNoPrimaryTermAndIndex> seqNoPrimaryTermAndIndex = new AtomicReference<>(null);
    // the checkpoint of this data frame, storing the checkpoint until data indexing from source to dest is _complete_
    // Note: Each indexer run creates a new future checkpoint which becomes the current checkpoint only after the indexer run finished
    private final AtomicLong currentCheckpoint;

    public DataFrameTransformTask(long id, String type, String action, TaskId parentTask, Transform transform,
                                  TransformState state, SchedulerEngine schedulerEngine, DataFrameAuditor auditor,
                                  ThreadPool threadPool, Map<String, String> headers) {
        super(id, type, action, TransformField.PERSISTENT_TASK_DESCRIPTION_PREFIX + transform.getId(), parentTask, headers);
        this.transform = transform;
        this.schedulerEngine = schedulerEngine;
        this.threadPool = threadPool;
        this.auditor = auditor;
        IndexerState initialState = IndexerState.STOPPED;
        TransformTaskState initialTaskState = TransformTaskState.STOPPED;
        String initialReason = null;
        long initialGeneration = 0;
        TransformIndexerPosition initialPosition = null;
        if (state != null) {
            initialTaskState = state.getTaskState();
            initialReason = state.getReason();
            final IndexerState existingState = state.getIndexerState();
            if (existingState.equals(IndexerState.INDEXING)) {
                // reset to started as no indexer is running
                initialState = IndexerState.STARTED;
            } else if (existingState.equals(IndexerState.ABORTING) || existingState.equals(IndexerState.STOPPING)) {
                // reset to stopped as something bad happened
                initialState = IndexerState.STOPPED;
            } else {
                initialState = existingState;
            }
            initialPosition = state.getPosition();
            initialGeneration = state.getCheckpoint();
        }

        this.initialIndexerState = initialState;
        this.initialPosition = initialPosition;
        this.currentCheckpoint = new AtomicLong(initialGeneration);
        this.taskState = new AtomicReference<>(initialTaskState);
        this.stateReason = new AtomicReference<>(initialReason);
    }

    public String getTransformId() {
        return transform.getId();
    }

    /**
     * Enable Task API to return detailed status information
     */
    @Override
    public Status getStatus() {
        return getState();
    }

    private ClientDataFrameIndexer getIndexer() {
        return indexer.get();
    }

    public TransformState getState() {
        if (getIndexer() == null) {
            return new TransformState(
                taskState.get(),
                initialIndexerState,
                initialPosition,
                currentCheckpoint.get(),
                stateReason.get(),
                null,
                null, //Node attributes
                false);
        } else {
           return new TransformState(
               taskState.get(),
               indexer.get().getState(),
               indexer.get().getPosition(),
               currentCheckpoint.get(),
               stateReason.get(),
               getIndexer().getProgress(),
               null, //Node attributes
               indexer.get().shouldStopAtCheckpoint());
        }
    }

    public TransformIndexerStats getStats() {
        if (getIndexer() == null) {
            return new TransformIndexerStats();
        } else {
            return getIndexer().getStats();
        }
    }

    public long getCheckpoint() {
        return currentCheckpoint.get();
    }

    long incrementCheckpoint() {
        return currentCheckpoint.getAndIncrement();
    }

    public void getCheckpointingInfo(DataFrameTransformsCheckpointService transformsCheckpointService,
            ActionListener<TransformCheckpointingInfo> listener) {
        ClientDataFrameIndexer indexer = getIndexer();
        if (indexer == null) {
            transformsCheckpointService.getCheckpointingInfo(
                    transform.getId(),
                    currentCheckpoint.get(),
                    initialPosition,
                    null,
                    listener);
            return;
        }
        indexer.getCheckpointProvider().getCheckpointingInfo(
                indexer.getLastCheckpoint(),
                indexer.getNextCheckpoint(),
                indexer.getPosition(),
                indexer.getProgress(),
                ActionListener.wrap(
                    info -> {
                        if (indexer.getChangesLastDetectedAt() == null) {
                            listener.onResponse(info);
                        } else {
                            listener.onResponse(info.setChangesLastDetectedAt(indexer.getChangesLastDetectedAt()));
                        }
                    },
                    listener::onFailure
                ));
    }

    // Here `failOnConflict` is usually true, except when the initial start is called when the task is assigned to the node
    synchronized void start(Long startingCheckpoint, boolean force, boolean failOnConflict, ActionListener<Response> listener) {
        logger.debug("[{}] start called with force [{}] and state [{}].", getTransformId(), force, getState());
        if (taskState.get() == TransformTaskState.FAILED && force == false) {
            listener.onFailure(new ElasticsearchStatusException(
                TransformMessages.getMessage(DATA_FRAME_CANNOT_START_FAILED_TRANSFORM,
                    getTransformId(),
                    stateReason.get()),
                RestStatus.CONFLICT));
            return;
        }
        // If we are already in a `STARTED` state, we should not attempt to call `.start` on the indexer again.
        if (taskState.get() == TransformTaskState.STARTED) {
            listener.onFailure(new ElasticsearchStatusException(
                "Cannot start transform [{}] as it is already STARTED.",
                RestStatus.CONFLICT,
                getTransformId()
            ));
            return;
        }
        if (getIndexer() == null) {
            // If our state is failed AND the indexer is null, the user needs to _stop?force=true so that the indexer gets
            // fully initialized.
            // If we are NOT failed, then we can assume that `start` was just called early in the process.
            String msg = taskState.get() == TransformTaskState.FAILED ?
                "It failed during the initialization process; force stop to allow reinitialization." :
                "Try again later.";
            listener.onFailure(new ElasticsearchStatusException("Task for transform [{}] not fully initialized. {}",
                RestStatus.CONFLICT,
                getTransformId(),
                msg));
            return;
        }
        // If we are already in a `STARTED` state, we should not attempt to call `.start` on the indexer again.
        if (taskState.get() == TransformTaskState.STARTED && failOnConflict) {
            listener.onFailure(new ElasticsearchStatusException(
                "Cannot start transform [{}] as it is already STARTED.",
                RestStatus.CONFLICT,
                getTransformId()
            ));
            return;
        }

        if (getIndexer().shouldStopAtCheckpoint()) {
                listener.onFailure(new ElasticsearchException("Cannot start task for data frame transform [{}], " +
                    "because it is stopping at the next checkpoint.", transform.getId()));
            return;
        }
        final IndexerState newState = getIndexer().start();
        if (Arrays.stream(RUNNING_STATES).noneMatch(newState::equals)) {
            listener.onFailure(new ElasticsearchException("Cannot start task for data frame transform [{}], because state was [{}]",
                transform.getId(), newState));
            return;
        }
        stateReason.set(null);
        taskState.set(TransformTaskState.STARTED);
        if (startingCheckpoint != null) {
            currentCheckpoint.set(startingCheckpoint);
        }

        final TransformState state = new TransformState(
            TransformTaskState.STARTED,
            IndexerState.STOPPED,
            getIndexer().getPosition(),
            currentCheckpoint.get(),
            null,
            getIndexer().getProgress());

        logger.info("[{}] updating state for data frame transform to [{}].", transform.getId(), state.toString());
        // Even though the indexer information is persisted to an index, we still need DataFrameTransformTaskState in the clusterstate
        // This keeps track of STARTED, FAILED, STOPPED
        // This is because a FAILED state can occur because we cannot read the config from the internal index, which would imply that
        //   we could not read the previous state information from said index.
        persistStateToClusterState(state, ActionListener.wrap(
            task -> {
                auditor.info(transform.getId(),
                    "Updated data frame transform state to [" + state.getTaskState() + "].");
                long now = System.currentTimeMillis();
                // kick off the indexer
                triggered(new Event(schedulerJobName(), now, now));
                registerWithSchedulerJob();
                listener.onResponse(new StartTransformTaskAction.Response(true));
            },
            exc -> {
                auditor.warning(transform.getId(),
                    "Failed to persist to cluster state while marking task as started. Failure: " + exc.getMessage());
                logger.error(new ParameterizedMessage("[{}] failed updating state to [{}].", getTransformId(), state), exc);
                getIndexer().stop();
                listener.onFailure(new ElasticsearchException("Error while updating state for data frame transform ["
                    + transform.getId() + "] to [" + state.getIndexerState() + "].", exc));
            }
        ));
    }
    /**
     * Start the background indexer and set the task's state to started
     * @param startingCheckpoint Set the current checkpoint to this value. If null the
     *                           current checkpoint is not set
     * @param force Whether to force start a failed task or not
     * @param listener Started listener
     */
    public synchronized void start(Long startingCheckpoint, boolean force, ActionListener<Response> listener) {
        start(startingCheckpoint, force, true, listener);
    }

    /**
     * This sets the flag for the task to stop at the next checkpoint.
     *
     * If first persists the flag to cluster state, and then mutates the local variable.
     *
     * It only persists to cluster state if the value is different than what is currently held in memory.
     * @param shouldStopAtCheckpoint whether or not we should stop at the next checkpoint or not
     * @param shouldStopAtCheckpointListener the listener to return to when we have persisted the updated value to the state index.
     */
    public synchronized void setShouldStopAtCheckpoint(boolean shouldStopAtCheckpoint,
                                                       ActionListener<Void> shouldStopAtCheckpointListener) {
        logger.debug("[{}] attempted to set task to stop at checkpoint [{}] with state [{}]",
            getTransformId(),
            shouldStopAtCheckpoint,
            getState());
        if (taskState.get() == TransformTaskState.STARTED == false ||
            getIndexer() == null ||
            getIndexer().shouldStopAtCheckpoint() == shouldStopAtCheckpoint ||
            getIndexer().getState() == IndexerState.STOPPED ||
            getIndexer().getState() == IndexerState.STOPPING) {
            shouldStopAtCheckpointListener.onResponse(null);
            return;
        }
        TransformState state = new TransformState(
            taskState.get(),
            indexer.get().getState(),
            indexer.get().getPosition(),
            currentCheckpoint.get(),
            stateReason.get(),
            getIndexer().getProgress(),
            null, //Node attributes
            shouldStopAtCheckpoint);
        getIndexer().doSaveState(state,
            ActionListener.wrap(
                r -> {
                    // We only want to update this internal value if it is persisted as such
                    getIndexer().setShouldStopAtCheckpoint(shouldStopAtCheckpoint);
                    logger.debug("[{}] successfully persisted should_stop_at_checkpoint update [{}]",
                        getTransformId(),
                        shouldStopAtCheckpoint);
                    shouldStopAtCheckpointListener.onResponse(null);
                },
                statsExc -> {
                    logger.warn("[{}] failed to persist should_stop_at_checkpoint update [{}]",
                        getTransformId(),
                        shouldStopAtCheckpoint);
                    shouldStopAtCheckpointListener.onFailure(statsExc);
                }
            ));
    }

    public synchronized void stop(boolean force, boolean shouldStopAtCheckpoint) {
        logger.debug("[{}] stop called with force [{}], shouldStopAtCheckpoint [{}], state [{}]",
            getTransformId(),
            force,
            shouldStopAtCheckpoint,
            getState());
        if (getIndexer() == null) {
            // If there is no indexer the task has not been triggered
            // but it still needs to be stopped and removed
            shutdown();
            return;
        }

        if (getIndexer().getState() == IndexerState.STOPPED || getIndexer().getState() == IndexerState.STOPPING) {
            return;
        }

        if (taskState.get() == TransformTaskState.FAILED && force == false) {
            throw new ElasticsearchStatusException(
                TransformMessages.getMessage(DATA_FRAME_CANNOT_STOP_FAILED_TRANSFORM,
                    getTransformId(),
                    stateReason.get()),
                RestStatus.CONFLICT);
        }

        stateReason.set(null);
        // No reason to keep it in the potentially failed state.
        // Since `doSaveState` is asynchronous, it is best to set the state as STARTED so that another `start` call cannot be
        // executed while we are wrapping up.
        boolean wasFailed = taskState.compareAndSet(TransformTaskState.FAILED, TransformTaskState.STARTED);

        // shouldStopAtCheckpoint only comes into play when onFinish is called (or doSaveState right after).
        // if it is false, stop immediately
        if (shouldStopAtCheckpoint == false ||
            // If state was in a failed state, we should stop immediately as we will never reach the next checkpoint
            wasFailed ||
            // If the indexerState is STARTED and it is on an initialRun, that means that the indexer has previously finished a checkpoint,
            // or has yet to even start one.
            // Either way, this means that we won't get to have onFinish called down stream (or at least won't for some time).
            (getIndexer().getState() == IndexerState.STARTED && getIndexer().initialRun())) {
            IndexerState state = getIndexer().stop();
            if (state == IndexerState.STOPPED) {
                getIndexer().onStop();
                getIndexer().doSaveState(state, getIndexer().getPosition(), () -> {});
            }
        }
    }

    @Override
    public synchronized void triggered(Event event) {
        // Ignore if event is not for this job
        if (event.getJobName().equals(schedulerJobName()) == false)  {
            return;
        }

        if (getIndexer() == null) {
            logger.warn("[{}] data frame task triggered with an unintialized indexer.", getTransformId());
            return;
        }

        if (taskState.get() == TransformTaskState.FAILED || taskState.get() == TransformTaskState.STOPPED) {
            logger.debug("[{}] schedule was triggered for transform but task is [{}]. Ignoring trigger.",
                getTransformId(),
                taskState.get());
            return;
        }

        // ignore trigger if indexer is running or completely stopped
        IndexerState indexerState = getIndexer().getState();
        if (IndexerState.INDEXING.equals(indexerState) ||
            IndexerState.STOPPING.equals(indexerState) ||
            IndexerState.STOPPED.equals(indexerState)) {
            logger.debug("[{}] indexer for transform has state [{}]. Ignoring trigger.", getTransformId(), indexerState);
            return;
        }

        logger.debug("[{}] data frame indexer schedule has triggered, state: [{}].", event.getJobName(), indexerState);

        // if it runs for the 1st time we just do it, if not we check for changes
        if (currentCheckpoint.get() == 0) {
            logger.debug("[{}] trigger initial run.", getTransformId());
            getIndexer().maybeTriggerAsyncJob(System.currentTimeMillis());
        } else if (getIndexer().isContinuous()) {
            getIndexer().maybeTriggerAsyncJob(System.currentTimeMillis());
        }
    }

    /**
     * Attempt to gracefully cleanup the data frame transform so it can be terminated.
     * This tries to remove the job from the scheduler and completes the persistent task
     */
    synchronized void shutdown() {
        deregisterSchedulerJob();
        markAsCompleted();
    }

    void persistStateToClusterState(TransformState state,
                                    ActionListener<PersistentTasksCustomMetaData.PersistentTask<?>> listener) {
        updatePersistentTaskState(state, ActionListener.wrap(
            success -> {
                logger.debug("[{}] successfully updated state for data frame transform to [{}].", transform.getId(), state.toString());
                listener.onResponse(success);
            },
            failure -> {
                logger.error(new ParameterizedMessage("[{}] failed to update cluster state for data frame transform.",
                    transform.getId()),
                    failure);
                listener.onFailure(failure);
            }
        ));
    }

    synchronized void markAsFailed(String reason, ActionListener<Void> listener) {
        // If we are already flagged as failed, this probably means that a second trigger started firing while we were attempting to
        // flag the previously triggered indexer as failed. Exit early as we are already flagged as failed.
        if (taskState.get() == TransformTaskState.FAILED) {
            logger.warn("[{}] is already failed but encountered new failure; reason [{}].", getTransformId(), reason);
            listener.onResponse(null);
            return;
        }
        // If the indexer is `STOPPING` this means that `DataFrameTransformTask#stop` was called previously, but something caused
        // the indexer to fail. Since `ClientDataFrameIndexer#doSaveState` will persist the state to the index once the indexer stops,
        // it is probably best to NOT change the internal state of the task and allow the normal stopping logic to continue.
        if (getIndexer() != null && getIndexer().getState() == IndexerState.STOPPING) {
            logger.info("[{}] attempt to fail transform with reason [{}] while it was stopping.", getTransformId(), reason);
            listener.onResponse(null);
            return;
        }
        // If we are stopped, this means that between the failure occurring and being handled, somebody called stop
        // We should just allow that stop to continue
        if (getIndexer() != null && getIndexer().getState() == IndexerState.STOPPED) {
            logger.info("[{}] encountered a failure but indexer is STOPPED; reason [{}].", getTransformId(), reason);
            listener.onResponse(null);
            return;
        }
        auditor.error(transform.getId(), reason);
        // We should not keep retrying. Either the task will be stopped, or started
        // If it is started again, it is registered again.
        deregisterSchedulerJob();
        // The idea of stopping at the next checkpoint is no longer valid. Since a failed task could potentially START again,
        // we should set this flag to false.
        if (getIndexer() != null) {
            getIndexer().setShouldStopAtCheckpoint(false);
        }
        // The end user should see that the task is in a failed state, and attempt to stop it again but with force=true
        taskState.set(TransformTaskState.FAILED);
        stateReason.set(reason);
        TransformState newState = getState();
        // Even though the indexer information is persisted to an index, we still need DataFrameTransformTaskState in the clusterstate
        // This keeps track of STARTED, FAILED, STOPPED
        // This is because a FAILED state could occur because we failed to read the config from the internal index, which would imply that
        //   we could not read the previous state information from said index.
        persistStateToClusterState(newState, ActionListener.wrap(
            r -> listener.onResponse(null),
            e -> {
                String msg = "Failed to persist to cluster state while marking task as failed with reason [" + reason + "].";
                auditor.warning(transform.getId(),
                    msg + " Failure: " + e.getMessage());
                logger.error(new ParameterizedMessage("[{}] {}", getTransformId(), msg),
                    e);
                listener.onFailure(e);
            }
        ));
    }

    /**
     * This is called when the persistent task signals that the allocated task should be terminated.
     * Termination in the task framework is essentially voluntary, as the allocated task can only be
     * shut down from the inside.
     */
    @Override
    public synchronized void onCancelled() {
        logger.info("[{}] received cancellation request for data frame transform, state: [{}].",
            getTransformId(),
            taskState.get());
        if (getIndexer() != null && getIndexer().abort()) {
            // there is no background transform running, we can shutdown safely
            shutdown();
        }
    }

    DataFrameTransformTask setNumFailureRetries(int numFailureRetries) {
        this.numFailureRetries = numFailureRetries;
        return this;
    }

    int getNumFailureRetries() {
        return numFailureRetries;
    }

    private void registerWithSchedulerJob() {
        schedulerEngine.register(this);
        final SchedulerEngine.Job schedulerJob = new SchedulerEngine.Job(schedulerJobName(), next());
        schedulerEngine.add(schedulerJob);
    }

    private void deregisterSchedulerJob() {
        schedulerEngine.remove(schedulerJobName());
        schedulerEngine.unregister(this);
    }

    private String schedulerJobName() {
        return DataFrameTransformTask.SCHEDULE_NAME + "_" + getTransformId();
    }

    private SchedulerEngine.Schedule next() {
        return (startTime, now) -> {
            TimeValue frequency = transform.getFrequency();
            return now + (frequency == null ? SCHEDULER_NEXT_MILLISECONDS : frequency.getMillis());
        };
    }

    synchronized void initializeIndexer(ClientDataFrameIndexerBuilder indexerBuilder) {
        indexer.set(indexerBuilder.build(this));
    }

    void updateSeqNoPrimaryTermAndIndex(SeqNoPrimaryTermAndIndex expectedValue, SeqNoPrimaryTermAndIndex newValue) {
        boolean updated = seqNoPrimaryTermAndIndex.compareAndSet(expectedValue, newValue);
        // This should never happen. We ONLY ever update this value if at initialization or we just finished updating the document
        // famous last words...
        assert updated :
            "[" + getTransformId() + "] unexpected change to seqNoPrimaryTermAndIndex.";
    }

    @Nullable
    SeqNoPrimaryTermAndIndex getSeqNoPrimaryTermAndIndex() {
        return seqNoPrimaryTermAndIndex.get();
    }

    ThreadPool getThreadPool() {
        return threadPool;
    }

    TransformTaskState getTaskState() {
        return taskState.get();
    }

    void setStateReason(String reason) {
        stateReason.set(reason);
    }

    String getStateReason() {
        return stateReason.get();
    }
}
