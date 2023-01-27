/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.transform.action;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.TaskOperationFailure;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.tasks.TransportTasksAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.security.SecurityContext;
import org.elasticsearch.xpack.core.transform.action.TriggerTransformAction;
import org.elasticsearch.xpack.core.transform.action.TriggerTransformAction.Request;
import org.elasticsearch.xpack.core.transform.action.TriggerTransformAction.Response;
import org.elasticsearch.xpack.core.transform.transforms.TransformState;
import org.elasticsearch.xpack.core.transform.transforms.TransformTaskState;
import org.elasticsearch.xpack.transform.TransformServices;
import org.elasticsearch.xpack.transform.transforms.TransformTask;
import org.elasticsearch.xpack.transform.transforms.scheduling.TransformScheduler;

import java.util.List;

import static org.elasticsearch.core.Strings.format;
import static org.elasticsearch.xpack.transform.utils.SecondaryAuthorizationUtils.useSecondaryAuthIfAvailable;

public class TransportTriggerTransformAction extends TransportTasksAction<TransformTask, Request, Response, Response> {

    private static final Logger logger = LogManager.getLogger(TransportTriggerTransformAction.class);
    private final TransformScheduler transformScheduler;
    private final SecurityContext securityContext;

    @Inject
    public TransportTriggerTransformAction(
        Settings settings,
        TransportService transportService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        ClusterService clusterService,
        TransformServices transformServices
    ) {
        super(
            TriggerTransformAction.NAME,
            clusterService,
            transportService,
            actionFilters,
            Request::new,
            Response::new,
            Response::new,
            ThreadPool.Names.SAME
        );

        this.transformScheduler = transformServices.getScheduler();
        this.securityContext = XPackSettings.SECURITY_ENABLED.get(settings)
            ? new SecurityContext(settings, threadPool.getThreadContext())
            : null;
    }

    @Override
    protected void doExecute(Task task, Request request, ActionListener<Response> listener) {
        final ClusterState clusterState = clusterService.state();
        XPackPlugin.checkReadyForXPackCustomMetadata(clusterState);

        useSecondaryAuthIfAvailable(securityContext, () -> {
            PersistentTasksCustomMetadata.PersistentTask<?> transformTask = TransformTask.getTransformTask(request.getId(), clusterState);

            // to send a request to trigger the transform at runtime, several requirements must be met:
            // - transform must be running, meaning a task exists
            // - transform is not failed (stopped transforms do not have a task)
            if (transformTask != null
                && transformTask.isAssigned()
                && transformTask.getState() instanceof TransformState
                && ((TransformState) transformTask.getState()).getTaskState() != TransformTaskState.FAILED) {

                ActionListener<Response> taskTriggerListener = ActionListener.wrap(listener::onResponse, e -> {
                    // benign: A transform might have been stopped meanwhile, this is not a problem
                    if (e instanceof TransformTaskDisappearedDuringTriggerException) {
                        logger.debug("[{}] transform task disappeared during trigger, ignoring", request.getId());
                        listener.onResponse(Response.TRUE);
                        return;
                    }
                    if (e instanceof TransformTaskTriggerException) {
                        logger.warn(() -> format("[%s] failed to trigger running transform.", request.getId()), e);
                        listener.onResponse(Response.TRUE);
                        return;
                    }
                    listener.onFailure(e);
                });
                request.setNodes(transformTask.getExecutorNode());
                super.doExecute(task, request, taskTriggerListener);
            } else {
                listener.onResponse(Response.TRUE);
            }
        });
    }

    @Override
    protected void taskOperation(Task actionTask, Request request, TransformTask transformTask, ActionListener<Response> listener) {
        transformScheduler.trigger(request.getId());
        listener.onResponse(Response.TRUE);
    }

    @Override
    protected Response newResponse(
        Request request,
        List<Response> tasks,
        List<TaskOperationFailure> taskOperationFailures,
        List<FailedNodeException> failedNodeExceptions
    ) {
        if (tasks.isEmpty()) {
            if (taskOperationFailures.isEmpty() == false) {
                throw new TransformTaskTriggerException("Failed to trigger running transform.", taskOperationFailures.get(0).getCause());
            } else if (failedNodeExceptions.isEmpty() == false) {
                throw new TransformTaskTriggerException("Failed to trigger running transform.", failedNodeExceptions.get(0));
            } else {
                throw new TransformTaskDisappearedDuringTriggerException("Could not trigger running transform as it has been stopped.");
            }
        }
        return tasks.get(0);
    }

    private static class TransformTaskTriggerException extends ElasticsearchException {
        TransformTaskTriggerException(String msg, Throwable cause, Object... args) {
            super(msg, cause, args);
        }
    }

    private static class TransformTaskDisappearedDuringTriggerException extends ElasticsearchException {
        TransformTaskDisappearedDuringTriggerException(String msg) {
            super(msg);
        }
    }
}
