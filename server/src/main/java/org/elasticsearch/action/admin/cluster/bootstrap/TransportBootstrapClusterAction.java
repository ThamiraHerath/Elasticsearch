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
package org.elasticsearch.action.admin.cluster.bootstrap;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.coordination.Coordinator;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.TransportService;

public class TransportBootstrapClusterAction extends HandledTransportAction<BootstrapClusterRequest, BootstrapClusterResponse> {

    @Nullable // TODO make this not nullable
    private final Coordinator coordinator;
    private final TransportService transportService;

    @Inject
    public TransportBootstrapClusterAction(Settings settings, ActionFilters actionFilters, TransportService transportService,
                                           Discovery discovery) {
        super(settings, BootstrapClusterAction.NAME, transportService, actionFilters, BootstrapClusterRequest::new);
        this.transportService = transportService;
        if (discovery instanceof Coordinator) {
            coordinator = (Coordinator) discovery;
        } else {
            coordinator = null;
        }
    }

    @Override
    protected void doExecute(Task task, BootstrapClusterRequest request, ActionListener<BootstrapClusterResponse> listener) {
        if (coordinator == null) { // TODO remove when not nullable
            throw new IllegalStateException("cluster bootstrapping is not supported by this discovery type");
        }

        final DiscoveryNode localNode = transportService.getLocalNode();
        assert localNode != null;
        if (localNode.isMasterNode() == false) {
            throw new ElasticsearchException("this node is not master-eligible");
        }

        transportService.getThreadPool().generic().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onResponse(new BootstrapClusterResponse(
                        coordinator.setInitialConfiguration(request.getBootstrapConfiguration()) == false));
                } catch (Exception e) {
                    listener.onFailure(e);
                }
            }

            @Override
            public String toString() {
                return "setting initial configuration with " + request;
            }
        });
    }
}
