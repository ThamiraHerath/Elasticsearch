/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.cluster.Diff;
import org.elasticsearch.cluster.SimpleDiffable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Objects;

/**
 * Holds data stream dedicated configuration options such as failure store, (in the future lifecycle). Currently, it
 * supports the following configurations:
 * - failure store
 */
public class DataStreamOptions implements SimpleDiffable<DataStreamOptions>, ToXContentObject {

    public static final ParseField FAILURE_STORE_FIELD = new ParseField("failure_store");
    public static final DataStreamOptions FAILURE_STORE_ENABLED = new DataStreamOptions(new DataStreamFailureStore(true));
    public static final DataStreamOptions FAILURE_STORE_DISABLED = new DataStreamOptions(new DataStreamFailureStore(false));
    public static final DataStreamOptions EMPTY = new DataStreamOptions();

    public static final ConstructingObjectParser<DataStreamOptions, Void> PARSER = new ConstructingObjectParser<>(
        "options",
        false,
        (args, unused) -> new DataStreamOptions((DataStreamFailureStore) args[0])
    );
    @Nullable
    private final DataStreamFailureStore failureStore;

    public DataStreamOptions(@Nullable DataStreamFailureStore failureStore) {
        this.failureStore = failureStore;
    }

    static {
        PARSER.declareObjectOrNull(
            ConstructingObjectParser.optionalConstructorArg(),
            (p, c) -> DataStreamFailureStore.fromXContent(p),
            DataStreamFailureStore.NULL,
            FAILURE_STORE_FIELD
        );
    }

    public DataStreamOptions() {
        this(null);
    }

    public static DataStreamOptions read(StreamInput in) throws IOException {
        return new DataStreamOptions(in.readOptionalWriteable(DataStreamFailureStore::read));
    }

    public static Diff<DataStreamOptions> readDiffFrom(StreamInput in) throws IOException {
        return SimpleDiffable.readDiffFrom(DataStreamOptions::read, in);
    }

    public boolean isEmpty() {
        return this.equals(EMPTY);
    }

    /**
     * Determines if this data stream has its failure store enabled or not. Currently, the failure store
     * is enabled only when a user has explicitly requested it.
     * @return true, if the user has explicitly enabled the failure store.
     */
    public boolean isFailureStoreEnabled() {
        return failureStore != null && Boolean.TRUE.equals(failureStore.enabled());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalWriteable(failureStore);
    }

    @Override
    public String toString() {
        return Strings.toString(this, true, true);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (failureStore != null) {
            builder.field(FAILURE_STORE_FIELD.getPreferredName(), failureStore);
        }
        builder.endObject();
        return builder;
    }

    public static DataStreamOptions fromXContent(XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }

    /**
     * Creates a class that composes the different fields of the data stream options and normalises explicitly nullified fields.
     */
    public static Composer composer(DataStreamOptions options) {
        return new Composer(options);
    }

    @Nullable
    public DataStreamFailureStore failureStore() {
        return failureStore;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (DataStreamOptions) obj;
        return Objects.equals(this.failureStore, that.failureStore);
    }

    @Override
    public int hashCode() {
        return Objects.hash(failureStore);
    }

    /**
     * Composes different data stream options to a normalised final value.
     */
    public static class Composer {
        private DataStreamFailureStore failureStore;

        private Composer(DataStreamOptions options) {
            failureStore = options.failureStore == null || options.failureStore.isNullified() ? null : options.failureStore;
        }

        public void apply(DataStreamOptions dataStreamOptions) {
            if (dataStreamOptions.failureStore != null) {
                this.failureStore = dataStreamOptions.failureStore.isNullified() ? null : dataStreamOptions.failureStore;
            }
        }

        public DataStreamOptions compose() {
            return new DataStreamOptions(failureStore);
        }
    }
}
