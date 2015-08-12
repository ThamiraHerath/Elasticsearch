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

package org.elasticsearch.rest.action.admin.cluster.node.stats;

import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsRequest;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.admin.indices.stats.CommonStatsFlags;
import org.elasticsearch.action.admin.indices.stats.CommonStatsFlags.Flag;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestBuilderListener;

import java.util.Set;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestStatus.OK;


/**
 *
 */
public class RestNodesStatsAction extends BaseRestHandler {

    @Inject
    public RestNodesStatsAction(Settings settings, RestController controller, Client client) {
        super(settings, controller, client);
        controller.registerHandler(GET, "/_nodes/stats", this);
        controller.registerHandler(GET, "/_nodes/{nodeId}/stats", this);

        controller.registerHandler(GET, "/_nodes/stats/{metric}", this);
        controller.registerHandler(GET, "/_nodes/{nodeId}/stats/{metric}", this);

        controller.registerHandler(GET, "/_nodes/stats/{metric}/{indexMetric}", this);

        controller.registerHandler(GET, "/_nodes/{nodeId}/stats/{metric}/{indexMetric}", this);
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, final Client client) {
        String[] nodesIds = Strings.splitStringByCommaToArray(request.param("nodeId"));
        Set<String> metrics = Strings.splitStringByCommaToSet(request.param("metric", "_all"));

        NodesStatsRequest nodesStatsRequest = new NodesStatsRequest(nodesIds);

        if (metrics.size() == 1 && metrics.contains("_all")) {
            nodesStatsRequest.all();
            nodesStatsRequest.indices(CommonStatsFlags.ALL);
        } else {
            nodesStatsRequest.clear();
            nodesStatsRequest.os(metrics.remove("os"));
            nodesStatsRequest.jvm(metrics.remove("jvm"));
            nodesStatsRequest.threadPool(metrics.remove("thread_pool"));
            nodesStatsRequest.network(metrics.remove("network"));
            nodesStatsRequest.fs(metrics.remove("fs"));
            nodesStatsRequest.transport(metrics.remove("transport"));
            nodesStatsRequest.http(metrics.remove("http"));
            nodesStatsRequest.indices(metrics.remove("indices"));
            nodesStatsRequest.process(metrics.remove("process"));
            nodesStatsRequest.breaker(metrics.remove("breaker"));
            nodesStatsRequest.script(metrics.remove("script"));
            nodesStatsRequest.plugins(metrics.remove("plugins"));

            // check for index specific metrics
            if (metrics.remove("indices")) {
                Set<String> indexMetrics = Strings.splitStringByCommaToSet(request.param("indexMetric", "_all"));
                if (indexMetrics.size() == 1 && indexMetrics.contains("_all")) {
                    nodesStatsRequest.indices(CommonStatsFlags.ALL);
                } else {
                    CommonStatsFlags flags = new CommonStatsFlags();
                    for (Flag flag : CommonStatsFlags.Flag.values()) {
                        flags.set(flag, indexMetrics.contains(flag.getRestName()));
                    }
                    nodesStatsRequest.indices(flags);
                }
            }

            // assume that remaining metrics are for custom plugins stats
            if (!metrics.isEmpty()) {
                nodesStatsRequest.custom(metrics.toArray(new String[metrics.size()]));
            }
        }

        if (nodesStatsRequest.indices().isSet(Flag.FieldData) && (request.hasParam("fields") || request.hasParam("fielddata_fields"))) {
            nodesStatsRequest.indices().fieldDataFields(request.paramAsStringArray("fielddata_fields", request.paramAsStringArray("fields", null)));
        }
        if (nodesStatsRequest.indices().isSet(Flag.Completion) && (request.hasParam("fields") || request.hasParam("completion_fields"))) {
            nodesStatsRequest.indices().completionDataFields(request.paramAsStringArray("completion_fields", request.paramAsStringArray("fields", null)));
        }
        if (nodesStatsRequest.indices().isSet(Flag.Search) && (request.hasParam("groups"))) {
            nodesStatsRequest.indices().groups(request.paramAsStringArray("groups", null));
        }
        if (nodesStatsRequest.indices().isSet(Flag.Indexing) && (request.hasParam("types"))) {
            nodesStatsRequest.indices().types(request.paramAsStringArray("types", null));
        }

        client.admin().cluster().nodesStats(nodesStatsRequest, new RestBuilderListener<NodesStatsResponse>(channel) {
            @Override
            public RestResponse buildResponse(NodesStatsResponse nodesStatsResponse, XContentBuilder builder) throws Exception {
                builder.startObject();
                nodesStatsResponse.toXContent(builder, request);
                builder.endObject();
                return new BytesRestResponse(OK, builder);
            }
        });
    }
}
