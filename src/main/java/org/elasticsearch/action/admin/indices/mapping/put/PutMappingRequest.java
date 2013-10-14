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

package org.elasticsearch.action.admin.indices.mapping.put;

import com.carrotsearch.hppc.ObjectOpenHashSet;
import org.elasticsearch.ElasticSearchGenerationException;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.MasterNodeOperationRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.action.ValidateActions.addValidationError;
import static org.elasticsearch.common.unit.TimeValue.readTimeValue;

/**
 * Puts mapping definition registered under a specific type into one or more indices. Best created with
 * {@link org.elasticsearch.client.Requests#putMappingRequest(String...)}.
 * <p/>
 * <p>If the mappings already exists, the new mappings will be merged with the new one. If there are elements
 * that can't be merged are detected, the request will be rejected unless the {@link #ignoreConflicts(boolean)}
 * is set. In such a case, the duplicate mappings will be rejected.
 *
 * @see org.elasticsearch.client.Requests#putMappingRequest(String...)
 * @see org.elasticsearch.client.IndicesAdminClient#putMapping(PutMappingRequest)
 * @see PutMappingResponse
 */
public class PutMappingRequest extends MasterNodeOperationRequest<PutMappingRequest> {

    private static ObjectOpenHashSet<String> RESERVED_FIELDS = ObjectOpenHashSet.from(
            "_uid", "_id", "_type", "_source",  "_all", "_analyzer", "_boost", "_parent", "_routing", "_index",
            "_size", "_timestamp", "_ttl"
    );

    private String[] indices;

    private String type;

    private String source;

    private TimeValue timeout = new TimeValue(10, TimeUnit.SECONDS);

    private boolean ignoreConflicts = false;

    PutMappingRequest() {
    }

    /**
     * Constructs a new put mapping request against one or more indices. If nothing is set then
     * it will be executed against all indices.
     */
    public PutMappingRequest(String... indices) {
        this.indices = indices;
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (type == null) {
            validationException = addValidationError("mapping type is missing", validationException);
        }
        if (source == null) {
            validationException = addValidationError("mapping source is missing", validationException);
        }
        return validationException;
    }

    /**
     * Sets the indices this put mapping operation will execute on.
     */
    public PutMappingRequest indices(String[] indices) {
        this.indices = indices;
        return this;
    }

    /**
     * The indices the mappings will be put.
     */
    public String[] indices() {
        return indices;
    }

    /**
     * The mapping type.
     */
    public String type() {
        return type;
    }

    /**
     * The type of the mappings.
     */
    public PutMappingRequest type(String type) {
        this.type = type;
        return this;
    }

    /**
     * The mapping source definition.
     */
    public String source() {
        return source;
    }

    /**
     * A specialized simplified mapping source method, takes the form of simple properties definition:
     * ("field1", "type=string,store=true").
     *
     * Also supports metadata mapping fields such as `_all` and `_parent` as property definition, these metadata
     * mapping fields will automatically be put on the top level mapping object.
     */
    public PutMappingRequest source(Object... source) {
        return source(buildFromSimplifiedDef(type, source));
    }

    public static XContentBuilder buildFromSimplifiedDef(String type, Object... source) {
        try {
            XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.startObject();
            if (type != null) {
                builder.startObject(type);
            }

            for (int i = 0; i < source.length; i++) {
                String fieldName = source[i++].toString();
                if (RESERVED_FIELDS.contains(fieldName)) {
                    builder.startObject(fieldName);
                    String[] s1 = Strings.splitStringByCommaToArray(source[i].toString());
                    for (String s : s1) {
                        String[] s2 = Strings.split(s, "=");
                        if (s2.length != 2) {
                            throw new ElasticSearchIllegalArgumentException("malformed " + s);
                        }
                        builder.field(s2[0], s2[1]);
                    }
                    builder.endObject();
                }
            }

            builder.startObject("properties");
            for (int i = 0; i < source.length; i++) {
                String fieldName = source[i++].toString();
                if (RESERVED_FIELDS.contains(fieldName)) {
                    continue;
                }

                builder.startObject(fieldName);
                String[] s1 = Strings.splitStringByCommaToArray(source[i].toString());
                for (String s : s1) {
                    String[] s2 = Strings.split(s, "=");
                    if (s2.length != 2) {
                        throw new ElasticSearchIllegalArgumentException("malformed " + s);
                    }
                    builder.field(s2[0], s2[1]);
                }
                builder.endObject();
            }
            builder.endObject();
            if (type != null) {
                builder.endObject();
            }
            builder.endObject();
            return builder;
        } catch (Exception e) {
            throw new ElasticSearchIllegalArgumentException("failed to generate simplified mapping definition", e);
        }
    }

    /**
     * The mapping source definition.
     */
    public PutMappingRequest source(XContentBuilder mappingBuilder) {
        try {
            return source(mappingBuilder.string());
        } catch (IOException e) {
            throw new ElasticSearchIllegalArgumentException("Failed to build json for mapping request", e);
        }
    }

    /**
     * The mapping source definition.
     */
    public PutMappingRequest source(Map mappingSource) {
        try {
            XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
            builder.map(mappingSource);
            return source(builder.string());
        } catch (IOException e) {
            throw new ElasticSearchGenerationException("Failed to generate [" + mappingSource + "]", e);
        }
    }

    /**
     * The mapping source definition.
     */
    public PutMappingRequest source(String mappingSource) {
        this.source = mappingSource;
        return this;
    }

    /**
     * Timeout to wait till the put mapping gets acknowledged of all current cluster nodes. Defaults to
     * <tt>10s</tt>.
     */
    TimeValue timeout() {
        return timeout;
    }

    /**
     * Timeout to wait till the put mapping gets acknowledged of all current cluster nodes. Defaults to
     * <tt>10s</tt>.
     */
    public PutMappingRequest timeout(TimeValue timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Timeout to wait till the put mapping gets acknowledged of all current cluster nodes. Defaults to
     * <tt>10s</tt>.
     */
    public PutMappingRequest timeout(String timeout) {
        return timeout(TimeValue.parseTimeValue(timeout, null));
    }

    /**
     * If there is already a mapping definition registered against the type, then it will be merged. If there are
     * elements that can't be merged are detected, the request will be rejected unless the
     * {@link #ignoreConflicts(boolean)} is set. In such a case, the duplicate mappings will be rejected.
     */
    public boolean ignoreConflicts() {
        return ignoreConflicts;
    }

    /**
     * If there is already a mapping definition registered against the type, then it will be merged. If there are
     * elements that can't be merged are detected, the request will be rejected unless the
     * {@link #ignoreConflicts(boolean)} is set. In such a case, the duplicate mappings will be rejected.
     */
    public PutMappingRequest ignoreConflicts(boolean ignoreDuplicates) {
        this.ignoreConflicts = ignoreDuplicates;
        return this;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        indices = in.readStringArray();
        type = in.readOptionalString();
        source = in.readString();
        timeout = readTimeValue(in);
        ignoreConflicts = in.readBoolean();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeStringArrayNullable(indices);
        out.writeOptionalString(type);
        out.writeString(source);
        timeout.writeTo(out);
        out.writeBoolean(ignoreConflicts);
    }
}
