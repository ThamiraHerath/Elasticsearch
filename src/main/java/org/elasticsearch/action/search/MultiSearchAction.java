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

package org.elasticsearch.action.search;

import org.elasticsearch.action.ClientAction;
import org.elasticsearch.client.Client;

/**
 */
public class MultiSearchAction extends ClientAction<MultiSearchRequest, MultiSearchResponse, MultiSearchRequestBuilder> {

    public static final MultiSearchAction INSTANCE = new MultiSearchAction();
    public static final String NAME = "indices:data/read/msearch";

    private MultiSearchAction() {
        super(NAME);
    }

    @Override
    public MultiSearchResponse newResponse() {
        return new MultiSearchResponse();
    }

    @Override
    public MultiSearchRequestBuilder newRequestBuilder(Client client) {
        return new MultiSearchRequestBuilder(client);
    }
}
