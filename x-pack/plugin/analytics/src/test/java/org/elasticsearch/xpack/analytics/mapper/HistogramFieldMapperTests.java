/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.analytics.mapper;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.DocumentMapperParser;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xpack.analytics.AnalyticsPlugin;
import org.elasticsearch.xpack.core.XPackPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;


public class HistogramFieldMapperTests extends ESSingleNodeTestCase {

    public void testParseValue() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        ParsedDocument doc = defaultMapper.parse(new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                        .startObject().field("pre_aggregated").startObject()
                        .field("values", new int[] {0, 0})
                        .field("counts", new long[] {0, 0})
                        .endObject()
                        .endObject()),
                XContentType.JSON));

        assertThat(doc.rootDoc().getField("pre_aggregated"), notNullValue());
    }

    public void testParseArrayValue() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().startArray("pre_aggregated")
                .startObject()
                .field("counts", new int[] {2, 2, 3})
                .field("values", new double[] {2, 2, 3})
                .endObject()
                .startObject()
                .field("counts", new int[] {2, 2, 3})
                .field("values", new double[] {2, 2, 3})
                .endObject().endArray()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("doesn't not support indexing multiple values " +
            "for the same field in the same document"));
    }

    public void testEmptyArrays() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("values", new double[] {})
                .field("counts", new int[] {})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("arrays for values and counts cannot be empty"));
    }

    public void testMissingFieldCounts() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("values", new double[] {2, 2})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("expected field called [counts]"));
    }

    public void testMissingFieldValues() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("counts", new int[] {2, 2})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("expected field called [values]"));
    }

    public void testUnknownField() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("counts", new int[] {2, 2})
                .field("values", new double[] {2, 2})
                .field("unknown", new double[] {2, 2})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("with unknown parameter [unknown]"));
    }

    public void testFieldArraysDifferentSize() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("counts", new int[] {2, 2})
                .field("values", new double[] {2, 2, 3})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("expected same length from [values] and [counts] but got [3 != 2]"));
    }

    public void testFieldCountsNotArray() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("counts", "bah")
                .field("values", new double[] {2, 2, 3})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("expecting token of type [START_ARRAY] but found [VALUE_STRING]"));
    }

    public void testFieldCountsStringArray() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("counts", new String[] {"4", "5", "6"})
                .field("values", new double[] {2, 2, 3})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("expecting token of type [VALUE_NUMBER] but found [VALUE_STRING]"));
    }

    public void testFieldValuesStringArray() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("counts", new int[] {4, 5, 6})
                .field("values", new String[] {"2", "2", "3"})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("expecting token of type [VALUE_NUMBER] but found [VALUE_STRING]"));
    }

    public void testFieldValuesNotArray() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("counts", new int[] {2, 2, 3})
                .field("values", "bah")
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("expecting token of type [START_ARRAY] but found [VALUE_STRING]"));
    }

    public void testCountIsLong() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("counts", new long[] {2, 2, Long.MAX_VALUE})
                .field("values", new double[] {2 ,2 ,3})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString(" out of range of int"));
    }

    public void testValuesNotInOrder() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("counts", new long[] {2, 8, 4})
                .field("values", new double[] {2 ,3 ,2})
                .endObject()
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString(" values must be in increasing order, " +
            "got [2.0] but previous value was [3.0]"));
    }

    public void testFieldNotObject() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated", "bah")
                .endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("expecting token of type [START_OBJECT] " +
            "but found [VALUE_STRING]"));
    }

    public void testNegativeCount() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram");
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = createIndex("test").mapperService().documentMapperParser()
            .parse("_doc", new CompressedXContent(mapping));

        SourceToParse source = new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().startObject("pre_aggregated")
                .field("counts", new int[] {2, 2, -3})
                .field("values", new double[] {2, 2, 3})
                .endObject().endObject()),
            XContentType.JSON);

        Exception e = expectThrows(MapperParsingException.class, () -> defaultMapper.parse(source));
        assertThat(e.getCause().getMessage(), containsString("[counts] elements must be >= 0 but got -3"));
    }

    public void testSetStoredField() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram")
            .field("store", true);
        String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());

        DocumentMapperParser documentMapperParser = createIndex("test").mapperService().documentMapperParser();

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            documentMapperParser.parse("_doc", new CompressedXContent(mapping)));
        assertThat(e.getMessage(), containsString("The [histogram] field does not support stored fields"));

        xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram")
            .field("store", false);
        String mapping2 = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());
        DocumentMapper defaultMapper = documentMapperParser.parse("_doc", new CompressedXContent(mapping2));
        assertNotNull(defaultMapper);
    }

    public void testSetIndexField() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram")
            .field("index", true);
        final String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());

        DocumentMapperParser documentMapperParser = createIndex("test").mapperService().documentMapperParser();

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            documentMapperParser.parse("_doc", new CompressedXContent(mapping)));
        assertThat(e.getMessage(), containsString("The [histogram] field does not support indexing"));

        xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram")
            .field("index", false);
        final String mapping2 = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());

        DocumentMapper defaultMapper = documentMapperParser.parse("_doc", new CompressedXContent(mapping2));
        assertNotNull(defaultMapper);
    }

    public void testSetDocValuesField() throws Exception {
        ensureGreen();
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram")
            .field("doc_values", false);
        final String mapping = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());

        DocumentMapperParser documentMapperParser = createIndex("test").mapperService().documentMapperParser();

        DocumentMapper defaultMapper = documentMapperParser.parse("_doc", new CompressedXContent(mapping));
        assertNotNull(defaultMapper);

        ParsedDocument doc = defaultMapper.parse(new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("values", new double[] {0, 0})
                .field("counts", new int[] {0, 0})
                .endObject()
                .endObject()),
            XContentType.JSON));

        assertThat(doc.rootDoc().getField("pre_aggregated"), nullValue());

        xContentBuilder = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("pre_aggregated").field("type", "histogram")
            .field("doc_values", true);
        final String mapping2 = Strings.toString(xContentBuilder.endObject().endObject().endObject().endObject());

        defaultMapper = documentMapperParser.parse("_doc", new CompressedXContent(mapping2));
        assertNotNull(defaultMapper);

        doc = defaultMapper.parse(new SourceToParse("test", "1",
            BytesReference.bytes(XContentFactory.jsonBuilder()
                .startObject().field("pre_aggregated").startObject()
                .field("values", new double[] {0, 0})
                .field("counts", new int[] {0, 0})
                .endObject()
                .endObject()),
            XContentType.JSON));

        assertThat(doc.rootDoc().getField("pre_aggregated"), notNullValue());
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>(super.getPlugins());
        plugins.add(AnalyticsPlugin.class);
        plugins.add(XPackPlugin.class);
        return plugins;
    }

}
