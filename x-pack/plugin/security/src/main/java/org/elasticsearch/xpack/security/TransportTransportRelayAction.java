/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.ByteArrayStreamInput;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.RequestHandlerRegistry;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.security.action.TransportRelayAction;
import org.elasticsearch.xpack.core.security.action.TransportRelayRequest;
import org.elasticsearch.xpack.core.security.action.TransportRelayResponse;
import org.elasticsearch.xpack.security.support.CacheInvalidatorRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * Clears a security cache by name (with optional keys).
 * @see CacheInvalidatorRegistry
 */
public class TransportTransportRelayAction extends HandledTransportAction<TransportRelayRequest, TransportRelayResponse> {

    private final NodeClient nodeClient;
    private final ClusterService clusterService;
    private final TransportService transportService;

    @Inject
    public TransportTransportRelayAction(
        NodeClient nodeClient,
        ClusterService clusterService,
        TransportService transportService,
        ActionFilters actionFilters
    ) {
        super(TransportRelayAction.NAME, transportService, actionFilters, TransportRelayRequest::new);
        this.nodeClient = nodeClient;
        this.clusterService = clusterService;
        this.transportService = transportService;
    }

    @Override
    protected void doExecute(Task task, TransportRelayRequest request, ActionListener<TransportRelayResponse> listener) {
        final RequestHandlerRegistry<? extends TransportRequest> requestHandler = transportService.getRequestHandler(request.getAction());

        final TransportRequest transportRequest;
        try {
            final byte[] decodedPayload = Base64.getDecoder().decode(request.getPayload());
            transportRequest = requestHandler.newRequest(
                new NamedWriteableAwareStreamInput(new ByteArrayStreamInput(decodedPayload), nodeClient.getNamedWriteableRegistry())
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        final ActionListenerResponseHandler<ActionResponse> actionListenerResponseHandler = new ActionListenerResponseHandler<>(
            listener.map((actionResponse -> {
                final BytesStreamOutput out = new BytesStreamOutput();
                try {
                    actionResponse.writeTo(out);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return new TransportRelayResponse(Base64.getEncoder().encodeToString(out.bytes().array()));
            })),
            nodeClient.getResponseReader(request.getAction())
        );

        transportService.sendChildRequest(
            clusterService.localNode(),
            request.getAction(),
            transportRequest,
            task,
            TransportRequestOptions.EMPTY,
            actionListenerResponseHandler
        );
    }
}
