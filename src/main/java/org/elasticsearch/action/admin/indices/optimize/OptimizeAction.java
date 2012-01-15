/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.action.admin.indices.optimize;

import org.elasticsearch.action.admin.indices.IndicesAction;
import org.elasticsearch.client.IndicesAdminClient;

/**
 */
public class OptimizeAction extends IndicesAction<OptimizeRequest, OptimizeResponse, OptimizeRequestBuilder> {

    public static final OptimizeAction INSTANCE = new OptimizeAction();
    public static final String NAME = "indices/optimize";

    private OptimizeAction() {
        super(NAME);
    }

    @Override
    public OptimizeResponse newResponse() {
        return new OptimizeResponse();
    }

    @Override
    public OptimizeRequestBuilder newRequestBuilder(IndicesAdminClient client) {
        return new OptimizeRequestBuilder(client);
    }
}
