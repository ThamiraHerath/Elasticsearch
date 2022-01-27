/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.desirednodes;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.desirednodes.DesiredNodesSettingsValidator;
import org.elasticsearch.cluster.desirednodes.VersionConflictException;
import org.elasticsearch.cluster.metadata.DesiredNodes;
import org.elasticsearch.cluster.metadata.DesiredNodesMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Locale;

import static java.lang.String.format;

public class TransportUpdateDesiredNodesAction extends TransportMasterNodeAction<UpdateDesiredNodesRequest, UpdateDesiredNodesResponse> {
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
            UpdateDesiredNodesResponse::new,
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
        ActionListener<UpdateDesiredNodesResponse> listener
    ) throws Exception {
        try {
            DesiredNodes proposedDesiredNodes = new DesiredNodes(request.getHistoryID(), request.getVersion(), request.getNodes());
            settingsValidator.validate(proposedDesiredNodes);

            clusterService.submitStateUpdateTask("update-desired-nodes", new AckedClusterStateUpdateTask(Priority.HIGH, request, listener) {
                private volatile String newHistoryId;

                @Override
                public ClusterState execute(ClusterState currentState) {
                    Tuple<ClusterState, String> updatedClusterStateAndNewHistoryId = updateDesiredNodes(currentState, request);
                    newHistoryId = updatedClusterStateAndNewHistoryId.v2();
                    return updatedClusterStateAndNewHistoryId.v1();
                }

                @Override
                protected UpdateDesiredNodesResponse newResponse(boolean acknowledged) {
                    return new UpdateDesiredNodesResponse(acknowledged, newHistoryId);
                }
            }, ClusterStateTaskExecutor.unbatched());
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    static Tuple<ClusterState, String> updateDesiredNodes(ClusterState currentState, UpdateDesiredNodesRequest request) {
        DesiredNodesMetadata desiredNodesMetadata = getDesiredNodesMetadata(currentState);
        DesiredNodes latestDesiredNodes = desiredNodesMetadata.getLatestDesiredNodes();
        DesiredNodes proposedDesiredNodes = new DesiredNodes(request.getHistoryID(), request.getVersion(), request.getNodes());

        if (latestDesiredNodes != null) {
            if (latestDesiredNodes.equals(proposedDesiredNodes)) {
                return Tuple.tuple(currentState, null);
            }

            if (latestDesiredNodes.hasSameVersion(proposedDesiredNodes)) {
                throw new IllegalArgumentException(
                    format(
                        Locale.ROOT,
                        "Desired nodes with history [%s] and version [%d] already exists with a different definition",
                        latestDesiredNodes.historyID(),
                        latestDesiredNodes.version()
                    )
                );
            }

            if (latestDesiredNodes.isSupersededBy(proposedDesiredNodes) == false) {
                throw new VersionConflictException(
                    "version [{}] has been superseded by version [{}] for history [{}]",
                    proposedDesiredNodes.version(),
                    latestDesiredNodes.version(),
                    latestDesiredNodes.historyID()
                );
            }
        }

        final ClusterState updatedClusterState = currentState.copyAndUpdateMetadata(
            metadata -> metadata.putCustom(DesiredNodesMetadata.TYPE, new DesiredNodesMetadata(proposedDesiredNodes))
        );

        return Tuple.tuple(updatedClusterState, newHistoryId(latestDesiredNodes, proposedDesiredNodes));
    }

    @Nullable
    private static String newHistoryId(DesiredNodes latestDesiredNodes, DesiredNodes proposedDesiredNodes) {
        if (latestDesiredNodes == null || latestDesiredNodes.historyID().equals(proposedDesiredNodes.historyID()) == false) {
            return proposedDesiredNodes.historyID();
        } else {
            return null;
        }
    }

    private static DesiredNodesMetadata getDesiredNodesMetadata(ClusterState currentState) {
        if (currentState.metadata().custom(DesiredNodesMetadata.TYPE) != null) {
            return currentState.metadata().custom(DesiredNodesMetadata.TYPE);
        } else {
            return DesiredNodesMetadata.EMPTY;
        }
    }
}
