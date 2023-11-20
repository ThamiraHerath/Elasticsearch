/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.connector;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

public class ConnectorScheduling implements Writeable, ToXContentObject {

    private final ScheduleConfig accessControl;
    private final ScheduleConfig full;
    private final ScheduleConfig incremental;

    public static final ParseField ACCESS_CONTROL_FIELD = new ParseField("access_control");
    public static final ParseField FULL_FIELD = new ParseField("full");
    public static final ParseField INCREMENTAL_FIELD = new ParseField("incremental");

    private ConnectorScheduling(ScheduleConfig accessControl, ScheduleConfig full, ScheduleConfig incremental) {
        this.accessControl = accessControl;
        this.full = full;
        this.incremental = incremental;
    }

    public ConnectorScheduling(StreamInput in) throws IOException {
        this.accessControl = new ScheduleConfig(in);
        this.full = new ScheduleConfig(in);
        this.incremental = new ScheduleConfig(in);
    }

    private static final ConstructingObjectParser<ConnectorScheduling, Void> PARSER = new ConstructingObjectParser<>(
        "connector_scheduling",
        true,
        args -> new Builder().setAccessControl((ScheduleConfig) args[0])
            .setFull((ScheduleConfig) args[1])
            .setIncremental((ScheduleConfig) args[2])
            .build()
    );

    static {
        PARSER.declareField(
            constructorArg(),
            (p, c) -> ScheduleConfig.fromXContent(p),
            ACCESS_CONTROL_FIELD,
            ObjectParser.ValueType.OBJECT
        );
        PARSER.declareField(constructorArg(), (p, c) -> ScheduleConfig.fromXContent(p), FULL_FIELD, ObjectParser.ValueType.OBJECT);
        PARSER.declareField(constructorArg(), (p, c) -> ScheduleConfig.fromXContent(p), INCREMENTAL_FIELD, ObjectParser.ValueType.OBJECT);
    }

    public static ConnectorScheduling fromXContentBytes(BytesReference source, XContentType xContentType) {
        try (XContentParser parser = XContentHelper.createParser(XContentParserConfiguration.EMPTY, source, xContentType)) {
            return ConnectorScheduling.fromXContent(parser);
        } catch (IOException e) {
            throw new ElasticsearchParseException("Failed to parse: " + source.utf8ToString(), e);
        }
    }

    public static ConnectorScheduling fromXContent(XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        {
            builder.field(ACCESS_CONTROL_FIELD.getPreferredName(), accessControl);
            builder.field(FULL_FIELD.getPreferredName(), full);
            builder.field(INCREMENTAL_FIELD.getPreferredName(), incremental);
        }
        builder.endObject();
        return builder;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        accessControl.writeTo(out);
        full.writeTo(out);
        incremental.writeTo(out);
    }

    public static class Builder {

        private ScheduleConfig accessControl;
        private ScheduleConfig full;
        private ScheduleConfig incremental;

        public Builder setAccessControl(ScheduleConfig accessControl) {
            this.accessControl = accessControl;
            return this;
        }

        public Builder setFull(ScheduleConfig full) {
            this.full = full;
            return this;
        }

        public Builder setIncremental(ScheduleConfig incremental) {
            this.incremental = incremental;
            return this;
        }

        public ConnectorScheduling build() {
            return new ConnectorScheduling(accessControl, full, incremental);
        }
    }

    public static class ScheduleConfig implements Writeable, ToXContentObject {
        private final boolean enabled;
        private final String interval;

        public static final ParseField ENABLED_FIELD = new ParseField("enabled");
        public static final ParseField INTERVAL_FIELD = new ParseField("interval");

        private ScheduleConfig(boolean enabled, String interval) {
            this.enabled = enabled;
            this.interval = interval;
        }

        public ScheduleConfig(StreamInput in) throws IOException {
            this.enabled = in.readBoolean();
            this.interval = in.readString();
        }

        private static final ConstructingObjectParser<ScheduleConfig, Void> PARSER = new ConstructingObjectParser<>(
            "schedule_config",
            true,
            args -> new Builder().setEnabled((boolean) args[0]).setInterval((String) args[1]).build()
        );

        static {
            PARSER.declareBoolean(constructorArg(), ENABLED_FIELD);
            PARSER.declareString(constructorArg(), INTERVAL_FIELD);
        }

        public static ScheduleConfig fromXContent(XContentParser parser) throws IOException {
            return PARSER.parse(parser, null);
        }

        public static ConstructingObjectParser<ScheduleConfig, Void> getParser() {
            return PARSER;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            {
                builder.field(ENABLED_FIELD.getPreferredName(), enabled);
                builder.field(INTERVAL_FIELD.getPreferredName(), interval);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeBoolean(enabled);
            out.writeString(interval);
        }

        public static class Builder {

            private boolean enabled;
            private String interval;

            public Builder setEnabled(boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            public Builder setInterval(String interval) {
                this.interval = interval;
                return this;
            }

            public ScheduleConfig build() {
                return new ScheduleConfig(enabled, interval);
            }
        }
    }
}
