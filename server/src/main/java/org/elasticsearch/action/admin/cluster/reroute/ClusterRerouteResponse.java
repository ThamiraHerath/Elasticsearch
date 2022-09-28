/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.reroute;

import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.routing.allocation.RoutingExplanations;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.logging.DeprecationCategory;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.core.RestApiVersion;
import org.elasticsearch.rest.action.search.RestSearchAction;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * Response returned after a cluster reroute request
 */
public class ClusterRerouteResponse extends AcknowledgedResponse implements ToXContentObject {

    private static final DeprecationLogger deprecationLogger = DeprecationLogger.getLogger(RestSearchAction.class);

    /**
     * To bre removed in 9
     */
    private final ClusterState state;
    private final RoutingExplanations explanations;

    ClusterRerouteResponse(StreamInput in) throws IOException {
        super(in);
        state = ClusterState.readFrom(in, null);
        explanations = RoutingExplanations.readFrom(in);
    }

    ClusterRerouteResponse(boolean acknowledged, ClusterState state, RoutingExplanations explanations) {
        super(acknowledged);
        this.state = state;
        this.explanations = explanations;
    }

    /**
     * Returns the cluster state resulted from the cluster reroute request execution
     */
    public ClusterState getState() {
        return this.state;
    }

    public RoutingExplanations getExplanations() {
        return this.explanations;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        state.writeTo(out);
        RoutingExplanations.writeTo(explanations, out);
    }

    @Override
    protected void addCustomFields(XContentBuilder builder, Params params) throws IOException {
        if (Objects.equals(params.param("metric"), "none") == false) {
            // this entire branch needs to be removed in 9
            if (builder.getRestApiVersion().major > RestApiVersion.V_7.major) {
                deprecationLogger.critical(
                    DeprecationCategory.API,
                    "reroute_cluster_state",
                    "[state] field is deprecated in a reroute response and will be removed."
                );
            }
            builder.startObject("state");
            state.toXContent(builder, params);
            builder.endObject();
        }

        if (params.paramAsBoolean("explain", false)) {
            explanations.toXContent(builder, ToXContent.EMPTY_PARAMS);
        }
    }
}
