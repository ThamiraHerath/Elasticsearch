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

package org.elasticsearch.plugin.indexbysearch;

import static java.lang.Math.min;
import static org.elasticsearch.action.ValidateActions.addValidationError;
import static org.elasticsearch.search.sort.SortBuilders.fieldSort;

import java.io.IOException;
import java.util.Arrays;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequest.OpType;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.plugin.indexbysearch.AbstractAsyncScrollAction.AsyncScrollActionRequest;
import org.elasticsearch.search.builder.SearchSourceBuilder;

public class IndexBySearchRequest extends ActionRequest<IndexBySearchRequest> implements AsyncScrollActionRequest {
    private static final TimeValue DEFAULT_SCROLL_TIMEOUT = TimeValue.timeValueMinutes(5);
    private static final int DEFAULT_SIZE = 100;

    /**
     * The search to be executed.
     */
    private SearchRequest search;

    /**
     * Prototype for index requests.
     *
     * Note that we co-opt version = Versions.NOT_SET to mean
     * "do not set the version in the index requests that we send for each scroll hit."
     */
    private IndexRequest index;

    /**
     * Maximum number of documents to index. Null means all. Confusingly,
     * {@linkplain search}'s size is used for the bulk batch size. -1 is all
     * hits and is the default.
     */
    private int size = -1;

    /**
     * Should version conflicts cause an abort? Defaults to false.
     */
    private boolean abortOnVersionConflict = false;

    public IndexBySearchRequest() {
    }

    public IndexBySearchRequest(SearchRequest search, IndexRequest index) {
        this.search = search;
        this.index = index;

        search.scroll(DEFAULT_SCROLL_TIMEOUT);
        search.source(new SearchSourceBuilder());
        search.source().version(true);
        search.source().sort(fieldSort("_doc"));
        search.source().size(DEFAULT_SIZE);

        // Clear the versionType so we can check if we've parsed it
        index.versionType(null);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException e = search.validate();
        /*
         * Note that we don't validate the index here - it won't work because
         * we'll be filling in portions of it as we receive the docs. But we can
         * validate some things.
         */
        if (index.index() == null) {
            e = addValidationError("index must be specified", e);
        }
        if (false == (index.routing() == null || index.routing().startsWith("=") ||
                "keep".equals(index.routing()) || "discard".equals(index.routing()))) {
            e = addValidationError("routing must be unset, [keep], [discard] or [=<some new value>]", e);
        }
        if (search.source().from() != -1) {
            e = addValidationError("from is not supported in this context", e);
        }
        return e;
    }

    @Override
    public int size() {
        return size;
    }

    public IndexBySearchRequest size(int size) {
        this.size = size;
        return this;
    }

    @Override
    public boolean abortOnVersionConflict() {
        return abortOnVersionConflict;
    }

    public IndexBySearchRequest abortOnVersionConflict(boolean abortOnVersionConflict) {
        this.abortOnVersionConflict = abortOnVersionConflict;
        return this;
    }

    public SearchRequest search() {
        return search;
    }

    public IndexRequest index() {
        return index;
    }

    public void fillInConditionalDefaults() {
        if (search.source() == null) {
            search.source(new SearchSourceBuilder());
        }
        if (size() != -1) {
            /*
             * Don't use larger batches than the maximum request size because
             * that'd be silly.
             */
            search().source().size(min(size(), search().source().size()));
        }
        if (index().versionType() == null) {
            setupDefaultVersionType();
        }
    }

    void setupDefaultVersionType() {
        if (index().version() == Versions.NOT_SET) {
            /*
             * Not set means just don't set it on the index request. That
             * doesn't work properly with some VersionTypes so lets just set it
             * to something simple.
             */
            index().versionType(VersionType.INTERNAL);
            return;
        }
        if (destinationSameAsSource()) {
            // Only writes on versions == and writes the version number
            index().versionType(VersionType.REINDEX);
            return;
        }
        index().opType(OpType.CREATE);
    }

    /**
     * Are the source and the destination "the same". Useful for conditional defaults. The rules are:
     * <ul>
     *  <li>Is the source exactly one index? No === false<li>
     *  <li>Is the single source index the same as the destination index? No === false</li>
     *  <li>Is the destination type null? Yes === true if the source type is also empty, false otherwise</li>
     *  <li>Is the source exactly one type? No === false</li>
     *  <li>true if the single source type is the same as the destination type</li>
     * </ul>
     */
    public boolean destinationSameAsSource() {
        if (search.indices() == null || search.indices().length != 1) {
            return false;
        }
        if (false == search.indices()[0].equals(index.index())) {
            return false;
        }
        if (index.type() == null) {
            return search.types() == null || search.types().length == 0;
        }
        if (search.types().length != 1) {
            return false;
        }
        return search.types()[0].equals(index.type());
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        search.readFrom(in);
        index.readFrom(in);
        size = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        search.writeTo(out);
        index.writeTo(out);
        out.writeVInt(size);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append("index-by-search from ");
        if (search.indices() != null && search.indices().length != 0) {
            b.append(Arrays.toString(search.indices()));
        } else {
            b.append("[all indices]");
        }
        if (search.types() != null && search.types().length != 0) {
            b.append(search.types());
        }
        b.append(" to [").append(index.index()).append(']');
        if (index.type() != null) {
            b.append('[').append(index.type()).append(']');
        }
        return b.toString();
    }

    /**
     * Parse and set the version on an index request. This is used to handle
     * index-by-search specific parsing logic for versions.
     */
    public static void setVersionOnIndexRequest(IndexRequest indexRequest, String version) {
        switch (version) {
        case "not_set":
            indexRequest.version(Versions.NOT_SET);
            return;
        default:
            throw new IllegalArgumentException("Invalid version: " + version);
        }
    }
}
