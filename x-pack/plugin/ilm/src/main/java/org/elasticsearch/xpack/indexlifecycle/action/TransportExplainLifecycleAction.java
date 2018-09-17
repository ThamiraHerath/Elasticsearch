/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.indexlifecycle.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.info.TransportClusterInfoAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.indexlifecycle.ExplainLifecycleRequest;
import org.elasticsearch.xpack.core.indexlifecycle.ExplainLifecycleResponse;
import org.elasticsearch.xpack.core.indexlifecycle.LifecycleExecutionState;
import org.elasticsearch.xpack.core.indexlifecycle.IndexLifecycleExplainResponse;
import org.elasticsearch.xpack.core.indexlifecycle.LifecycleSettings;
import org.elasticsearch.xpack.core.indexlifecycle.action.ExplainLifecycleAction;

import java.util.HashMap;
import java.util.Map;

public class TransportExplainLifecycleAction
        extends TransportClusterInfoAction<ExplainLifecycleRequest, ExplainLifecycleResponse> {

    @Inject
    public TransportExplainLifecycleAction(Settings settings, TransportService transportService, ClusterService clusterService,
            ThreadPool threadPool, ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, ExplainLifecycleAction.NAME, transportService, clusterService, threadPool, actionFilters,
                ExplainLifecycleRequest::new, indexNameExpressionResolver);
    }

    @Override
    protected ExplainLifecycleResponse newResponse() {
        return new ExplainLifecycleResponse();
    }

    @Override
    protected String executor() {
        // very lightweight operation, no need to fork
        return ThreadPool.Names.SAME;
    }

    @Override
    protected ClusterBlockException checkBlock(ExplainLifecycleRequest request, ClusterState state) {
        return state.blocks().indicesBlockedException(ClusterBlockLevel.METADATA_READ,
                indexNameExpressionResolver.concreteIndexNames(state, request));
    }

    @Override
    protected void doMasterOperation(ExplainLifecycleRequest request, String[] concreteIndices, ClusterState state,
            ActionListener<ExplainLifecycleResponse> listener) {
        Map<String, IndexLifecycleExplainResponse> indexReponses = new HashMap<>();
        for (String index : concreteIndices) {
            IndexMetaData idxMetadata = state.metaData().index(index);
            Settings idxSettings = idxMetadata.getSettings();
            LifecycleExecutionState lifecycleState = LifecycleExecutionState.fromIndexMetadata(idxMetadata);
            String policyName = LifecycleSettings.LIFECYCLE_NAME_SETTING.get(idxSettings);
            final IndexLifecycleExplainResponse indexResponse;
            if (Strings.hasLength(policyName)) {
                indexResponse = IndexLifecycleExplainResponse.newManagedIndexResponse(index, policyName,
                    LifecycleSettings.LIFECYCLE_SKIP_SETTING.get(idxSettings),
                    lifecycleState.getIndexCreationDate() == null ? -1 : lifecycleState.getIndexCreationDate(),
                    lifecycleState.getPhase() == null ? "" : lifecycleState.getPhase(),
                    lifecycleState.getAction() == null ? "" : lifecycleState.getAction(),
                    lifecycleState.getStep() == null ? "" : lifecycleState.getStep(),
                    lifecycleState.getFailedStep() == null ? "" : lifecycleState.getFailedStep(),
                    lifecycleState.getPhaseTime() == null ? -1 : lifecycleState.getPhaseTime(),
                    lifecycleState.getActionTime() == null ? -1 : lifecycleState.getActionTime(),
                    lifecycleState.getStepTime() == null ? -1 : lifecycleState.getStepTime(),
                    new BytesArray(lifecycleState.getStepInfo() == null ? "" : lifecycleState.getStepInfo()));
            } else {
                indexResponse = IndexLifecycleExplainResponse.newUnmanagedIndexResponse(index);
            }
            indexReponses.put(indexResponse.getIndex(), indexResponse);
        }
        listener.onResponse(new ExplainLifecycleResponse(indexReponses));
    }

}
