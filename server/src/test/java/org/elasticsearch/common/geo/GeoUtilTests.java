/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.common.geo;

import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;

public class GeoUtilTests extends ESTestCase {

    public void testPrecisionParser() throws IOException {
        assertEquals(10, parsePrecision(builder -> builder.field("test", 10)));
        assertEquals(10, parsePrecision(builder -> builder.field("test", 10.2)));
        assertEquals(6, parsePrecision(builder -> builder.field("test", "6")));
        assertEquals(7, parsePrecision(builder -> builder.field("test", "1km")));
        assertEquals(7, parsePrecision(builder -> builder.field("test", "1.1km")));
    }

    public void testIncorrectPrecisionParser() {
        expectThrows(NumberFormatException.class, () -> parsePrecision(builder -> builder.field("test", "10.1.1.1")));
        expectThrows(NumberFormatException.class, () -> parsePrecision(builder -> builder.field("test", "364.4smoots")));
        assertEquals(
            "precision too high [0.01mm]",
            expectThrows(IllegalArgumentException.class, () -> parsePrecision(builder -> builder.field("test", "0.01mm"))).getMessage()
        );
    }

    /**
     * Invokes GeoUtils.parsePrecision parser on the value generated by tokenGenerator
     * <p>
     * The supplied tokenGenerator should generate a single field that contains the precision in
     * one of the supported formats or malformed precision value if error handling is tested. The
     * method return the parsed value or throws an exception, if precision value is malformed.
     */
    private int parsePrecision(CheckedConsumer<XContentBuilder, IOException> tokenGenerator) throws IOException {
        XContentBuilder builder = jsonBuilder().startObject();
        tokenGenerator.accept(builder);
        builder.endObject();
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, BytesReference.bytes(builder))) {
            assertEquals(XContentParser.Token.START_OBJECT, parser.nextToken()); // {
            assertEquals(XContentParser.Token.FIELD_NAME, parser.nextToken()); // field name
            assertTrue(parser.nextToken().isValue()); // field value
            int precision = GeoUtils.parsePrecision(parser);
            assertEquals(XContentParser.Token.END_OBJECT, parser.nextToken()); // }
            assertNull(parser.nextToken()); // no more tokens
            return precision;
        }
    }
}
