/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.client.indices;

import org.elasticsearch.client.TimedRequest;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;

/**
 * A request to simulate matching a provided index name and an optional new index template against the existing index templates.
 */
public class SimulateIndexTemplateRequest extends TimedRequest implements ToXContentObject {

    private static final ParseField INDEX_NAME = new ParseField("index_name");
    private static final ParseField INDEX_TEMPLATE = new ParseField("index_template");

    private String indexName;

    @Nullable
    private PutIndexTemplateV2Request indexTemplateV2Request;

    public SimulateIndexTemplateRequest(String indexName) {
        if (Strings.isNullOrEmpty(indexName)) {
            throw new IllegalArgumentException("index name cannot be null or empty");
        }
        this.indexName = indexName;
    }

    /**
     * Return the index name for which we simulate the index template matching.
     */
    public String indexName() {
        return indexName;
    }

    /**
     * Set the index name to simulate template matching against the index templates in the system.
     */
    public SimulateIndexTemplateRequest indexName(String indexName) {
        this.indexName = indexName;
        return this;
    }

    /**
     * An optional new template request will be part of the index template simulation.
     */
    @Nullable
    public PutIndexTemplateV2Request indexTemplateV2Request() {
        return indexTemplateV2Request;
    }

    /**
     * Optionally, define a new template request which will included in the index simulation as if it was an index template stored in the
     * system. The new template will be validated just as a regular, standalone, live, new index template request.
     */
    public SimulateIndexTemplateRequest indexTemplateV2Request(@Nullable PutIndexTemplateV2Request indexTemplateV2Request) {
        this.indexTemplateV2Request = indexTemplateV2Request;
        return this;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(INDEX_NAME.getPreferredName(), indexName);
        if (indexTemplateV2Request != null) {
            indexTemplateV2Request.toXContent(builder, params);
        }
        builder.endObject();
        return builder;
    }
}
