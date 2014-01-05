/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.action.admin.indices.settings.get;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.master.TransportMasterNodeOperationAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Map;

/**
 */
public class TransportGetSettingsAction extends TransportMasterNodeOperationAction<GetSettingsRequest, GetSettingsResponse> {

    private final SettingsFilter settingsFilter;
    private final ClusterService clusterService;

    @Inject
    public TransportGetSettingsAction(Settings settings, TransportService transportService, ClusterService clusterService,
                                      ThreadPool threadPool, SettingsFilter settingsFilter) {
        super(settings, transportService, clusterService, threadPool);
        this.settingsFilter = settingsFilter;
        this.clusterService = clusterService;
    }

    @Override
    protected String transportAction() {
        return GetSettingsAction.NAME;
    }

    @Override
    protected String executor() {
        // Very lightweight operation
        return ThreadPool.Names.SAME;
    }

    @Override
    protected GetSettingsRequest newRequest() {
        return new GetSettingsRequest();
    }

    @Override
    protected GetSettingsResponse newResponse() {
        return new GetSettingsResponse();
    }

    @Override
    protected void processBeforeProcess(GetSettingsRequest request, ClusterState clusterState) {
        request.indices(clusterState.metaData().concreteIndices(request.indices(), request.indicesOptions()));
    }

    @Override
    protected void masterOperation(GetSettingsRequest request, ClusterState state, ActionListener<GetSettingsResponse> listener) throws ElasticSearchException {
        ImmutableOpenMap.Builder<String, Settings> indexToSettingsBuilder = ImmutableOpenMap.builder();
        for (String concreteIndex : request.indices()) {
            IndexMetaData indexMetaData = state.getMetaData().index(concreteIndex);
            if (indexMetaData == null) {
                continue;
            }

            Settings settings = settingsFilter.filterSettings(indexMetaData.settings());
            if (request.prefix() != null) {
                ImmutableSettings.Builder settingsBuilder = ImmutableSettings.builder();
                for (Map.Entry<String, String> entry : settings.getAsMap().entrySet()) {
                    if (entry.getKey().startsWith(request.prefix())) {
                        settingsBuilder.put(entry.getKey(), entry.getValue());
                    }
                }
                settings = settingsBuilder.build();
            }
            indexToSettingsBuilder.put(concreteIndex, settings);
        }
        listener.onResponse(new GetSettingsResponse(indexToSettingsBuilder.build()));
    }
}
