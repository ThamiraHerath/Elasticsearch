/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
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

package org.elasticsearch.rest.action.deletebyquery;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequest;
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse;
import org.elasticsearch.action.deletebyquery.IndexDeleteByQueryResponse;
import org.elasticsearch.action.deletebyquery.ShardDeleteByQueryRequest;
import org.elasticsearch.action.support.replication.ReplicationType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.XContentRestResponse;
import org.elasticsearch.rest.XContentThrowableRestResponse;
import org.elasticsearch.rest.action.support.RestActions;
import org.elasticsearch.rest.action.support.RestXContentBuilder;

import java.io.IOException;

import static org.elasticsearch.rest.RestRequest.Method.*;
import static org.elasticsearch.rest.RestStatus.*;
import static org.elasticsearch.rest.action.support.RestActions.*;

/**
 * @author kimchy (Shay Banon)
 */
public class RestDeleteByQueryAction extends BaseRestHandler {

    @Inject public RestDeleteByQueryAction(Settings settings, Client client, RestController controller) {
        super(settings, client);
        controller.registerHandler(DELETE, "/{index}/_query", this);
        controller.registerHandler(DELETE, "/{index}/{type}/_query", this);
    }

    @Override public void handleRequest(final RestRequest request, final RestChannel channel) {
        DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest(splitIndices(request.param("index")));
        // we just build a response and send it, no need to fork a thread
        deleteByQueryRequest.listenerThreaded(false);
        try {
            if (request.hasContent()) {
                deleteByQueryRequest.query(request.contentByteArray(), request.contentByteArrayOffset(), request.contentLength(), request.contentUnsafe());
            } else {
                String source = request.param("source");
                if (source != null) {
                    deleteByQueryRequest.query(source);
                } else {
                    deleteByQueryRequest.query(RestActions.parseQuerySource(request));
                }
            }
            deleteByQueryRequest.types(splitTypes(request.param("type")));
            deleteByQueryRequest.timeout(request.paramAsTime("timeout", ShardDeleteByQueryRequest.DEFAULT_TIMEOUT));

            deleteByQueryRequest.routing(request.param("routing"));
            String replicationType = request.param("replication");
            if (replicationType != null) {
                deleteByQueryRequest.replicationType(ReplicationType.fromString(replicationType));
            }
            String consistencyLevel = request.param("consistency");
            if (consistencyLevel != null) {
                deleteByQueryRequest.consistencyLevel(WriteConsistencyLevel.fromString(consistencyLevel));
            }
        } catch (Exception e) {
            try {
                XContentBuilder builder = RestXContentBuilder.restContentBuilder(request);
                channel.sendResponse(new XContentRestResponse(request, PRECONDITION_FAILED, builder.startObject().field("error", e.getMessage()).endObject()));
            } catch (IOException e1) {
                logger.error("Failed to send failure response", e1);
            }
            return;
        }
        client.deleteByQuery(deleteByQueryRequest, new ActionListener<DeleteByQueryResponse>() {
            @Override public void onResponse(DeleteByQueryResponse result) {
                try {
                    XContentBuilder builder = RestXContentBuilder.restContentBuilder(request);
                    builder.startObject().field("ok", true);

                    builder.startObject("_indices");
                    for (IndexDeleteByQueryResponse indexDeleteByQueryResponse : result.indices().values()) {
                        builder.startObject(indexDeleteByQueryResponse.index(), XContentBuilder.FieldCaseConversion.NONE);

                        builder.startObject("_shards");
                        builder.field("total", indexDeleteByQueryResponse.totalShards());
                        builder.field("successful", indexDeleteByQueryResponse.successfulShards());
                        builder.field("failed", indexDeleteByQueryResponse.failedShards());
                        builder.endObject();

                        builder.endObject();
                    }
                    builder.endObject();

                    builder.endObject();
                    channel.sendResponse(new XContentRestResponse(request, OK, builder));
                } catch (Exception e) {
                    onFailure(e);
                }
            }

            @Override public void onFailure(Throwable e) {
                try {
                    channel.sendResponse(new XContentThrowableRestResponse(request, e));
                } catch (IOException e1) {
                    logger.error("Failed to send failure response", e1);
                }
            }
        });
    }
}