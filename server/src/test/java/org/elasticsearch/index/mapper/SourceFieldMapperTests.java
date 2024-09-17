/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexVersions;
import org.elasticsearch.test.index.IndexVersionUtils;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentLocation;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.indices.recovery.RecoverySettings.INDICES_RECOVERY_SOURCE_ENABLED_SETTING;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

public class SourceFieldMapperTests extends MetadataMapperTestCase {

    @Override
    protected String fieldName() {
        return SourceFieldMapper.NAME;
    }

    @Override
    protected boolean isConfigurable() {
        return true;
    }

    @Override
    protected void registerParameters(ParameterChecker checker) throws IOException {
        checker.registerConflictCheck(
            "enabled",
            topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("enabled", false).endObject()),
            topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("enabled", true).endObject())
        );
        checker.registerUpdateCheck(
            topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("enabled", true).endObject()),
            topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("enabled", false).endObject()),
            dm -> assertFalse(dm.metadataMapper(SourceFieldMapper.class).enabled())
        );
        checker.registerUpdateCheck(
            topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("mode", "stored").endObject()),
            topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("mode", "synthetic").endObject()),
            dm -> assertTrue(dm.metadataMapper(SourceFieldMapper.class).isSynthetic())
        );
        checker.registerConflictCheck("includes", b -> b.array("includes", "foo*"));
        checker.registerConflictCheck("excludes", b -> b.array("excludes", "foo*"));
        checker.registerConflictCheck(
            "mode",
            topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("mode", "synthetic").endObject()),
            topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("mode", "stored").endObject())
        );
    }

    public void testNoFormat() throws Exception {

        DocumentMapper documentMapper = createDocumentMapper(topMapping(b -> b.startObject("_source").endObject()));
        ParsedDocument doc = documentMapper.parse(
            new SourceToParse(
                "1",
                BytesReference.bytes(XContentFactory.jsonBuilder().startObject().field("field", "value").endObject()),
                XContentType.JSON
            )
        );

        assertThat(XContentHelper.xContentType(doc.source()), equalTo(XContentType.JSON));

        doc = documentMapper.parse(
            new SourceToParse(
                "1",
                BytesReference.bytes(XContentFactory.smileBuilder().startObject().field("field", "value").endObject()),
                XContentType.SMILE
            )
        );

        assertThat(XContentHelper.xContentType(doc.source()), equalTo(XContentType.SMILE));
    }

    public void testIncludes() throws Exception {
        DocumentMapper documentMapper = createDocumentMapper(
            topMapping(b -> b.startObject("_source").array("includes", "path1*").endObject())
        );

        ParsedDocument doc = documentMapper.parse(source(b -> {
            b.startObject("path1").field("field1", "value1").endObject();
            b.startObject("path2").field("field2", "value2").endObject();
        }));

        IndexableField sourceField = doc.rootDoc().getField("_source");
        Map<String, Object> sourceAsMap;
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, new BytesArray(sourceField.binaryValue()))) {
            sourceAsMap = parser.map();
        }
        assertThat(sourceAsMap.containsKey("path1"), equalTo(true));
        assertThat(sourceAsMap.containsKey("path2"), equalTo(false));
    }

    public void testDuplicatedIncludes() throws Exception {
        DocumentMapper documentMapper = createDocumentMapper(
            topMapping(b -> b.startObject("_source").array("includes", "path1", "path1").endObject())
        );

        ParsedDocument doc = documentMapper.parse(source(b -> {
            b.startObject("path1").field("field1", "value1").endObject();
            b.startObject("path2").field("field2", "value2").endObject();
        }));

        IndexableField sourceField = doc.rootDoc().getField("_source");
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, new BytesArray(sourceField.binaryValue()))) {
            assertEquals(Map.of("path1", Map.of("field1", "value1")), parser.map());
        }
    }

    public void testExcludes() throws Exception {
        DocumentMapper documentMapper = createDocumentMapper(
            topMapping(b -> b.startObject("_source").array("excludes", "path1*").endObject())
        );

        ParsedDocument doc = documentMapper.parse(source(b -> {
            b.startObject("path1").field("field1", "value1").endObject();
            b.startObject("path2").field("field2", "value2").endObject();
        }));

        IndexableField sourceField = doc.rootDoc().getField("_source");
        Map<String, Object> sourceAsMap;
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, new BytesArray(sourceField.binaryValue()))) {
            sourceAsMap = parser.map();
        }
        assertThat(sourceAsMap.containsKey("path1"), equalTo(false));
        assertThat(sourceAsMap.containsKey("path2"), equalTo(true));
    }

    public void testDuplicatedExcludes() throws Exception {
        DocumentMapper documentMapper = createDocumentMapper(
            topMapping(b -> b.startObject("_source").array("excludes", "path1", "path1").endObject())
        );

        ParsedDocument doc = documentMapper.parse(source(b -> {
            b.startObject("path1").field("field1", "value1").endObject();
            b.startObject("path2").field("field2", "value2").endObject();
        }));

        IndexableField sourceField = doc.rootDoc().getField("_source");
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, new BytesArray(sourceField.binaryValue()))) {
            assertEquals(Map.of("path2", Map.of("field2", "value2")), parser.map());
        }
    }

    public void testComplete() throws Exception {

        assertTrue(createDocumentMapper(topMapping(b -> {})).sourceMapper().isComplete());

        assertFalse(
            createDocumentMapper(topMapping(b -> b.startObject("_source").field("enabled", false).endObject())).sourceMapper().isComplete()
        );

        assertFalse(
            createDocumentMapper(topMapping(b -> b.startObject("_source").array("includes", "foo*").endObject())).sourceMapper()
                .isComplete()
        );

        assertFalse(
            createDocumentMapper(topMapping(b -> b.startObject("_source").array("excludes", "foo*").endObject())).sourceMapper()
                .isComplete()
        );
    }

    public void testSourceObjectContainsExtraTokens() throws Exception {
        DocumentMapper documentMapper = createDocumentMapper(mapping(b -> {}));

        Exception exception = expectThrows(
            DocumentParsingException.class,
            // extra end object (invalid JSON))
            () -> documentMapper.parse(new SourceToParse("1", new BytesArray("{}}"), XContentType.JSON))
        );
        assertNotNull(exception.getCause());
        assertThat(exception.getCause().getMessage(), containsString("Unexpected close marker '}'"));
    }

    public void testSyntheticDisabledNotSupported() {
        Exception e = expectThrows(
            MapperParsingException.class,
            () -> createDocumentMapper(
                topMapping(b -> b.startObject("_source").field("enabled", false).field("mode", "synthetic").endObject())
            )
        );
        assertThat(e.getMessage(), containsString("Cannot set both [mode] and [enabled] parameters"));
    }

    public void testSyntheticUpdates() throws Exception {
        MapperService mapperService = createMapperService("""
            { "_doc" : { "_source" : { "mode" : "synthetic" } } }
            """);

        SourceFieldMapper mapper = mapperService.documentMapper().sourceMapper();
        assertTrue(mapper.enabled());
        assertTrue(mapper.isSynthetic());

        merge(mapperService, """
            { "_doc" : { "_source" : { "mode" : "synthetic" } } }
            """);
        mapper = mapperService.documentMapper().sourceMapper();
        assertTrue(mapper.enabled());
        assertTrue(mapper.isSynthetic());

        ParsedDocument doc = mapperService.documentMapper().parse(source("{}"));
        assertNull(doc.rootDoc().get(SourceFieldMapper.NAME));

        Exception e = expectThrows(IllegalArgumentException.class, () -> merge(mapperService, """
            { "_doc" : { "_source" : { "mode" : "stored" } } }
            """));
        assertThat(e.getMessage(), containsString("Cannot update parameter [mode] from [synthetic] to [stored]"));

        merge(mapperService, """
            { "_doc" : { "_source" : { "mode" : "disabled" } } }
            """);
        mapper = mapperService.documentMapper().sourceMapper();
        assertFalse(mapper.enabled());
        assertFalse(mapper.isSynthetic());
    }

    public void testSyntheticSourceInTimeSeries() throws IOException {
        XContentBuilder mapping = fieldMapping(b -> {
            b.field("type", "keyword");
            b.field("time_series_dimension", true);
        });
        DocumentMapper mapper = createTimeSeriesModeDocumentMapper(mapping);
        assertTrue(mapper.sourceMapper().isSynthetic());
        assertEquals("{\"_source\":{\"mode\":\"synthetic\"}}", mapper.sourceMapper().toString());
    }

    public void testSyntheticSourceWithLogsIndexMode() throws IOException {
        XContentBuilder mapping = fieldMapping(b -> { b.field("type", "keyword"); });
        DocumentMapper mapper = createLogsModeDocumentMapper(mapping);
        assertTrue(mapper.sourceMapper().isSynthetic());
        assertEquals("{\"_source\":{\"mode\":\"synthetic\"}}", mapper.sourceMapper().toString());
    }

    public void testSupportsNonDefaultParameterValues() throws IOException {
        Settings settings = Settings.builder().put(SourceFieldMapper.LOSSY_PARAMETERS_ALLOWED_SETTING_NAME, false).build();
        {
            var sourceFieldMapper = createMapperService(settings, topMapping(b -> b.startObject("_source").endObject())).documentMapper()
                .sourceMapper();
            assertThat(sourceFieldMapper, notNullValue());
        }
        {
            var sourceFieldMapper = createMapperService(
                settings,
                topMapping(b -> b.startObject("_source").field("mode", randomBoolean() ? "synthetic" : "stored").endObject())
            ).documentMapper().sourceMapper();
            assertThat(sourceFieldMapper, notNullValue());
        }
        Exception e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(settings, topMapping(b -> b.startObject("_source").field("enabled", false).endObject()))
                .documentMapper()
                .sourceMapper()
        );
        assertThat(e.getMessage(), containsString("Parameter [enabled] is not allowed in source"));

        e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(settings, topMapping(b -> b.startObject("_source").array("includes", "foo").endObject()))
                .documentMapper()
                .sourceMapper()
        );
        assertThat(e.getMessage(), containsString("Parameter [includes] is not allowed in source"));

        e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(settings, topMapping(b -> b.startObject("_source").array("excludes", "foo").endObject()))
                .documentMapper()
                .sourceMapper()
        );
        assertThat(e.getMessage(), containsString("Parameter [excludes] is not allowed in source"));

        e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(settings, topMapping(b -> b.startObject("_source").field("mode", "disabled").endObject()))
                .documentMapper()
                .sourceMapper()
        );
        assertThat(e.getMessage(), containsString("Parameter [mode=disabled] is not allowed in source"));

        e = expectThrows(
            MapperParsingException.class,
            () -> createMapperService(
                settings,
                topMapping(
                    b -> b.startObject("_source").field("enabled", false).array("includes", "foo").array("excludes", "foo").endObject()
                )
            ).documentMapper().sourceMapper()
        );
        assertThat(e.getMessage(), containsString("Parameters [enabled,includes,excludes] are not allowed in source"));
    }

    public void testBypassCheckForNonDefaultParameterValuesInEarlierVersions() throws IOException {
        Settings settings = Settings.builder().put(SourceFieldMapper.LOSSY_PARAMETERS_ALLOWED_SETTING_NAME, false).build();
        {
            var sourceFieldMapper = createMapperService(
                IndexVersionUtils.getPreviousVersion(IndexVersions.SOURCE_MAPPER_LOSSY_PARAMS_CHECK),
                settings,
                () -> true,
                topMapping(b -> b.startObject("_source").field("enabled", false).endObject())
            ).documentMapper().sourceMapper();
            assertThat(sourceFieldMapper, notNullValue());
        }
        {
            var sourceFieldMapper = createMapperService(
                IndexVersionUtils.getPreviousVersion(IndexVersions.SOURCE_MAPPER_LOSSY_PARAMS_CHECK),
                settings,
                () -> true,
                topMapping(b -> b.startObject("_source").array("includes", "foo").endObject())
            ).documentMapper().sourceMapper();
            assertThat(sourceFieldMapper, notNullValue());
        }
        {
            var sourceFieldMapper = createMapperService(
                IndexVersionUtils.getPreviousVersion(IndexVersions.SOURCE_MAPPER_LOSSY_PARAMS_CHECK),
                settings,
                () -> true,
                topMapping(b -> b.startObject("_source").array("excludes", "foo").endObject())
            ).documentMapper().sourceMapper();
            assertThat(sourceFieldMapper, notNullValue());
        }
        {
            var sourceFieldMapper = createMapperService(
                IndexVersionUtils.getPreviousVersion(IndexVersions.SOURCE_MAPPER_LOSSY_PARAMS_CHECK),
                settings,
                () -> true,
                topMapping(b -> b.startObject("_source").field("mode", "disabled").endObject())
            ).documentMapper().sourceMapper();
            assertThat(sourceFieldMapper, notNullValue());
        }
    }

    public void testRecoverySourceWithSourceExcludes() throws IOException {
        {
            MapperService mapperService = createMapperService(
                topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("excludes", List.of("field1")).endObject())
            );
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> { b.field("field1", "value1"); }));

            assertNotNull(doc.rootDoc().getField("_recovery_source"));
            assertThat(doc.rootDoc().getField("_recovery_source").binaryValue(), equalTo(new BytesRef("{\"field1\":\"value1\"}")));
        }
        {
            Settings settings = Settings.builder().put(INDICES_RECOVERY_SOURCE_ENABLED_SETTING.getKey(), false).build();
            MapperService mapperService = createMapperService(
                settings,
                topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("enabled", false).endObject())
            );
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> b.field("field1", "value1")));
            assertNull(doc.rootDoc().getField("_recovery_source"));
        }
    }

    public void testRecoverySourceWithSourceDisabled() throws IOException {
        {
            MapperService mapperService = createMapperService(
                topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("enabled", false).endObject())
            );
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> { b.field("field1", "value1"); }));
            assertNotNull(doc.rootDoc().getField("_recovery_source"));
            assertThat(doc.rootDoc().getField("_recovery_source").binaryValue(), equalTo(new BytesRef("{\"field1\":\"value1\"}")));
        }
        {
            Settings settings = Settings.builder().put(INDICES_RECOVERY_SOURCE_ENABLED_SETTING.getKey(), false).build();
            MapperService mapperService = createMapperService(
                settings,
                topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("enabled", false).endObject())
            );
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> b.field("field1", "value1")));
            assertNull(doc.rootDoc().getField("_recovery_source"));
        }
    }

    public void testRecoverySourceWithSyntheticSource() throws IOException {
        {
            MapperService mapperService = createMapperService(
                topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("mode", "synthetic").endObject())
            );
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> { b.field("field1", "value1"); }));
            assertNotNull(doc.rootDoc().getField("_recovery_source"));
            assertThat(doc.rootDoc().getField("_recovery_source").binaryValue(), equalTo(new BytesRef("{\"field1\":\"value1\"}")));
        }
        {
            Settings settings = Settings.builder().put(INDICES_RECOVERY_SOURCE_ENABLED_SETTING.getKey(), false).build();
            MapperService mapperService = createMapperService(
                settings,
                topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("mode", "synthetic").endObject())
            );
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> b.field("field1", "value1")));
            assertNull(doc.rootDoc().getField("_recovery_source"));
        }
    }

    public void testRecoverySourceWithLogs() throws IOException {
        {
            Settings settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB.getName()).build();
            MapperService mapperService = createMapperService(settings, mapping(b -> {}));
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> { b.field("@timestamp", "2012-02-13"); }));
            assertNotNull(doc.rootDoc().getField("_recovery_source"));
            assertThat(doc.rootDoc().getField("_recovery_source").binaryValue(), equalTo(new BytesRef("{\"@timestamp\":\"2012-02-13\"}")));
        }
        {
            Settings settings = Settings.builder()
                .put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB.getName())
                .put(INDICES_RECOVERY_SOURCE_ENABLED_SETTING.getKey(), false)
                .build();
            MapperService mapperService = createMapperService(settings, mapping(b -> {}));
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> b.field("@timestamp", "2012-02-13")));
            assertNull(doc.rootDoc().getField("_recovery_source"));
        }
    }

    public void testRecoverySourceWithLogsCustom() throws IOException {
        XContentBuilder mappings = topMapping(b -> b.startObject(SourceFieldMapper.NAME).field("mode", "synthetic").endObject());
        {
            Settings settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB.getName()).build();
            MapperService mapperService = createMapperService(settings, mappings);
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> { b.field("@timestamp", "2012-02-13"); }));
            assertNotNull(doc.rootDoc().getField("_recovery_source"));
            assertThat(doc.rootDoc().getField("_recovery_source").binaryValue(), equalTo(new BytesRef("{\"@timestamp\":\"2012-02-13\"}")));
        }
        {
            Settings settings = Settings.builder()
                .put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB.getName())
                .put(INDICES_RECOVERY_SOURCE_ENABLED_SETTING.getKey(), false)
                .build();
            MapperService mapperService = createMapperService(settings, mappings);
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source(b -> b.field("@timestamp", "2012-02-13")));
            assertNull(doc.rootDoc().getField("_recovery_source"));
        }
    }

    public void testRecoverySourceWithTimeSeries() throws IOException {
        {
            Settings settings = Settings.builder()
                .put(IndexSettings.MODE.getKey(), IndexMode.TIME_SERIES.getName())
                .put(IndexMetadata.INDEX_ROUTING_PATH.getKey(), "field")
                .build();
            MapperService mapperService = createMapperService(settings, fieldMapping(b -> {
                b.field("type", "keyword");
                b.field("time_series_dimension", true);
            }));
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source("123", b -> b.field("@timestamp", "2012-02-13").field("field", "value1"), null));
            assertNotNull(doc.rootDoc().getField("_recovery_source"));
            assertThat(
                doc.rootDoc().getField("_recovery_source").binaryValue(),
                equalTo(new BytesRef("{\"@timestamp\":\"2012-02-13\",\"field\":\"value1\"}"))
            );
        }
        {
            Settings settings = Settings.builder()
                .put(IndexSettings.MODE.getKey(), IndexMode.TIME_SERIES.getName())
                .put(IndexMetadata.INDEX_ROUTING_PATH.getKey(), "field")
                .put(INDICES_RECOVERY_SOURCE_ENABLED_SETTING.getKey(), false)
                .build();
            MapperService mapperService = createMapperService(settings, fieldMapping(b -> {
                b.field("type", "keyword");
                b.field("time_series_dimension", true);
            }));
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(
                source("123", b -> b.field("@timestamp", "2012-02-13").field("field", randomAlphaOfLength(5)), null)
            );
            assertNull(doc.rootDoc().getField("_recovery_source"));
        }
    }

    public void testRecoverySourceWithTimeSeriesCustom() throws IOException {
        String mappings = """
                {
                    "_doc" : {
                        "_source" : {
                            "mode" : "synthetic"
                        },
                        "properties": {
                            "field": {
                                "type": "keyword",
                                "time_series_dimension": true
                            }
                        }
                    }
                }
            """;
        {
            Settings settings = Settings.builder()
                .put(IndexSettings.MODE.getKey(), IndexMode.TIME_SERIES.getName())
                .put(IndexMetadata.INDEX_ROUTING_PATH.getKey(), "field")
                .build();
            MapperService mapperService = createMapperService(settings, mappings);
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(source("123", b -> b.field("@timestamp", "2012-02-13").field("field", "value1"), null));
            assertNotNull(doc.rootDoc().getField("_recovery_source"));
            assertThat(
                doc.rootDoc().getField("_recovery_source").binaryValue(),
                equalTo(new BytesRef("{\"@timestamp\":\"2012-02-13\",\"field\":\"value1\"}"))
            );
        }
        {
            Settings settings = Settings.builder()
                .put(IndexSettings.MODE.getKey(), IndexMode.TIME_SERIES.getName())
                .put(IndexMetadata.INDEX_ROUTING_PATH.getKey(), "field")
                .put(INDICES_RECOVERY_SOURCE_ENABLED_SETTING.getKey(), false)
                .build();
            MapperService mapperService = createMapperService(settings, mappings);
            DocumentMapper docMapper = mapperService.documentMapper();
            ParsedDocument doc = docMapper.parse(
                source("123", b -> b.field("@timestamp", "2012-02-13").field("field", randomAlphaOfLength(5)), null)
            );
            assertNull(doc.rootDoc().getField("_recovery_source"));
        }
    }

    public void testWrongLocationPatchSourceInPostParse() throws Exception {
        MapperService mapperService = createMapperService(Settings.EMPTY, mapping(b -> {
            b.startObject("field");
            b.field("type", "long");
            b.endObject();
        }));
        SourceFieldMapper mapper = (SourceFieldMapper) mapperService.getMetadataMappers().get(SourceFieldMapper.class);
        XContentBuilder builder = JsonXContent.contentBuilder();
        builder.value(Map.of("field", 45));
        var sourceToParse = new SourceToParse("0", BytesReference.bytes(builder), builder.contentType());
        FieldMapper fieldMapper = (FieldMapper) mapperService.mappingLookup().getMapper("field");
        try (
            var parser = XContentHelper.createParserNotCompressed(
                XContentParserConfiguration.EMPTY,
                sourceToParse.source(),
                XContentType.JSON
            )
        ) {
            DocumentParserContext context = new TestDocumentParserContext(mapperService.mappingLookup(), sourceToParse) {
                @Override
                public XContentParser parser() {
                    return parser;
                }
            };
            var xContentLocation = new XContentLocation(0, 3);
            context.addSourceFieldPatch(fieldMapper, xContentLocation);
            var exc = expectThrows(IllegalArgumentException.class, () -> mapper.postParse(context));
            assertThat(exc.getMessage(), containsString("Registered patch not found"));
        }
    }

    public void testRemainingPatchSourceInPostParse() throws Exception {
        MapperService mapperService = createMapperService(Settings.EMPTY, mapping(b -> {
            b.startObject("field");
            b.field("type", "long");
            b.endObject();

            b.startObject("another_field");
            b.field("type", "long");
            b.endObject();
        }));
        SourceFieldMapper mapper = (SourceFieldMapper) mapperService.getMetadataMappers().get(SourceFieldMapper.class);
        XContentBuilder builder = JsonXContent.contentBuilder();
        builder.value(Map.of("another_field", 45));
        var sourceToParse = new SourceToParse("0", BytesReference.bytes(builder), builder.contentType());
        var fieldMapper = (FieldMapper) mapperService.mappingLookup().getMapper("field");
        var anotherFieldMapper = (FieldMapper) mapperService.mappingLookup().getMapper("another_field");
        try (
            var parser = XContentHelper.createParserNotCompressed(
                XContentParserConfiguration.EMPTY,
                sourceToParse.source(),
                XContentType.JSON
            )
        ) {
            DocumentParserContext context = new TestDocumentParserContext(mapperService.mappingLookup(), sourceToParse) {
                @Override
                public XContentParser parser() {
                    return parser;
                }
            };
            var xContentLocation1 = new XContentLocation(0, 3);
            context.addSourceFieldPatch(fieldMapper, xContentLocation1);
            {
                var exc = expectThrows(IllegalArgumentException.class, () -> context.addSourceFieldPatch(fieldMapper, xContentLocation1));
                assertThat(exc.getMessage(), containsString("Field [field] does not support patching the same location "));
            }
            var xContentLocation2 = new XContentLocation(2, 6);
            context.addSourceFieldPatch(anotherFieldMapper, xContentLocation2);

            var exc = expectThrows(IllegalArgumentException.class, () -> mapper.postParse(context));
            assertThat(exc.getMessage(), containsString("Registered patch not found"));
        }
    }
}
