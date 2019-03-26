/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.dataframe.transforms;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.Objects;

/**
 * Holds information about checkpointing regarding
 *  - the current checkpoint
 *  - the in progress checkpoint
 *  - the current state of the source
 */
public class DataFrameTransformCheckpointStats implements Writeable, ToXContentObject {

    public static final ParseField CURRENT_CHECKPOINT = new ParseField("current");
    public static final ParseField IN_PROGRESS_CHECKPOINT = new ParseField("in_progress");
    public static final ParseField OPERATIONS_BEHIND = new ParseField("operations_behind");

    private final SingleCheckpointStats current;
    private final SingleCheckpointStats inProgress;
    private final long operationsBehind;

    private static final ConstructingObjectParser<DataFrameTransformCheckpointStats, Void> LENIENT_PARSER = new ConstructingObjectParser<>(
            "data_frame_transform_checkpoint_stats", true, a -> {
                long behind = a[2] == null ? 0L : (Long) a[2];

                return new DataFrameTransformCheckpointStats(
                        a[0] == null ? SingleCheckpointStats.EMPTY : (SingleCheckpointStats) a[0],
                        a[1] == null ? SingleCheckpointStats.EMPTY : (SingleCheckpointStats) a[1],
                        behind);
                });

    static {
        LENIENT_PARSER.declareObject(ConstructingObjectParser.optionalConstructorArg(), (p, c) -> SingleCheckpointStats.fromXContent(p),
                CURRENT_CHECKPOINT);
        LENIENT_PARSER.declareObject(ConstructingObjectParser.optionalConstructorArg(), (p, c) -> SingleCheckpointStats.fromXContent(p),
                IN_PROGRESS_CHECKPOINT);
        LENIENT_PARSER.declareLong(ConstructingObjectParser.optionalConstructorArg(), OPERATIONS_BEHIND);
    }

    public DataFrameTransformCheckpointStats() {
        this(SingleCheckpointStats.EMPTY, SingleCheckpointStats.EMPTY, 0L);
    }

    public DataFrameTransformCheckpointStats(SingleCheckpointStats current, SingleCheckpointStats inProgress,
            long operationsBehind) {
        this.current = Objects.requireNonNull(current);
        this.inProgress = Objects.requireNonNull(inProgress);
        this.operationsBehind = operationsBehind;
    }

    public DataFrameTransformCheckpointStats(StreamInput in) throws IOException {
        current = new SingleCheckpointStats(in);
        inProgress = new SingleCheckpointStats(in);
        operationsBehind = in.readLong();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (current.getTimestampMillis() > 0) {
            builder.field(CURRENT_CHECKPOINT.getPreferredName(), current);
        }
        if (inProgress.getTimestampMillis() > 0) {
            builder.field(IN_PROGRESS_CHECKPOINT.getPreferredName(), inProgress);
        }
        if (operationsBehind > 0) {
            builder.field(OPERATIONS_BEHIND.getPreferredName(), operationsBehind);
        }

        builder.endObject();
        return builder;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        current.writeTo(out);
        inProgress.writeTo(out);
        out.writeLong(operationsBehind);
    }

    public static DataFrameTransformCheckpointStats fromXContent(XContentParser p) {
        return LENIENT_PARSER.apply(p, null);
    }

    @Override
    public int hashCode() {
        return Objects.hash(current, inProgress, operationsBehind);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DataFrameTransformCheckpointStats that = (DataFrameTransformCheckpointStats) other;

        return Objects.equals(this.current, that.current) &&
                Objects.equals(this.inProgress, that.inProgress) &&
                this.operationsBehind == that.operationsBehind;
    }

}
