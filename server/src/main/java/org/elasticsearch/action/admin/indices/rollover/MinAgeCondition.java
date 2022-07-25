/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.indices.rollover;

import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;

/**
 * Condition for index minimum age. Evaluates to <code>true</code>
 * when the index is at least {@link #value} old
 */
public class MinAgeCondition extends Condition<TimeValue> {
    public static final String NAME = "min_age";

    public MinAgeCondition(TimeValue value) {
        super(NAME);
        this.value = value;
    }

    public MinAgeCondition(StreamInput in) throws IOException {
        super(NAME);
        this.value = TimeValue.timeValueMillis(in.readLong());
    }

    @Override
    public Result evaluate(final Stats stats) {
        long indexAge = System.currentTimeMillis() - stats.indexCreated();
        return new Result(this, this.value.getMillis() <= indexAge);
    }

    @Override
    public boolean isRequired() {
        return true;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        // While we technically could serialize this with out.writeTimeValue(...), that would
        // require doing the song and dance around backwards compatibility for this value. Since
        // in this case the deserialized version is not displayed to a user, it's okay to simply use
        // milliseconds. It's possible to lose precision if someone were to say, specify 50
        // nanoseconds, however, in that case, their max age is indistinguishable from 0
        // milliseconds regardless.
        out.writeLong(value.getMillis());
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder.field(NAME, value.getStringRep());
    }

    public static MinAgeCondition fromXContent(XContentParser parser) throws IOException {
        if (parser.nextToken() == XContentParser.Token.VALUE_STRING) {
            return new MinAgeCondition(TimeValue.parseTimeValue(parser.text(), NAME));
        } else {
            throw new IllegalArgumentException("invalid token: " + parser.currentToken());
        }
    }

    @Override
    boolean includedInVersion(Version version) {
        return version.onOrAfter(Version.V_8_4_0);
    }
}
