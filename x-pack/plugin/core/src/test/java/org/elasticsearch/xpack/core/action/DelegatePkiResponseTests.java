/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.action;

import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.security.action.DelegatePkiResponse;

import static org.hamcrest.Matchers.is;

public class DelegatePkiResponseTests extends ESTestCase {

    public void testSerialization() throws Exception {
        DelegatePkiResponse response = new DelegatePkiResponse(randomAlphaOfLengthBetween(0, 10),
                TimeValue.parseTimeValue(randomTimeValue(), getClass().getSimpleName() + ".expiresIn"));
        try (BytesStreamOutput output = new BytesStreamOutput()) {
            response.writeTo(output);
            try (StreamInput input = output.bytes().streamInput()) {
                DelegatePkiResponse serialized = new DelegatePkiResponse(input);
                assertThat(response.getTokenString(), is(serialized.getTokenString()));
                assertThat(response.getExpiresIn(), is(serialized.getExpiresIn()));
                assertThat(response, is(serialized));
            }
        }
    }
}
