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

package org.elasticsearch.action.bulk;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.support.replication.ReplicationRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.elasticsearch.action.ValidateActions.addValidationError;

/**
 * A bulk request holds an ordered {@link IndexRequest}s, {@link DeleteRequest}s and {@link UpdateRequest}s
 * and allows to executes it in a single batch.
 *
 * Note that we only support refresh on the bulk request not per item.
 * @see org.elasticsearch.client.Client#bulk(BulkRequest)
 */
public class BulkRequest extends ActionRequest implements CompositeIndicesRequest, WriteRequest<BulkRequest> {

    private static final int REQUEST_OVERHEAD = 50;

    /**
     * Requests that are part of this request. It is only possible to add things that are both {@link ActionRequest}s and
     * {@link WriteRequest}s to this but java doesn't support syntax to declare that everything in the array has both types so we declare
     * the one with the least casts.
     */
    final List<DocWriteRequest<?>> requests = new ArrayList<>();
    private final Set<String> indices = new HashSet<>();

    protected TimeValue timeout = BulkShardRequest.DEFAULT_TIMEOUT;
    private ActiveShardCount waitForActiveShards = ActiveShardCount.DEFAULT;
    private RefreshPolicy refreshPolicy = RefreshPolicy.NONE;
    private String globalPipeline;
    private String globalRouting;
    private String globalIndex;
    private Boolean preferV2Templates;

    private long sizeInBytes = 0;

    public BulkRequest() {}

    public BulkRequest(StreamInput in) throws IOException {
        super(in);
        waitForActiveShards = ActiveShardCount.readFrom(in);
        int size = in.readVInt();
        for (int i = 0; i < size; i++) {
            requests.add(DocWriteRequest.readDocumentRequest(in));
        }
        refreshPolicy = RefreshPolicy.readFrom(in);
        timeout = in.readTimeValue();
        if (in.getVersion().onOrAfter(Version.V_8_0_0)) {
            this.preferV2Templates = in.readOptionalBoolean();
        }
    }

    public BulkRequest(@Nullable String globalIndex) {
        this.globalIndex = globalIndex;
    }

    /**
     * Adds a list of requests to be executed. Either index or delete requests.
     */
    public BulkRequest add(DocWriteRequest<?>... requests) {
        for (DocWriteRequest<?> request : requests) {
            add(request);
        }
        return this;
    }

    /**
     * Add a request to the current BulkRequest.
     * @param request Request to add
     * @return the current bulk request
     */
    public BulkRequest add(DocWriteRequest<?> request) {
        if (request instanceof IndexRequest) {
            add((IndexRequest) request);
        } else if (request instanceof DeleteRequest) {
            add((DeleteRequest) request);
        } else if (request instanceof UpdateRequest) {
            add((UpdateRequest) request);
        } else {
            throw new IllegalArgumentException("No support for request [" + request + "]");
        }
        indices.add(request.index());
        return this;
    }

    /**
     * Adds a list of requests to be executed. Either index or delete requests.
     */
    public BulkRequest add(Iterable<DocWriteRequest<?>> requests) {
        for (DocWriteRequest<?> request : requests) {
            add(request);
        }
        return this;
    }

    /**
     * Adds an {@link IndexRequest} to the list of actions to execute. Follows the same behavior of {@link IndexRequest}
     * (for example, if no id is provided, one will be generated, or usage of the create flag).
     */
    public BulkRequest add(IndexRequest request) {
        return internalAdd(request);
    }

    BulkRequest internalAdd(IndexRequest request) {
        Objects.requireNonNull(request, "'request' must not be null");
        applyGlobalMandatoryParameters(request);

        requests.add(request);
        // lack of source is validated in validate() method
        sizeInBytes += (request.source() != null ? request.source().length() : 0) + REQUEST_OVERHEAD;
        indices.add(request.index());
        return this;
    }

    /**
     * Adds an {@link UpdateRequest} to the list of actions to execute.
     */
    public BulkRequest add(UpdateRequest request) {
        return internalAdd(request);
    }

    BulkRequest internalAdd(UpdateRequest request) {
        Objects.requireNonNull(request, "'request' must not be null");
        applyGlobalMandatoryParameters(request);

        requests.add(request);
        if (request.doc() != null) {
            sizeInBytes += request.doc().source().length();
        }
        if (request.upsertRequest() != null) {
            sizeInBytes += request.upsertRequest().source().length();
        }
        if (request.script() != null) {
            sizeInBytes += request.script().getIdOrCode().length() * 2;
        }
        indices.add(request.index());
        return this;
    }

    /**
     * Adds an {@link DeleteRequest} to the list of actions to execute.
     */
    public BulkRequest add(DeleteRequest request) {
        Objects.requireNonNull(request, "'request' must not be null");
        applyGlobalMandatoryParameters(request);

        requests.add(request);
        sizeInBytes += REQUEST_OVERHEAD;
        indices.add(request.index());
        return this;
    }

    /**
     * The list of requests in this bulk request.
     */
    public List<DocWriteRequest<?>> requests() {
        return this.requests;
    }

    public BulkRequest preferV2Templates(@Nullable Boolean preferV2Templates) {
        this.preferV2Templates = preferV2Templates;
        return this;
    }

    @Nullable
    public Boolean preferV2Templates() {
        return this.preferV2Templates;
    }

    /**
     * The number of actions in the bulk request.
     */
    public int numberOfActions() {
        return requests.size();
    }

    /**
     * The estimated size in bytes of the bulk request.
     */
    public long estimatedSizeInBytes() {
        return sizeInBytes;
    }

