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

package org.elasticsearch.index.rankeval;

import org.elasticsearch.action.support.ToXContentToBytes;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Defines a QA specification: All end user supplied query intents will be mapped to the search request specified in this search request
 * template and executed against the targetIndex given. Any filters that should be applied in the target system can be specified as well.
 *
 * The resulting document lists can then be compared against what was specified in the set of rated documents as part of a QAQuery.
 * */
public class QuerySpec extends ToXContentToBytes implements Writeable {
    private static final ESLogger logger = Loggers.getLogger(QuerySpec.class);

    private String specId;
    private SearchSourceBuilder testRequest;
    private List<String> indices = new ArrayList<>();
    private List<String> types = new ArrayList<>();
    /** Collection of rated queries for this query QA specification.*/
    private List<RatedDocument> ratedDocs = new ArrayList<>();
    /** Map of parameters to use for filling a query template, can be used instead of providing testRequest. */
    private Map<String, Object> params = new HashMap<>();

    public QuerySpec() {
        // ctor that doesn't require all args to be present immediatly is easier to use with ObjectParser
        // TODO decide if we can require only id as mandatory, set default values for the rest?
    }

    public QuerySpec(String specId, SearchSourceBuilder testRequest, List<String> indices, List<String> types,
            List<RatedDocument> ratedDocs) {
        this.specId = specId;
        this.testRequest = testRequest;
        this.indices = indices;
        this.types = types;
        this.ratedDocs = ratedDocs;
    }

    public QuerySpec(StreamInput in) throws IOException {
        this.specId = in.readString();
        testRequest = new SearchSourceBuilder(in);
        int indicesSize = in.readInt();
        indices = new ArrayList<>(indicesSize);
        for (int i = 0; i < indicesSize; i++) {
            this.indices.add(in.readString());
        }
        int typesSize = in.readInt();
        types = new ArrayList<>(typesSize);
        for (int i = 0; i < typesSize; i++) {
            this.types.add(in.readString());
        }
        int intentSize = in.readInt();
        ratedDocs = new ArrayList<>(intentSize);
        for (int i = 0; i < intentSize; i++) {
            ratedDocs.add(new RatedDocument(in));
        }
        this.params = in.readMap();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(specId);
        testRequest.writeTo(out);
        out.writeInt(indices.size());
        for (String index : indices) {
            out.writeString(index);
        }
        out.writeInt(types.size());
        for (String type : types) {
            out.writeString(type);
        }
        out.writeInt(ratedDocs.size());
        for (RatedDocument ratedDoc : ratedDocs) {
            ratedDoc.writeTo(out);
        }
        out.writeMap(params);
    }

    public SearchSourceBuilder getTestRequest() {
        return testRequest;
    }

    public void setTestRequest(SearchSourceBuilder testRequest) {
        this.testRequest = testRequest;
    }

    public List<String> getIndices() {
        return indices;
    }

    public void setIndices(List<String> indices) {
        this.indices = indices;
    }

    public List<String> getTypes() {
        return types;
    }

    public void setTypes(List<String> types) {
        this.types = types;
    }

    /** Returns a user supplied spec id for easier referencing. */
    public String getSpecId() {
        return specId;
    }

    /** Sets a user supplied spec id for easier referencing. */
    public void setSpecId(String specId) {
        this.specId = specId;
    }

    /** Returns a list of rated documents to evaluate. */
    public List<RatedDocument> getRatedDocs() {
        return ratedDocs;
    }

    /** Set a list of rated documents for this query. */
    public void setRatedDocs(List<RatedDocument> ratedDocs) {
        this.ratedDocs = ratedDocs;
    }
    
    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
    
    public Map<String, Object> getParams() {
        return this.params;
    }

    private static final ParseField ID_FIELD = new ParseField("id");
    private static final ParseField REQUEST_FIELD = new ParseField("request");
    private static final ParseField RATINGS_FIELD = new ParseField("ratings");
    private static final ParseField PARAMS_FIELD = new ParseField("params");
    private static final ObjectParser<QuerySpec, RankEvalContext> PARSER = new ObjectParser<>("requests", QuerySpec::new);

    static {
        PARSER.declareString(QuerySpec::setSpecId, ID_FIELD);
        PARSER.declareObject(QuerySpec::setTestRequest, (p, c) -> {
            try {
                logger.error("Building search source builder");
                return SearchSourceBuilder.fromXContent(c.getParseContext(), c.getAggs(),  c.getSuggesters());
            } catch (IOException ex) {
                throw new ParsingException(p.getTokenLocation(), "error parsing request", ex);
            }
        } , REQUEST_FIELD);
        PARSER.declareObject(QuerySpec::setParams, (p, c) -> {
            try {
                return p.map();
            } catch (IOException ex) {
                throw new ParsingException(p.getTokenLocation(), "error parsing ratings", ex);
            }
        }, PARAMS_FIELD);
        PARSER.declareObjectArray(QuerySpec::setRatedDocs, (p, c) -> {
            try {
                return RatedDocument.fromXContent(p, c);
            } catch (IOException ex) {
                throw new ParsingException(p.getTokenLocation(), "error parsing ratings", ex);
            }
        } , RATINGS_FIELD);
    }

    /**
     * Parses {@link QuerySpec} from rest representation:
     *
     * Example:
     *  {
     *   "id": "coffee_query",
     *   "request": {
     *           "query": {
     *               "bool": {
     *                   "must": [
     *                       {"match": {"beverage": "coffee"}},
     *                       {"term": {"browser": {"value": "safari"}}},
     *                       {"term": {"time_of_day": {"value": "morning","boost": 2}}},
     *                       {"term": {"ip_location": {"value": "ams","boost": 10}}}]}
     *           },
     *           "size": 10
     *   },
     *   "ratings": [{ "1": 1 }, { "2": 0 }, { "3": 1 } ]
     *  }
     */
    public static QuerySpec fromXContent(XContentParser parser, RankEvalContext context) throws IOException {
        return PARSER.parse(parser, context);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(ID_FIELD.getPreferredName(), this.specId);
        if (testRequest != null)
            builder.field(REQUEST_FIELD.getPreferredName(), this.testRequest);
        builder.startArray(PARAMS_FIELD.getPreferredName());
//        for (Entry<String, Object> entry : this.params.entrySet()) {
//            builder.field(entry.getKey(), entry.getValue());
//        }
        builder.endArray();
        builder.startArray(RATINGS_FIELD.getPreferredName());
        for (RatedDocument doc : this.ratedDocs) {
            doc.toXContent(builder, params);
        }
        builder.endArray();
        builder.endObject();
        return builder;
    }
}
