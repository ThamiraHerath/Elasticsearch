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

package org.elasticsearch.rest.action.admin.indices.shards;

import org.elasticsearch.action.admin.indices.shards.IndicesShardsStoresAction;
import org.elasticsearch.action.admin.indices.shards.IndicesShardsStoresResponse;
import org.elasticsearch.action.admin.indices.shards.IndicesShardsStoresRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestBuilderListener;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestStatus.OK;

/**
 * Rest action for {@link IndicesShardsStoresAction}
 */
public class RestIndicesShardStoresAction extends BaseRestHandler {

    @Inject
    public RestIndicesShardStoresAction(Settings settings, RestController controller, Client client) {
        super(settings, controller, client);
        controller.registerHandler(GET, "/_shard_stores", this);
        controller.registerHandler(GET, "/{index}/_shard_stores", this);
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, final Client client) {
        IndicesShardsStoresRequest indicesShardsStoresRequest = new IndicesShardsStoresRequest(Strings.splitStringByCommaToArray(request.param("index")));
        if (request.hasParam("status")) {
            indicesShardsStoresRequest.shardStatuses(Strings.splitStringByCommaToArray(request.param("status")));
        }
        indicesShardsStoresRequest.indicesOptions(IndicesOptions.fromRequest(request, indicesShardsStoresRequest.indicesOptions()));
        client.admin().indices().shardsStores(indicesShardsStoresRequest, new RestBuilderListener<IndicesShardsStoresResponse>(channel) {
            @Override
            public RestResponse buildResponse(IndicesShardsStoresResponse response, XContentBuilder builder) throws Exception {
                builder.startObject();
                response.toXContent(builder, request);
                builder.endObject();
                return new BytesRestResponse(OK, builder);
            }
        });
    }
}
