/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.protocol.xpack.XPackUsageRequest;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.application.search.SearchApplicationIndexService;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureAction;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureResponse;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureTransportAction;
import org.elasticsearch.xpack.core.application.EnterpriseSearchFeatureSetUsage;

import java.util.Map;

public class EnterpriseSearchUsageTransportAction extends XPackUsageFeatureTransportAction {
    private final SearchApplicationIndexService searchApplicationIndexService;

    @Inject
    public EnterpriseSearchUsageTransportAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        SearchApplicationIndexService searchApplicationIndexService
    ) {
        super(
            XPackUsageFeatureAction.ENTERPRISE_SEARCH.name(),
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            indexNameExpressionResolver
        );
        this.searchApplicationIndexService = searchApplicationIndexService;
    }

    @Override
    protected void masterOperation(
        Task task,
        XPackUsageRequest request,
        ClusterState state,
        ActionListener<XPackUsageFeatureResponse> listener
    ) throws Exception {
        searchApplicationIndexService.listSearchApplication("*", 0, 0, listener.delegateFailure((l, searchApplicationResult) -> {
            l.onResponse(
                new XPackUsageFeatureResponse(
                    new EnterpriseSearchFeatureSetUsage(true, true, Map.of("count", searchApplicationResult.totalResults()))
                )
            );
        }));
    }
}
