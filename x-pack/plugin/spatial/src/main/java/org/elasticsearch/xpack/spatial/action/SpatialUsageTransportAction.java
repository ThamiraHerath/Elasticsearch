/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.spatial.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.protocol.xpack.XPackUsageRequest;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureAction;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureResponse;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureTransportAction;
import org.elasticsearch.xpack.core.spatial.SpatialFeatureSetUsage;
import org.elasticsearch.xpack.core.spatial.action.SpatialStatsAction;

import java.util.Collections;

public class SpatialUsageTransportAction extends XPackUsageFeatureTransportAction {

    private final XPackLicenseState licenseState;
    private final Client client;

    @Inject
    public SpatialUsageTransportAction(TransportService transportService, ClusterService clusterService, ThreadPool threadPool,
                                       ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                       XPackLicenseState licenseState, Client client) {
        super(XPackUsageFeatureAction.SPATIAL.name(), transportService, clusterService,
            threadPool, actionFilters, indexNameExpressionResolver);
        this.licenseState = licenseState;
        this.client = client;
    }

    @Override
    protected void masterOperation(Task task, XPackUsageRequest request, ClusterState state,
                                   ActionListener<XPackUsageFeatureResponse> listener) {
        if (licenseState.isAllowed(XPackLicenseState.Feature.SPATIAL)) {
            SpatialStatsAction.Request statsRequest = new SpatialStatsAction.Request();
            statsRequest.setParentTask(clusterService.localNode().getId(), task.getId());
            client.execute(SpatialStatsAction.INSTANCE, statsRequest, ActionListener.wrap(r ->
                    listener.onResponse(new XPackUsageFeatureResponse(new SpatialFeatureSetUsage(true, true, r))),
                listener::onFailure));
        } else {
            SpatialFeatureSetUsage usage = new SpatialFeatureSetUsage(false, true,
                new SpatialStatsAction.Response(state.getClusterName(), Collections.emptyList(), Collections.emptyList()));
            listener.onResponse(new XPackUsageFeatureResponse(usage));
        }
    }
}
