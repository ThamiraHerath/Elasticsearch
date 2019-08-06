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
package org.elasticsearch.index.reindex;

import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.AutoCreateIndex;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.function.Predicate;

public class TransportStartReindexJobAction extends HandledTransportAction<StartReindexJobAction.Request, StartReindexJobAction.Response> {

    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final PersistentTasksService persistentTasksService;
    private final ReindexValidator reindexValidator;
    private final ReindexIndexClient reindexIndexClient;

    @Inject
    public TransportStartReindexJobAction(Settings settings, Client client, TransportService transportService, ThreadPool threadPool,
                                          ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                          ClusterService clusterService, PersistentTasksService persistentTasksService,
                                          AutoCreateIndex autoCreateIndex, NamedXContentRegistry xContentRegistry) {
        super(StartReindexJobAction.NAME, transportService, actionFilters, StartReindexJobAction.Request::new);
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.reindexValidator = new ReindexValidator(settings, clusterService, indexNameExpressionResolver, autoCreateIndex);
        this.persistentTasksService = persistentTasksService;
        this.reindexIndexClient = new ReindexIndexClient(client, xContentRegistry);
    }

    @Override
    protected void doExecute(Task task, StartReindexJobAction.Request request, ActionListener<StartReindexJobAction.Response> listener) {
        try {
            reindexValidator.initialValidation(request.getReindexRequest());
        } catch (Exception e) {
            listener.onFailure(e);
        }

        String generatedId = UUIDs.randomBase64UUID();

        // In the current implementation, we only need to store task results if we do not wait for completion
        boolean storeTaskResult = request.getWaitForCompletion() == false;
        ReindexJob job = new ReindexJob(storeTaskResult, threadPool.getThreadContext().getHeaders());

        ClusterState clusterState = clusterService.state();
        boolean reindexIndexExists = clusterState.routingTable().hasIndex(ReindexIndexClient.REINDEX_INDEX);

        ReindexTaskIndexState reindexState = new ReindexTaskIndexState(request.getReindexRequest());
        reindexIndexClient.createReindexTaskDoc(generatedId, reindexState, reindexIndexExists, new ActionListener<>() {
            @Override
            public void onResponse(Void v) {
                // TODO: Task name
                persistentTasksService.sendStartRequest(generatedId, ReindexTask.NAME, job, new ActionListener<>() {
                    @Override
                    public void onResponse(PersistentTasksCustomMetaData.PersistentTask<ReindexJob> persistentTask) {
                        if (request.getWaitForCompletion()) {
                            waitForReindexDone(persistentTask.getId(), listener);
                        } else {
                            waitForReindexTask(persistentTask.getId(), listener);
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        assert e instanceof ResourceAlreadyExistsException == false : "UUID generation should not produce conflicts";
                        listener.onFailure(e);
                    }
                });
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    private void waitForReindexDone(String taskId, ActionListener<StartReindexJobAction.Response> listener) {
        // TODO: Configurable timeout?
        persistentTasksService.waitForPersistentTaskCondition(taskId, new ReindexPredicate(true), null,
            new PersistentTasksService.WaitForPersistentTaskListener<ReindexJob>() {
                @Override
                public void onResponse(PersistentTasksCustomMetaData.PersistentTask<ReindexJob> task) {
                    ReindexJobState state = (ReindexJobState) task.getState();
                    reindexIndexClient.getReindexTaskDoc(taskId, new ActionListener<>() {
                        @Override
                        public void onResponse(ReindexTaskIndexState reindexState) {
                            if (reindexState.getException() == null) {
                                listener.onResponse(new StartReindexJobAction.Response(taskId, reindexState.getReindexResponse()));
                            } else {
                                listener.onFailure(reindexState.getException());
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            listener.onFailure(e);
                        }
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
    }

    private void waitForReindexTask(String taskId, ActionListener<StartReindexJobAction.Response> listener) {
        // TODO: Configurable timeout?
        persistentTasksService.waitForPersistentTaskCondition(taskId, new ReindexPredicate(false), null,
            new PersistentTasksService.WaitForPersistentTaskListener<ReindexJob>() {
                @Override
                public void onResponse(PersistentTasksCustomMetaData.PersistentTask<ReindexJob> task) {
                    ReindexJobState state = (ReindexJobState) task.getState();
                    listener.onResponse(new StartReindexJobAction.Response(state.getEphemeralTaskId().toString()));
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
    }

    private static class ReindexPredicate implements Predicate<PersistentTasksCustomMetaData.PersistentTask<?>> {

        private boolean waitForDone;

        private ReindexPredicate(boolean waitForDone) {
            this.waitForDone = waitForDone;
        }

        @Override
        public boolean test(PersistentTasksCustomMetaData.PersistentTask<?> persistentTask) {
            if (persistentTask == null) {
                return false;
            }
            PersistentTasksCustomMetaData.Assignment assignment = persistentTask.getAssignment();
            if (assignment == null || assignment.isAssigned() == false) {
                return false;
            }

            ReindexJobState state = (ReindexJobState) persistentTask.getState();


            if (waitForDone == false) {
                return isStarted(state);
            } else {
                return isDone(state);
            }
        }

        private boolean isStarted(ReindexJobState state) {
            return state != null;
        }

        private boolean isDone(ReindexJobState state) {
            return state != null && state.isDone();
        }
    }
}
