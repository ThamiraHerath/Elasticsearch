/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.rest.action.apikey;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xpack.core.security.action.apikey.ApiKey;
import org.elasticsearch.xpack.core.security.action.apikey.CreateApiKeyRequest;
import org.elasticsearch.xpack.core.security.action.apikey.CreateCrossClusterApiKeyAction;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

/**
 * Rest action to create an API key specific to cross cluster access via the dedicate remote cluster server port
 */
public final class RestCreateCrossClusterApiKeyAction extends ApiKeyBaseRestHandler {

    /**
     * @param settings the node's settings
     * @param licenseState the license state that will be used to determine if
     * security is licensed
     */
    public RestCreateCrossClusterApiKeyAction(Settings settings, XPackLicenseState licenseState) {
        super(settings, licenseState);
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(POST, "/_security/cross_cluster/api_key"));
    }

    @Override
    public String getName() {
        return "xpack_security_create_cross_cluster_api_key";
    }

    @Override
    protected RestChannelConsumer innerPrepareRequest(final RestRequest request, final NodeClient client) throws IOException {
        final Payload payload = PARSER.parse(request.contentParser(), null);

        final CreateApiKeyRequest createApiKeyRequest = payload.toCreateApiKeyRequest();
        return channel -> client.execute(
            CreateCrossClusterApiKeyAction.INSTANCE,
            createApiKeyRequest,
            new RestToXContentListener<>(channel)
        );
    }

    record Payload(String name, CrossClusterApiKeyAccess access, TimeValue expiration, Map<String, Object> metadata) {
        public CreateApiKeyRequest toCreateApiKeyRequest() {
            final CreateApiKeyRequest createApiKeyRequest = new CreateApiKeyRequest();
            createApiKeyRequest.setType(ApiKey.Type.CROSS_CLUSTER);
            createApiKeyRequest.setName(name);
            createApiKeyRequest.setExpiration(expiration);
            createApiKeyRequest.setMetadata(metadata);
            createApiKeyRequest.setRoleDescriptors(List.of(access.toRoleDescriptor(name)));
            return createApiKeyRequest;
        }
    }

    @SuppressWarnings("unchecked")
    static final ConstructingObjectParser<Payload, Void> PARSER = new ConstructingObjectParser<>(
        "cross_cluster_api_key_request_payload",
        false,
        (args, v) -> new Payload(
            (String) args[0],
            (CrossClusterApiKeyAccess) args[1],
            TimeValue.parseTimeValue((String) args[2], null, "expiration"),
            (Map<String, Object>) args[3]
        )
    );

    static {
        PARSER.declareString(constructorArg(), new ParseField("name"));
        PARSER.declareObject(constructorArg(), CrossClusterApiKeyAccess.PARSER, new ParseField("access"));
        PARSER.declareString(optionalConstructorArg(), new ParseField("expiration"));
        PARSER.declareObject(optionalConstructorArg(), (p, c) -> p.map(), new ParseField("metadata"));
    }
}
