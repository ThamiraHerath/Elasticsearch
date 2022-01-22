/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.desirednodes;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.desirednodes.DesiredNodesSettingsValidator;
import org.elasticsearch.cluster.metadata.DesiredNodes;
import org.elasticsearch.cluster.metadata.DesiredNodesMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Locale;

import static java.lang.String.format;

public class TransportUpdateDesiredNodesAction extends TransportMasterNodeAction<UpdateDesiredNodesRequest, AcknowledgedResponse> {
    private final DesiredNodesSettingsValidator settingsValidator;

    @Inject
    public TransportUpdateDesiredNodesAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        DesiredNodesSettingsValidator settingsValidator
    ) {
        super(
            UpdateDesiredNodesAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            UpdateDesiredNodesRequest::new,
            indexNameExpressionResolver,
            AcknowledgedResponse::readFrom,
            ThreadPool.Names.SAME
        );
        this.settingsValidator = settingsValidator;
    }

    @Override
    protected ClusterBlockException checkBlock(UpdateDesiredNodesRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }

    @Override
    protected void masterOperation(
        Task task,
        UpdateDesiredNodesRequest request,
        ClusterState state,
        ActionListener<AcknowledgedResponse> listener
    ) throws Exception {
        try {
            DesiredNodes proposedDesiredNodes = new DesiredNodes(request.getHistoryID(), request.getVersion(), request.getNodes());
            settingsValidator.validate(proposedDesiredNodes);

            clusterService.submitStateUpdateTask("update-desired-nodes", new AckedClusterStateUpdateTask(Priority.HIGH, request, listener) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return updateDesiredNodes(currentState, request);
                }
            }, ClusterStateTaskExecutor.unbatched());
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    static ClusterState updateDesiredNodes(ClusterState currentState, UpdateDesiredNodesRequest request) {
        DesiredNodesMetadata desiredNodesMetadata = getDesiredNodesMetadata(currentState);
        DesiredNodes currentDesiredNodes = desiredNodesMetadata.getCurrentDesiredNodes();
        DesiredNodes proposedDesiredNodes = new DesiredNodes(request.getHistoryID(), request.getVersion(), request.getNodes());

        if (currentDesiredNodes != null) {
            if (currentDesiredNodes.equals(proposedDesiredNodes)) {
                return currentState;
            }

            if (currentDesiredNodes.hasSameVersion(proposedDesiredNodes) && currentDesiredNodes.equals(proposedDesiredNodes) == false) {
                throw new IllegalArgumentException(
                    format(
                        Locale.ROOT,
                        "Desired nodes with history [%s] and version [%d] already exists with a different definition",
                        currentDesiredNodes.historyID(),
                        currentDesiredNodes.version()
                    )
                );
            }

            if (currentDesiredNodes.isSupersededBy(proposedDesiredNodes) == false) {
                throw new ConflictException(
                    format(
                        Locale.ROOT,
                        "version [%d] has been superseded by version [%d] for history [%s]",
                        proposedDesiredNodes.version(),
                        currentDesiredNodes.version(),
                        currentDesiredNodes.historyID()
                    )
                );
            }
        }

        return currentState.copyAndUpdateMetadata(
            metadata -> metadata.putCustom(DesiredNodesMetadata.TYPE, new DesiredNodesMetadata(proposedDesiredNodes))
        );
    }

    private static DesiredNodesMetadata getDesiredNodesMetadata(ClusterState currentState) {
        if (currentState.metadata().custom(DesiredNodesMetadata.TYPE) != null) {
            return currentState.metadata().custom(DesiredNodesMetadata.TYPE);
        } else {
            return DesiredNodesMetadata.EMPTY;
        }
    }

    public static class ConflictException extends ElasticsearchException {
        public ConflictException(String msg, Object... args) {
            super(msg, args);
        }

        @Override
        public RestStatus status() {
            return RestStatus.CONFLICT;
        }
    }
}
