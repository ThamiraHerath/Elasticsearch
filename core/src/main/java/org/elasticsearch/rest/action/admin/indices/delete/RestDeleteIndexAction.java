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

package org.elasticsearch.rest.action.admin.indices.delete;

import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Strings;
import org.elasticsearch.rest.BaseStandardRegistrationsRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestGlobalContext;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.support.AcknowledgedRestListener;

import static org.elasticsearch.rest.RestRequest.Method.DELETE;

/**
 *
 */
public class RestDeleteIndexAction extends BaseStandardRegistrationsRestHandler {
    public RestDeleteIndexAction(RestGlobalContext context) {
        super(context, DELETE, "/", "/{index}");
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, final Client client) {
        DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(Strings.splitStringByCommaToArray(request.param("index")));
        deleteIndexRequest.timeout(request.paramAsTime("timeout", deleteIndexRequest.timeout()));
        deleteIndexRequest.masterNodeTimeout(request.paramAsTime("master_timeout", deleteIndexRequest.masterNodeTimeout()));
        deleteIndexRequest.indicesOptions(IndicesOptions.fromRequest(request, deleteIndexRequest.indicesOptions()));
        client.admin().indices().delete(deleteIndexRequest, new AcknowledgedRestListener<DeleteIndexResponse>(channel));
    }
}
