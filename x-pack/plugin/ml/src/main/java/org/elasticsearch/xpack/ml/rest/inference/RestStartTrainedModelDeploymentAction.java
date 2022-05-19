/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.rest.inference;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.core.RestApiVersion;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.core.ml.action.StartTrainedModelDeploymentAction;
import org.elasticsearch.xpack.core.ml.inference.assignment.AllocationStatus;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.rest.RestCompatibilityChecker;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.xpack.core.ml.action.StartTrainedModelDeploymentAction.Request.NUMBER_OF_ALLOCATIONS;
import static org.elasticsearch.xpack.core.ml.action.StartTrainedModelDeploymentAction.Request.QUEUE_CAPACITY;
import static org.elasticsearch.xpack.core.ml.action.StartTrainedModelDeploymentAction.Request.THREADS_PER_ALLOCATION;
import static org.elasticsearch.xpack.core.ml.action.StartTrainedModelDeploymentAction.Request.TIMEOUT;
import static org.elasticsearch.xpack.core.ml.action.StartTrainedModelDeploymentAction.Request.WAIT_FOR;

public class RestStartTrainedModelDeploymentAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "xpack_ml_start_trained_models_deployment_action";
    }

    @Override
    public List<Route> routes() {
        return Collections.singletonList(
            new Route(
                POST,
                MachineLearning.BASE_PATH
                    + "trained_models/{"
                    + StartTrainedModelDeploymentAction.Request.MODEL_ID.getPreferredName()
                    + "}/deployment/_start"
            )
        );
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        String modelId = restRequest.param(StartTrainedModelDeploymentAction.Request.MODEL_ID.getPreferredName());
        StartTrainedModelDeploymentAction.Request request;
        if (restRequest.hasContentOrSourceParam()) {
            request = StartTrainedModelDeploymentAction.Request.parseRequest(modelId, restRequest.contentOrSourceParamParser());
        } else {
            request = new StartTrainedModelDeploymentAction.Request(modelId);
            if (restRequest.hasParam(TIMEOUT.getPreferredName())) {
                TimeValue openTimeout = restRequest.paramAsTime(
                    TIMEOUT.getPreferredName(),
                    StartTrainedModelDeploymentAction.DEFAULT_TIMEOUT
                );
                request.setTimeout(openTimeout);
            }
            request.setWaitForState(
                AllocationStatus.State.fromString(restRequest.param(WAIT_FOR.getPreferredName(), AllocationStatus.State.STARTED.toString()))
            );
            RestCompatibilityChecker.checkAndSetDeprecatedParam(
                NUMBER_OF_ALLOCATIONS.getDeprecatedNames()[0],
                NUMBER_OF_ALLOCATIONS.getPreferredName(),
                RestApiVersion.V_8,
                restRequest,
                (r, s) -> r.paramAsInt(s, request.getNumberOfAllocations()),
                request::setNumberOfAllocations
            );
            RestCompatibilityChecker.checkAndSetDeprecatedParam(
                THREADS_PER_ALLOCATION.getDeprecatedNames()[0],
                THREADS_PER_ALLOCATION.getPreferredName(),
                RestApiVersion.V_8,
                restRequest,
                (r, s) -> r.paramAsInt(s, request.getThreadsPerAllocation()),
                request::setThreadsPerAllocation
            );
            request.setQueueCapacity(restRequest.paramAsInt(QUEUE_CAPACITY.getPreferredName(), request.getQueueCapacity()));
        }

        return channel -> client.execute(StartTrainedModelDeploymentAction.INSTANCE, request, new RestToXContentListener<>(channel));
    }
}
