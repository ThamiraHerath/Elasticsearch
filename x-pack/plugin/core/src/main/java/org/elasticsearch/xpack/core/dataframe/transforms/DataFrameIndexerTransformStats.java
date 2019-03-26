/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.dataframe.transforms;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.core.dataframe.DataFrameField;
import org.elasticsearch.xpack.core.indexing.IndexerJobStats;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.common.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;

public class DataFrameIndexerTransformStats extends IndexerJobStats {
    public static final String NAME = "data_frame_indexer_transform_stats";
    public static ParseField NUM_PAGES = new ParseField("pages_processed");
    public static ParseField NUM_INPUT_DOCUMENTS = new ParseField("documents_processed");
    public static ParseField NUM_OUTPUT_DOCUMENTS = new ParseField("documents_indexed");
    public static ParseField NUM_INVOCATIONS = new ParseField("trigger_count");
    public static ParseField INDEX_TIME_IN_MS = new ParseField("index_time_in_ms");
    public static ParseField SEARCH_TIME_IN_MS = new ParseField("search_time_in_ms");
    public static ParseField INDEX_TOTAL = new ParseField("index_total");
    public static ParseField SEARCH_TOTAL = new ParseField("search_total");
    public static ParseField SEARCH_FAILURES = new ParseField("search_failures");
    public static ParseField INDEX_FAILURES = new ParseField("index_failures");

    public static final ConstructingObjectParser<DataFrameIndexerTransformStats, Void> PARSER = new ConstructingObjectParser<>(
            NAME, args -> new DataFrameIndexerTransformStats((String) args[0], (long) args[1], (long) args[2], (long) args[3],
            (long) args[4], (long) args[5], (long) args[6], (long) args[7], (long) args[8], (long) args[9], (long) args[10]));

    static {
        PARSER.declareString(optionalConstructorArg(), DataFrameField.ID);
        PARSER.declareLong(constructorArg(), NUM_PAGES);
        PARSER.declareLong(constructorArg(), NUM_INPUT_DOCUMENTS);
        PARSER.declareLong(constructorArg(), NUM_OUTPUT_DOCUMENTS);
        PARSER.declareLong(constructorArg(), NUM_INVOCATIONS);
        PARSER.declareLong(constructorArg(), INDEX_TIME_IN_MS);
        PARSER.declareLong(constructorArg(), SEARCH_TIME_IN_MS);
        PARSER.declareLong(constructorArg(), INDEX_TOTAL);
        PARSER.declareLong(constructorArg(), SEARCH_TOTAL);
        PARSER.declareLong(constructorArg(), INDEX_FAILURES);
        PARSER.declareLong(constructorArg(), SEARCH_FAILURES);
        PARSER.declareString(optionalConstructorArg(), DataFrameField.INDEX_DOC_TYPE);
    }

    private final String transformId;

    /**
     * Certain situations call for a null transform ID, e.g. when merging many different transforms for statistics gather.
     *
     * @return new DataFrameIndexerTransformStats with empty stats and a null transformId
     */
    public static DataFrameIndexerTransformStats withNullTransformId() {
        return new DataFrameIndexerTransformStats((String) null);
    }

    public DataFrameIndexerTransformStats(String transformId) {
        super();
        this.transformId = transformId;
    }

    public DataFrameIndexerTransformStats(String transformId, long numPages, long numInputDocuments, long numOuputDocuments,
                                          long numInvocations, long indexTime, long searchTime, long indexTotal, long searchTotal,
                                          long indexFailures, long searchFailures) {
        super(numPages, numInputDocuments, numOuputDocuments, numInvocations, indexTime, searchTime, indexTotal, searchTotal, indexFailures,
                searchFailures);
        this.transformId = transformId;
    }

    public DataFrameIndexerTransformStats(DataFrameIndexerTransformStats other) {
        this(other.transformId, other.numPages, other.numInputDocuments, other.numOuputDocuments, other.numInvocations,
            other.indexTime, other.searchTime, other.indexTotal, other.searchTotal, other.indexFailures, other.searchFailures);
    }

    public DataFrameIndexerTransformStats(StreamInput in) throws IOException {
        super(in);
        transformId = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeOptionalString(transformId);
    }

    /**
     * Get the persisted stats document name from the Data Frame Transformer Id.
     *
     * @return The id of document the where the transform stats are persisted
     */
    public static String documentId(String transformId) {
        return NAME + "-" + transformId;
    }

    @Nullable
    public String getTransformId() {
        return transformId;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(NUM_PAGES.getPreferredName(), numPages);
        builder.field(NUM_INPUT_DOCUMENTS.getPreferredName(), numInputDocuments);
        builder.field(NUM_OUTPUT_DOCUMENTS.getPreferredName(), numOuputDocuments);
        builder.field(NUM_INVOCATIONS.getPreferredName(), numInvocations);
        builder.field(INDEX_TIME_IN_MS.getPreferredName(), indexTime);
        builder.field(INDEX_TOTAL.getPreferredName(), indexTotal);
        builder.field(INDEX_FAILURES.getPreferredName(), indexFailures);
        builder.field(SEARCH_TIME_IN_MS.getPreferredName(), searchTime);
        builder.field(SEARCH_TOTAL.getPreferredName(), searchTotal);
        builder.field(SEARCH_FAILURES.getPreferredName(), searchFailures);
        if (params.paramAsBoolean(DataFrameField.FOR_INTERNAL_STORAGE, false)) {
            if (transformId != null) {
                builder.field(DataFrameField.ID.getPreferredName(), transformId);
            }
            builder.field(DataFrameField.INDEX_DOC_TYPE.getPreferredName(), NAME);
        }
        builder.endObject();
        return builder;
    }

    public DataFrameIndexerTransformStats merge(DataFrameIndexerTransformStats other) {
        numPages += other.numPages;
        numInputDocuments += other.numInputDocuments;
        numOuputDocuments += other.numOuputDocuments;
        numInvocations += other.numInvocations;
        indexTime += other.indexTime;
        searchTime += other.searchTime;
        indexTotal += other.indexTotal;
        searchTotal += other.searchTotal;
        indexFailures += other.indexFailures;
        searchFailures += other.searchFailures;

        return this;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DataFrameIndexerTransformStats that = (DataFrameIndexerTransformStats) other;

        return Objects.equals(this.transformId, that.transformId)
            && Objects.equals(this.numPages, that.numPages)
            && Objects.equals(this.numInputDocuments, that.numInputDocuments)
            && Objects.equals(this.numOuputDocuments, that.numOuputDocuments)
            && Objects.equals(this.numInvocations, that.numInvocations)
            && Objects.equals(this.indexTime, that.indexTime)
            && Objects.equals(this.searchTime, that.searchTime)
            && Objects.equals(this.indexFailures, that.indexFailures)
            && Objects.equals(this.searchFailures, that.searchFailures)
            && Objects.equals(this.indexTotal, that.indexTotal)
            && Objects.equals(this.searchTotal, that.searchTotal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transformId, numPages, numInputDocuments, numOuputDocuments, numInvocations,
            indexTime, searchTime, indexFailures, searchFailures, indexTotal, searchTotal);
    }

    public static DataFrameIndexerTransformStats fromXContent(XContentParser parser) {
        try {
            return PARSER.parse(parser, null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
