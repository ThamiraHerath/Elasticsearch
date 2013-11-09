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

package org.elasticsearch.action.admin.cluster.repositories.delete;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.master.AcknowledgedRequestBuilder;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.client.internal.InternalClusterAdminClient;

/**
 * Builder for unregister repository request
 */
public class DeleteRepositoryRequestBuilder extends AcknowledgedRequestBuilder<DeleteRepositoryRequest, DeleteRepositoryResponse, DeleteRepositoryRequestBuilder> {

    /**
     * Constructs unregister repository request builder
     *
     * @param clusterAdminClient cluster admin client
     */
    public DeleteRepositoryRequestBuilder(ClusterAdminClient clusterAdminClient) {
        super((InternalClusterAdminClient) clusterAdminClient, new DeleteRepositoryRequest());
    }

    /**
     * Constructs unregister repository request builder with specified repository name
     *
     * @param clusterAdminClient cluster adming client
     */
    public DeleteRepositoryRequestBuilder(ClusterAdminClient clusterAdminClient, String name) {
        super((InternalClusterAdminClient) clusterAdminClient, new DeleteRepositoryRequest(name));
    }

    /**
     * Sets the repository name
     *
     * @param name the repository name
     */
    public DeleteRepositoryRequestBuilder setName(String name) {
        request.name(name);
        return this;
    }

    @Override
    protected void doExecute(ActionListener<DeleteRepositoryResponse> listener) {
        ((ClusterAdminClient) client).deleteRepository(request, listener);
    }
}