    /**
     * Adds a framed data in binary format
     */
    public BulkRequest add(byte[] data, int from, int length, XContentType xContentType) throws IOException {
        return add(data, from, length, null, xContentType);
    }

    /**
     * Adds a framed data in binary format
     */
    public BulkRequest add(byte[] data, int from, int length, @Nullable String defaultIndex,
                           XContentType xContentType) throws IOException {
        return add(new BytesArray(data, from, length), defaultIndex, xContentType);
    }

    /**
     * Adds a framed data in binary format
     */
    public BulkRequest add(BytesReference data, @Nullable String defaultIndex,
                           XContentType xContentType) throws IOException {
        return add(data, defaultIndex, null, null, null, true, xContentType);
    }

    /**
     * Adds a framed data in binary format
     */
    public BulkRequest add(BytesReference data, @Nullable String defaultIndex, boolean allowExplicitIndex,
                           XContentType xContentType) throws IOException {
        return add(data, defaultIndex, null, null, null, allowExplicitIndex, xContentType);

    }

    public BulkRequest add(BytesReference data, @Nullable String defaultIndex,
                           @Nullable String defaultRouting, @Nullable FetchSourceContext defaultFetchSourceContext,
                           @Nullable String defaultPipeline, boolean allowExplicitIndex,
                           XContentType xContentType) throws IOException {
        String routing = valueOrDefault(defaultRouting, globalRouting);
        String pipeline = valueOrDefault(defaultPipeline, globalPipeline);
        new BulkRequestParser(true).parse(data, defaultIndex, routing, defaultFetchSourceContext, pipeline,
                allowExplicitIndex, xContentType, (indexRequest, type) -> internalAdd(indexRequest), this::internalAdd, this::add);
        return this;
    }

    /**
     * Sets the number of shard copies that must be active before proceeding with the write.
     * See {@link ReplicationRequest#waitForActiveShards(ActiveShardCount)} for details.
     */
    public BulkRequest waitForActiveShards(ActiveShardCount waitForActiveShards) {
        this.waitForActiveShards = waitForActiveShards;
        return this;
    }

    /**
     * A shortcut for {@link #waitForActiveShards(ActiveShardCount)} where the numerical
     * shard count is passed in, instead of having to first call {@link ActiveShardCount#from(int)}
     * to get the ActiveShardCount.
     */
    public BulkRequest waitForActiveShards(final int waitForActiveShards) {
        return waitForActiveShards(ActiveShardCount.from(waitForActiveShards));
    }

    public ActiveShardCount waitForActiveShards() {
        return this.waitForActiveShards;
    }

    @Override
    public BulkRequest setRefreshPolicy(RefreshPolicy refreshPolicy) {
        this.refreshPolicy = refreshPolicy;
        return this;
    }

    @Override
    public RefreshPolicy getRefreshPolicy() {
        return refreshPolicy;
    }

    /**
     * A timeout to wait if the index operation can't be performed immediately. Defaults to {@code 1m}.
     */
    public final BulkRequest timeout(TimeValue timeout) {
        this.timeout = timeout;
        return this;
    }

    public final BulkRequest pipeline(String globalPipeline) {
        this.globalPipeline = globalPipeline;
        return this;
    }

    public final BulkRequest routing(String globalRouting){
        this.globalRouting = globalRouting;
        return this;
    }
    /**
     * A timeout to wait if the index operation can't be performed immediately. Defaults to {@code 1m}.
     */
    public final BulkRequest timeout(String timeout) {
        return timeout(TimeValue.parseTimeValue(timeout, null, getClass().getSimpleName() + ".timeout"));
    }

    public TimeValue timeout() {
        return timeout;
    }

    public String pipeline() {
        return globalPipeline;
    }

    public String routing() {
        return globalRouting;
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (requests.isEmpty()) {
            validationException = addValidationError("no requests added", validationException);
        }
        for (DocWriteRequest<?> request : requests) {
            // We first check if refresh has been set
            if (((WriteRequest<?>) request).getRefreshPolicy() != RefreshPolicy.NONE) {
                validationException = addValidationError(
                        "RefreshPolicy is not supported on an item request. Set it on the BulkRequest instead.", validationException);
            }
            ActionRequestValidationException ex = ((WriteRequest<?>) request).validate();
            if (ex != null) {
                if (validationException == null) {
                    validationException = new ActionRequestValidationException();
                }
                validationException.addValidationErrors(ex.validationErrors());
            }
        }

        return validationException;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        waitForActiveShards.writeTo(out);
        out.writeVInt(requests.size());
        for (DocWriteRequest<?> request : requests) {
            DocWriteRequest.writeDocumentRequest(out, request);
        }
        refreshPolicy.writeTo(out);
        out.writeTimeValue(timeout);
        if (out.getVersion().onOrAfter(Version.V_8_0_0)) {
            out.writeOptionalBoolean(preferV2Templates);
        }
    }

    @Override
    public String getDescription() {
        return "requests[" + requests.size() + "], indices[" + Strings.collectionToDelimitedString(indices, ", ") + "]";
    }

    private void applyGlobalMandatoryParameters(DocWriteRequest<?> request) {
        request.index(valueOrDefault(request.index(), globalIndex));
    }

    private static String valueOrDefault(String value, String globalDefault) {
        if (Strings.isNullOrEmpty(value) && !Strings.isNullOrEmpty(globalDefault)) {
            return globalDefault;
        }
        return value;
    }
}
