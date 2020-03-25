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
package org.elasticsearch.cluster.metadata;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.contains;

public class IndexTemplateMetaDataTests extends ESTestCase {

    public void testIndexTemplateMetaDataXContentRoundTrip() throws Exception {

        String template = "{\"index_patterns\" : [ \".test-*\" ],\"order\" : 1000," +
            "\"settings\" : {\"number_of_shards\" : 1,\"number_of_replicas\" : 0}," +
            "\"mappings\" : {\"doc\" :" +
            "{\"properties\":{\"" +
            randomAlphaOfLength(10) + "\":{\"type\":\"text\"},\"" +
            randomAlphaOfLength(10) + "\":{\"type\":\"keyword\"}}" +
            "}}}";

        BytesReference templateBytes = new BytesArray(template);
        final IndexTemplateMetaData indexTemplateMetaData;
        try (XContentParser parser = XContentHelper.createParser(NamedXContentRegistry.EMPTY,
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION, templateBytes, XContentType.JSON)) {
            indexTemplateMetaData = IndexTemplateMetaData.fromXContent(parser, "test");
        }

        final BytesReference templateBytesRoundTrip;
        final ToXContent.Params params = new ToXContent.MapParams(
            Map.of(IndexTemplateMetaData.INCLUDE_TYPE_NAME, "true", IndexTemplateMetaData.INCLUDE_TEMPLATE_NAME, "false"));
        try (XContentBuilder builder = XContentBuilder.builder(JsonXContent.jsonXContent)) {
            builder.startObject();
            indexTemplateMetaData.toXContent(builder, params);
            builder.endObject();
            templateBytesRoundTrip = BytesReference.bytes(builder);
        }

        final IndexTemplateMetaData indexTemplateMetaDataRoundTrip;
        try (XContentParser parser = XContentHelper.createParser(NamedXContentRegistry.EMPTY,
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION, templateBytesRoundTrip, XContentType.JSON)) {
            indexTemplateMetaDataRoundTrip = IndexTemplateMetaData.fromXContent(parser, "test");
        }
        assertThat(indexTemplateMetaData, equalTo(indexTemplateMetaDataRoundTrip));
    }

    public void testValidateInvalidIndexPatterns() throws Exception {
        final IllegalArgumentException emptyPatternError = expectThrows(IllegalArgumentException.class, () -> {
            new IndexTemplateMetaData(randomRealisticUnicodeOfLengthBetween(5, 10), randomInt(), randomInt(),
                Collections.emptyList(), Settings.EMPTY, ImmutableOpenMap.of(), ImmutableOpenMap.of());
        });
        assertThat(emptyPatternError.getMessage(), equalTo("Index patterns must not be null or empty; got []"));

        final IllegalArgumentException nullPatternError = expectThrows(IllegalArgumentException.class, () -> {
            new IndexTemplateMetaData(randomRealisticUnicodeOfLengthBetween(5, 10), randomInt(), randomInt(),
                null, Settings.EMPTY, ImmutableOpenMap.of(), ImmutableOpenMap.of());
        });
        assertThat(nullPatternError.getMessage(), equalTo("Index patterns must not be null or empty; got null"));

        final String templateWithEmptyPattern = "{\"index_patterns\" : [],\"order\" : 1000," +
            "\"settings\" : {\"number_of_shards\" : 10,\"number_of_replicas\" : 1}," +
            "\"mappings\" : {\"doc\" :" +
            "{\"properties\":{\"" +
            randomAlphaOfLength(10) + "\":{\"type\":\"text\"},\"" +
            randomAlphaOfLength(10) + "\":{\"type\":\"keyword\"}}" +
            "}}}";
        try (XContentParser parser =
                 XContentHelper.createParser(NamedXContentRegistry.EMPTY,
                     DeprecationHandler.THROW_UNSUPPORTED_OPERATION, new BytesArray(templateWithEmptyPattern), XContentType.JSON)) {
            final IllegalArgumentException ex = expectThrows(IllegalArgumentException.class,
                () -> IndexTemplateMetaData.fromXContent(parser, randomAlphaOfLengthBetween(1, 100)));
            assertThat(ex.getMessage(), equalTo("Index patterns must not be null or empty; got []"));
        }

        final String templateWithoutPattern = "{\"order\" : 1000," +
            "\"settings\" : {\"number_of_shards\" : 10,\"number_of_replicas\" : 1}," +
            "\"mappings\" : {\"doc\" :" +
            "{\"properties\":{\"" +
            randomAlphaOfLength(10) + "\":{\"type\":\"text\"},\"" +
            randomAlphaOfLength(10) + "\":{\"type\":\"keyword\"}}" +
            "}}}";
        try (XContentParser parser =
                 XContentHelper.createParser(NamedXContentRegistry.EMPTY,
                     DeprecationHandler.THROW_UNSUPPORTED_OPERATION, new BytesArray(templateWithoutPattern), XContentType.JSON)) {
            final IllegalArgumentException ex = expectThrows(IllegalArgumentException.class,
                () -> IndexTemplateMetaData.fromXContent(parser, randomAlphaOfLengthBetween(1, 100)));
            assertThat(ex.getMessage(), equalTo("Index patterns must not be null or empty; got null"));
        }
    }

