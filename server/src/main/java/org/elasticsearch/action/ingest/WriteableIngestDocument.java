/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.ingest;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.core.RestApiVersion;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.IngestDocument.Metadata;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

final class WriteableIngestDocument implements Writeable, ToXContentFragment {

    static final String SOURCE_FIELD = "_source";
    static final String INGEST_FIELD = "_ingest";
    static final String DOC_FIELD = "doc";
    private final IngestDocument ingestDocument;

    @SuppressWarnings("unchecked")
    public static final ConstructingObjectParser<WriteableIngestDocument, Void> INGEST_DOC_PARSER = new ConstructingObjectParser<>(
        "ingest_document",
        true,
        a -> {
            Map<String, Object> source = (Map<String, Object>) a[5];
            Map<String, Object> sourceAndMetadata = Maps.newHashMapWithExpectedSize(5 + source.size());
            sourceAndMetadata.putAll(source);
            sourceAndMetadata.put(Metadata.INDEX.getFieldName(), a[0]);
            sourceAndMetadata.put(Metadata.ID.getFieldName(), a[1]);
            if (a[2] != null) {
                sourceAndMetadata.put(Metadata.ROUTING.getFieldName(), a[2]);
            }
            if (a[3] != null) {
                sourceAndMetadata.put(Metadata.VERSION.getFieldName(), a[3]);
            }
            if (a[4] != null) {
                sourceAndMetadata.put(Metadata.VERSION_TYPE.getFieldName(), a[4]);
            }
            Map<String, Object> ingestMetadata = (Map<String, Object>) a[6];
            return new WriteableIngestDocument(new IngestDocument(sourceAndMetadata, ingestMetadata));
        }
    );
    static {
        INGEST_DOC_PARSER.declareString(constructorArg(), new ParseField(Metadata.INDEX.getFieldName()));
        INGEST_DOC_PARSER.declareString(constructorArg(), new ParseField(Metadata.ID.getFieldName()));
        INGEST_DOC_PARSER.declareString(optionalConstructorArg(), new ParseField(Metadata.ROUTING.getFieldName()));
        INGEST_DOC_PARSER.declareLong(optionalConstructorArg(), new ParseField(Metadata.VERSION.getFieldName()));
        INGEST_DOC_PARSER.declareString(optionalConstructorArg(), new ParseField(Metadata.VERSION_TYPE.getFieldName()));
        INGEST_DOC_PARSER.declareObject(constructorArg(), (p, c) -> p.map(), new ParseField(SOURCE_FIELD));
        INGEST_DOC_PARSER.declareObject(constructorArg(), (p, c) -> {
            Map<String, Object> ingestMap = p.map();
            ingestMap.computeIfPresent("timestamp", (k, o) -> ZonedDateTime.parse((String) o));
            return ingestMap;
        }, new ParseField(INGEST_FIELD));
    }

    public static final ConstructingObjectParser<WriteableIngestDocument, Void> PARSER = new ConstructingObjectParser<>(
        "writeable_ingest_document",
        true,
        a -> (WriteableIngestDocument) a[0]
    );
    static {
        PARSER.declareObject(constructorArg(), INGEST_DOC_PARSER, new ParseField(DOC_FIELD));
    }

    WriteableIngestDocument(IngestDocument ingestDocument) {
        assert ingestDocument != null;
        this.ingestDocument = ingestDocument;
    }

    WriteableIngestDocument(StreamInput in) throws IOException {
        Map<String, Object> sourceAndMetadata = in.readMap();
        Map<String, Object> ingestMetadata = in.readMap();
        this.ingestDocument = new IngestDocument(sourceAndMetadata, ingestMetadata);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeGenericMap(ingestDocument.getSourceAndMetadata());
        out.writeGenericMap(ingestDocument.getIngestMetadata());
    }

    IngestDocument getIngestDocument() {
        return ingestDocument;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(DOC_FIELD);
        Map<String, Object> metadataMap = ingestDocument.getMetadataMap();
        for (Map.Entry<String, Object> metadata : metadataMap.entrySet()) {
            if (metadata.getValue() != null) {
                builder.field(metadata.getKey(), metadata.getValue().toString());
            }
        }
        if (builder.getRestApiVersion() == RestApiVersion.V_7) {
            builder.field(MapperService.TYPE_FIELD_NAME, MapperService.SINGLE_MAPPING_NAME);
        }
        builder.field(SOURCE_FIELD, ingestDocument.getSource());
        builder.field(INGEST_FIELD, ingestDocument.getIngestMetadata());
        builder.endObject();
        return builder;
    }

    public static WriteableIngestDocument fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WriteableIngestDocument that = (WriteableIngestDocument) o;
        return Objects.equals(ingestDocument, that.ingestDocument);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ingestDocument);
    }

    @Override
    public String toString() {
        return ingestDocument.toString();
    }
}
