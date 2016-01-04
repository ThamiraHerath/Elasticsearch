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

package org.elasticsearch.rest.action.admin.indices.open;

import org.elasticsearch.action.admin.indices.open.OpenIndexRequest;
import org.elasticsearch.action.admin.indices.open.OpenIndexResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Strings;
import org.elasticsearch.rest.BaseStandardRegistrationsRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestGlobalContext;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.support.AcknowledgedRestListener;

import static org.elasticsearch.rest.RestRequest.Method.POST;

/**
 *
 */
public class RestOpenIndexAction extends BaseStandardRegistrationsRestHandler {
    public RestOpenIndexAction(RestGlobalContext context) {
        super(context, POST, "/_open", "/{index}/_open");
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, final Client client) {
        OpenIndexRequest openIndexRequest = new OpenIndexRequest(Strings.splitStringByCommaToArray(request.param("index")));
        openIndexRequest.timeout(request.paramAsTime("timeout", openIndexRequest.timeout()));
        openIndexRequest.masterNodeTimeout(request.paramAsTime("master_timeout", openIndexRequest.masterNodeTimeout()));
        openIndexRequest.indicesOptions(IndicesOptions.fromRequest(request, openIndexRequest.indicesOptions()));
        client.admin().indices().open(openIndexRequest, new AcknowledgedRestListener<OpenIndexResponse>(channel));
    }
}
