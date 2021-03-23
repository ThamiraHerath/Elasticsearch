/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.shutdown;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;

import java.util.Collections;
import java.util.List;

public class RestPutShutdownNodeAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "put_shutdown_node";
    }

    @Override
    public List<Route> routes() {
        return Collections.singletonList(new Route(RestRequest.Method.PUT, "/_nodes/{nodeId}/shutdown"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        String nodeId = request.param("nodeId");
        return channel -> client.execute(
            PutShutdownNodeAction.INSTANCE,
            new PutShutdownNodeAction.Request(nodeId),
            new RestToXContentListener<>(channel)
        );
    }
}
