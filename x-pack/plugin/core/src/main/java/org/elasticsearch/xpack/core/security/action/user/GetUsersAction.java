/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.security.action.user;

import org.elasticsearch.action.Action;
import org.elasticsearch.client.ElasticsearchClient;

/**
 * Action for retrieving a user from the security index
 */
public class GetUsersAction extends Action<GetUsersRequest, GetUsersResponse, GetUsersRequestBuilder> {

    public static final GetUsersAction INSTANCE = new GetUsersAction();
    public static final String NAME = "cluster:admin/xpack/security/user/get";

    protected GetUsersAction() {
        super(NAME);
    }

    @Override
    public GetUsersRequestBuilder newRequestBuilder(ElasticsearchClient client) {
        return new GetUsersRequestBuilder(client, this);
    }

    @Override
    public GetUsersResponse newResponse() {
        return new GetUsersResponse();
    }
}