    public void testParseTemplateWithAliases() throws Exception {
        String templateInJSON = "{\"aliases\": {\"log\":{}}, \"index_patterns\": [\"pattern-1\"]}";
        try (XContentParser parser =
                 XContentHelper.createParser(NamedXContentRegistry.EMPTY,
                     DeprecationHandler.THROW_UNSUPPORTED_OPERATION, new BytesArray(templateInJSON), XContentType.JSON)) {
            IndexTemplateMetaData template = IndexTemplateMetaData.fromXContent(parser, randomAlphaOfLengthBetween(1, 100));
            assertThat(template.aliases().containsKey("log"), equalTo(true));
            assertThat(template.patterns(), contains("pattern-1"));
        }
    }

    public void testXContentFromV7Nodes() throws Exception {
        String json = "{\"mappings\":[{\"_doc\":{}}], \"index_patterns\": [\"pattern-1\"]}";
        try (XContentParser parser = XContentHelper.createParser(NamedXContentRegistry.EMPTY,
            DeprecationHandler.THROW_UNSUPPORTED_OPERATION, new BytesArray(json), XContentType.JSON)) {
            IndexTemplateMetaData template = IndexTemplateMetaData.fromXContent(parser, "");
            assertNotNull(template.mappings());
        }
    }

    public void testFromToXContent() throws Exception {
        String templateName = randomUnicodeOfCodepointLengthBetween(1, 10);
        IndexTemplateMetaData.Builder templateBuilder = IndexTemplateMetaData.builder(templateName);
        templateBuilder.patterns(Arrays.asList("pattern-1"));
        int numAlias = between(0, 5);
        for (int i = 0; i < numAlias; i++) {
            AliasMetaData.Builder alias = AliasMetaData.builder(randomRealisticUnicodeOfLengthBetween(1, 100));
            if (randomBoolean()) {
                alias.indexRouting(randomRealisticUnicodeOfLengthBetween(1, 100));
            }
            if (randomBoolean()) {
                alias.searchRouting(randomRealisticUnicodeOfLengthBetween(1, 100));
            }
            templateBuilder.putAlias(alias);
        }
        if (randomBoolean()) {
            templateBuilder.settings(Settings.builder().put("index.setting-1", randomLong()));
            templateBuilder.settings(Settings.builder().put("index.setting-2", randomTimeValue()));
        }
        if (randomBoolean()) {
            templateBuilder.order(randomInt());
        }
        if (randomBoolean()) {
            templateBuilder.version(between(0, 100));
        }
        if (randomBoolean()) {
            templateBuilder.putMapping("doc", "{\"doc\":{\"properties\":{\"type\":\"text\"}}}");
        }
        IndexTemplateMetaData template = templateBuilder.build();
        final ToXContent.Params params = new ToXContent.MapParams(
            Map.of(IndexTemplateMetaData.INCLUDE_TYPE_NAME, "true", IndexTemplateMetaData.INCLUDE_TEMPLATE_NAME, "false"));
        XContentBuilder builder = XContentBuilder.builder(randomFrom(XContentType.JSON.xContent()));
        builder.startObject();
        template.toXContent(builder, params);
        builder.endObject();
        try (XContentParser parser = createParser(shuffleXContent(builder))) {
            IndexTemplateMetaData parsed = IndexTemplateMetaData.fromXContent(parser, templateName);
            assertThat(parsed, equalTo(template));
        }
    }
}
