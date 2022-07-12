/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.spatial.index.mapper;

import org.apache.lucene.index.IndexableField;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MappedField;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MapperTestCase;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.spatial.LocalStateSpatialPlugin;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

import static org.elasticsearch.geometry.utils.Geohash.stringEncode;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** Base class for testing cartesian field mappers */
public abstract class CartesianFieldMapperTests extends MapperTestCase {

    static final String FIELD_NAME = "field";

    @Override
    protected Collection<Plugin> getPlugins() {
        return Collections.singletonList(new LocalStateSpatialPlugin());
    }

    @Override
    protected void assertSearchable(MappedField mappedField) {}

    @Override
    protected void minimalMapping(XContentBuilder b) throws IOException {
        b.field("type", getFieldName());
    }

    @Override
    protected Object getSampleValueForDocument() {
        return "POINT (14.0 15.0)";
    }

    protected abstract String getFieldName();

    protected abstract void assertXYPointField(IndexableField field, float x, float y);

    protected abstract void assertGeoJSONParseException(MapperParsingException e, String missingField);

    public void testWKT() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(this::minimalMapping));
        ParsedDocument doc = mapper.parse(source(b -> b.field(FIELD_NAME, "POINT (2000.1 305.6)")));
        assertXYPointField(doc.rootDoc().getField(FIELD_NAME), 2000.1f, 305.6f);
    }

    public void testGeoJSONMissingCoordinates() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(this::minimalMapping));
        MapperParsingException e = expectThrows(
            MapperParsingException.class,
            () -> mapper.parse(source(b -> b.startObject(FIELD_NAME).field("type", "Point").endObject()))
        );
        assertGeoJSONParseException(e, "coordinates");
    }

    public void testGeoJSONMissingType() throws IOException {
        double[] coords = new double[] { 0.0, 0.0 };
        DocumentMapper mapper = createDocumentMapper(fieldMapping(this::minimalMapping));
        Exception e = expectThrows(
            MapperParsingException.class,
            () -> mapper.parse(source(b -> b.startObject(FIELD_NAME).field("coordinates", coords).endObject()))
        );
        assertThat(e.getMessage(), containsString("failed to parse"));
        assertThat(e.getCause().getMessage(), containsString("Required [type]"));
    }

    public void testGeoJSON() throws IOException {
        double[] coords = new double[] { 2000.1, 305.6 };
        DocumentMapper mapper = createDocumentMapper(fieldMapping(this::minimalMapping));
        ParsedDocument doc = mapper.parse(
            source(b -> b.startObject(FIELD_NAME).field("coordinates", coords).field("type", "Point").endObject())
        );
        assertXYPointField(doc.rootDoc().getField(FIELD_NAME), 2000.1f, 305.6f);
    }

    public void testInvalidPointValuesIgnored() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b -> {
            b.field("type", getFieldName());
            b.field("ignore_malformed", true);
        }));

        assertThat(mapper.parse(source(b -> b.field(FIELD_NAME, "1234.333"))).rootDoc().getField(FIELD_NAME), nullValue());

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("x", 1.3).field("y", "-").endObject())).rootDoc().getField(FIELD_NAME),
            nullValue()
        );

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("geohash", stringEncode(0, 0)).endObject()))
                .rootDoc()
                .getField(FIELD_NAME),
            nullValue()
        );

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("x", "-").field("y", 1.3).endObject())).rootDoc().getField(FIELD_NAME),
            nullValue()
        );

        assertThat(mapper.parse(source(b -> b.field(FIELD_NAME, "-,1.3"))).rootDoc().getField(FIELD_NAME), nullValue());

        assertThat(mapper.parse(source(b -> b.field(FIELD_NAME, "1.3,-"))).rootDoc().getField(FIELD_NAME), nullValue());

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("lon", 1.3).field("y", 1.3).endObject()))
                .rootDoc()
                .getField(FIELD_NAME),
            nullValue()
        );

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("x", 1.3).field("lat", 1.3).endObject()))
                .rootDoc()
                .getField(FIELD_NAME),
            nullValue()
        );

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("x", "NaN").field("y", "NaN").endObject()))
                .rootDoc()
                .getField(FIELD_NAME),
            nullValue()
        );

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("x", "NaN").field("y", 1.3).endObject()))
                .rootDoc()
                .getField(FIELD_NAME),
            nullValue()
        );

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("x", 1.3).field("y", "NaN").endObject()))
                .rootDoc()
                .getField(FIELD_NAME),
            nullValue()
        );

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("x", 1.3).field("y", "NaN").endObject()))
                .rootDoc()
                .getField(FIELD_NAME),
            nullValue()
        );

        assertThat(mapper.parse(source(b -> b.field(FIELD_NAME, "NaN,NaN"))).rootDoc().getField(FIELD_NAME), nullValue());

        assertThat(mapper.parse(source(b -> b.field(FIELD_NAME, "10,NaN"))).rootDoc().getField(FIELD_NAME), nullValue());

        assertThat(mapper.parse(source(b -> b.field(FIELD_NAME, "NaN,12"))).rootDoc().getField(FIELD_NAME), nullValue());

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).field("x", 1.3).nullField("y").endObject())).rootDoc().getField(FIELD_NAME),
            nullValue()
        );

        assertThat(
            mapper.parse(source(b -> b.startObject(FIELD_NAME).nullField("x").field("y", 1.3).endObject())).rootDoc().getField(FIELD_NAME),
            nullValue()
        );
    }

    public void testZValueWKT() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b -> {
            b.field("type", getFieldName());
            b.field("ignore_z_value", true);
        }));

        ParsedDocument doc = mapper.parse(source(b -> b.field(FIELD_NAME, "POINT (2000.1 305.6 34567.33)")));

        assertThat(doc.rootDoc().getField(FIELD_NAME), notNullValue());

        DocumentMapper mapper2 = createDocumentMapper(fieldMapping(b -> {
            b.field("type", getFieldName());
            b.field("ignore_z_value", false);
        }));

        MapperParsingException e = expectThrows(
            MapperParsingException.class,
            () -> mapper2.parse(source(b -> b.field(FIELD_NAME, "POINT (2000.1 305.6 34567.33)")))
        );
        assertThat(e.getMessage(), containsString("failed to parse field [" + FIELD_NAME + "] of type"));
        assertThat(e.getRootCause().getMessage(), containsString("found Z value [34567.33] but [ignore_z_value] parameter is [false]"));
    }

    public void testZValueGeoJSON() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b -> {
            b.field("type", getFieldName());
            b.field("ignore_z_value", true);
        }));

        double[] coords = { 2000.1, 305.6, 34567.33 };
        ParsedDocument doc = mapper.parse(
            source(b -> b.startObject(FIELD_NAME).field("type", "Point").field("coordinates", coords).endObject())
        );

        assertThat(doc.rootDoc().getField(FIELD_NAME), notNullValue());

        DocumentMapper mapper2 = createDocumentMapper(fieldMapping(b -> {
            b.field("type", getFieldName());
            b.field("ignore_z_value", false);
        }));

        MapperParsingException e = expectThrows(
            MapperParsingException.class,
            () -> mapper2.parse(source(b -> b.startObject(FIELD_NAME).field("type", "Point").field("coordinates", coords).endObject()))
        );
        assertThat(e.getMessage(), containsString("failed to parse field [" + FIELD_NAME + "] of type"));
        assertThat(e.getRootCause().getMessage(), containsString("found Z value [34567.33] but [ignore_z_value] parameter is [false]"));
    }
}
