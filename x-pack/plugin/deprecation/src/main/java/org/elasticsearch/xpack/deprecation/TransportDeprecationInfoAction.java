/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.deprecation;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeReadAction;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.XPackField;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.deprecation.DeprecationInfoAction;
import org.elasticsearch.xpack.core.deprecation.NodesDeprecationCheckAction;
import org.elasticsearch.xpack.core.deprecation.NodesDeprecationCheckRequest;
import org.elasticsearch.xpack.core.ml.action.GetDatafeedsAction;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedConfig;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.core.ClientHelper.DEPRECATION_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;
import static org.elasticsearch.xpack.deprecation.DeprecationChecks.CLUSTER_SETTINGS_CHECKS;
import static org.elasticsearch.xpack.deprecation.DeprecationChecks.INDEX_SETTINGS_CHECKS;
import static org.elasticsearch.xpack.deprecation.DeprecationChecks.ML_SETTINGS_CHECKS;

public class TransportDeprecationInfoAction extends TransportMasterNodeReadAction<DeprecationInfoAction.Request,
        DeprecationInfoAction.Response> {

    private final XPackLicenseState licenseState;
    private final NodeClient client;
    private final IndexNameExpressionResolver indexNameExpressionResolver;
    private final Settings settings;

    @Inject
    public TransportDeprecationInfoAction(Settings settings, TransportService transportService,
                                          ClusterService clusterService, ThreadPool threadPool,
                                          ActionFilters actionFilters,
                                          IndexNameExpressionResolver indexNameExpressionResolver,
                                          XPackLicenseState licenseState, NodeClient client) {
        super(settings, DeprecationInfoAction.NAME, transportService, clusterService, threadPool, actionFilters,
                indexNameExpressionResolver, DeprecationInfoAction.Request::new);
        this.licenseState = licenseState;
        this.client = client;
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        this.settings = settings;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.GENERIC;
    }

    @Override
    protected DeprecationInfoAction.Response newResponse() {
        return new DeprecationInfoAction.Response();
    }

    @Override
    protected ClusterBlockException checkBlock(DeprecationInfoAction.Request request, ClusterState state) {
        // Cluster is not affected but we look up repositories in metadata
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
    }

    @Override
    protected final void masterOperation(final DeprecationInfoAction.Request request, ClusterState state,
                                         final ActionListener<DeprecationInfoAction.Response> listener) {
        if (licenseState.isDeprecationAllowed()) {
            NodesDeprecationCheckRequest nodeDepReq = new NodesDeprecationCheckRequest("_all");
            executeAsyncWithOrigin(client, DEPRECATION_ORIGIN, NodesDeprecationCheckAction.INSTANCE, nodeDepReq,
                ActionListener.wrap(response -> {
                if (response.hasFailures()) {
                    List<String> failedNodeIds = response.failures().stream()
                        .map(failure -> failure.nodeId() + ": " + failure.getMessage())
                        .collect(Collectors.toList());
                    logger.warn("nodes failed to run deprecation checks: {}", failedNodeIds);
                    for (FailedNodeException failure : response.failures()) {
                        logger.debug("node {} failed to run deprecation checks: {}", failure.nodeId(), failure);
                    }
                }
                getDatafeedConfigs(ActionListener.wrap(
                    datafeeds -> {
                        listener.onResponse(
                            DeprecationInfoAction.Response.from(state, indexNameExpressionResolver,
                                request.indices(), request.indicesOptions(), datafeeds,
                                response, INDEX_SETTINGS_CHECKS, CLUSTER_SETTINGS_CHECKS,
                                ML_SETTINGS_CHECKS));
                    },
                    listener::onFailure
                ));

            }, listener::onFailure));
        } else {
            listener.onFailure(LicenseUtils.newComplianceException(XPackField.DEPRECATION));
        }
    }

    private void getDatafeedConfigs(ActionListener<List<DatafeedConfig>> listener) {
        if (XPackSettings.MACHINE_LEARNING_ENABLED.get(settings) == false) {
            listener.onResponse(Collections.emptyList());
        } else {
            executeAsyncWithOrigin(client, DEPRECATION_ORIGIN, GetDatafeedsAction.INSTANCE,
                    new GetDatafeedsAction.Request(GetDatafeedsAction.ALL), ActionListener.wrap(
                            datafeedsResponse -> listener.onResponse(datafeedsResponse.getResponse().results()),
                            listener::onFailure
                    ));
        }
    }
}
