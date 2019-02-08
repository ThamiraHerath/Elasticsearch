/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.dataframe.transforms.pivot;

import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.dataframe.transforms.pivot.SingleGroupSource.Type;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;

/*
 * Wraps a single group for groupby
 */
public class GroupConfig implements Writeable, ToXContentObject {

    private final String destinationFieldName;
    private final SingleGroupSource.Type groupType;
    private final SingleGroupSource<?> groupSource;

    public GroupConfig(final String destinationFieldName, final SingleGroupSource.Type groupType, final SingleGroupSource<?> groupSource) {
        this.destinationFieldName = Objects.requireNonNull(destinationFieldName);
        this.groupType = Objects.requireNonNull(groupType);
        this.groupSource = Objects.requireNonNull(groupSource);
    }

    public GroupConfig(StreamInput in) throws IOException {
        destinationFieldName = in.readString();
        groupType = Type.fromId(in.readByte());
        switch (groupType) {
        case TERMS:
            groupSource = in.readOptionalWriteable(TermsGroupSource::new);
            break;
        case HISTOGRAM:
            groupSource = in.readOptionalWriteable(HistogramGroupSource::new);
            break;
        case DATE_HISTOGRAM:
            groupSource = in.readOptionalWriteable(DateHistogramGroupSource::new);
            break;
        default:
            throw new IOException("Unknown group type");
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(destinationFieldName);
        out.writeByte(groupType.getId());
        out.writeOptionalWriteable(groupSource);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.startObject(destinationFieldName);

        builder.field(groupType.value(), groupSource);
        builder.endObject();
        builder.endObject();
        return builder;
    }

    public String getDestinationFieldName() {
        return destinationFieldName;
    }

    public SingleGroupSource<?> getGroupSource() {
        return groupSource;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        final GroupConfig that = (GroupConfig) other;

        return Objects.equals(this.destinationFieldName, that.destinationFieldName) && Objects.equals(this.groupType, that.groupType)
                && Objects.equals(this.groupSource, that.groupSource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(destinationFieldName, groupType, groupSource);
    }

    public static GroupConfig fromXContent(final XContentParser parser, boolean lenient) throws IOException {
        String destinationFieldName;
        Type groupType;
        SingleGroupSource<?> groupSource;

        // be parsing friendly, whether the token needs to be advanced or not (similar to what ObjectParser does)
        XContentParser.Token token;
        if (parser.currentToken() == XContentParser.Token.START_OBJECT) {
            token = parser.currentToken();
        } else {
            token = parser.nextToken();
            if (token != XContentParser.Token.START_OBJECT) {
                throw new ParsingException(parser.getTokenLocation(), "Failed to parse object: Expected START_OBJECT but was: " + token);
            }
        }
        token = parser.nextToken();
        ensureExpectedToken(XContentParser.Token.FIELD_NAME, token, parser::getTokenLocation);
        destinationFieldName = parser.currentName();
        token = parser.nextToken();
        ensureExpectedToken(XContentParser.Token.START_OBJECT, token, parser::getTokenLocation);
        token = parser.nextToken();
        ensureExpectedToken(XContentParser.Token.FIELD_NAME, token, parser::getTokenLocation);
        groupType =  SingleGroupSource.Type.valueOf(parser.currentName().toUpperCase(Locale.ROOT));

        token = parser.nextToken();
        ensureExpectedToken(XContentParser.Token.START_OBJECT, token, parser::getTokenLocation);

        switch (groupType) {
            case TERMS:
                groupSource = TermsGroupSource.fromXContent(parser, lenient);
                break;
            case HISTOGRAM:
                groupSource = HistogramGroupSource.fromXContent(parser, lenient);
                break;
            case DATE_HISTOGRAM:
                groupSource = DateHistogramGroupSource.fromXContent(parser, lenient);
                break;
            default:
                throw new ParsingException(parser.getTokenLocation(), "invalid grouping type: " + groupType);
        }

        parser.nextToken();
        parser.nextToken();

        return new GroupConfig(destinationFieldName, groupType, groupSource);
    }
}
