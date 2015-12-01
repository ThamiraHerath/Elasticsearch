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

package org.elasticsearch.action.indexbysearch;

import static org.elasticsearch.action.ValidateActions.addValidationError;
import static org.elasticsearch.search.sort.SortBuilders.fieldSort;

import java.io.IOException;
import java.util.Arrays;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.builder.SearchSourceBuilder;

public class IndexBySearchRequest extends ActionRequest<IndexBySearchRequest> {
    private static final TimeValue DEFAULT_SCROLL_TIMEOUT = TimeValue.timeValueMinutes(5);
    private static final int DEFAULT_SIZE = 100;

    /**
     * The search to be executed.
     */
    private SearchRequest search;

    /**
     * Prototype for index requests.
     */
    private IndexRequest index;

    /**
     * Maximum number of documents to index. Null means all. Confusingly,
     * {@linkplain search}'s size is used for the bulk batch size. -1 is all
     * hits and is the default.
     */
    private int size = -1;

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
        if (search.source().from() != -1) {
            e = addValidationError("from is not supported in this context", e);
        }
        return e;
    }

    public int size() {
        return size;
    }

    public IndexBySearchRequest size(int size) {
        this.size = size;
        return this;
    }

    public SearchRequest search() {
        return search;
    }

    public IndexRequest index() {
        return index;
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
}
