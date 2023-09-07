/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.ByteBlockPool;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.StringHelper;
import org.elasticsearch.cluster.routing.IndexRouting;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.hash.MurmurHash3;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.util.ByteUtils;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.fielddata.FieldData;
import org.elasticsearch.index.fielddata.FieldDataContext;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.ScriptDocValues;
import org.elasticsearch.index.fielddata.plain.SortedOrdinalsIndexFieldData;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.script.field.DelegateDocValuesField;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;

import java.io.IOException;
import java.net.InetAddress;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Mapper for {@code _tsid} field included generated when the index is
 * {@link IndexMode#TIME_SERIES organized into time series}.
 */
public class TimeSeriesIdFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_tsid";
    public static final String CONTENT_TYPE = "_tsid";
    public static final TimeSeriesIdFieldType FIELD_TYPE = new TimeSeriesIdFieldType();
    public static final TimeSeriesIdFieldMapper INSTANCE = new TimeSeriesIdFieldMapper();

    /**
     * The maximum length of the tsid. The value itself comes from a range check in
     * Lucene's writer for utf-8 doc values.
     */
    private static final int LIMIT = ByteBlockPool.BYTE_BLOCK_SIZE - 2;
    /**
     * The maximum length of any single dimension. We picked this so that we could
     * comfortable fit 16 dimensions inside {@link #LIMIT}. This should be quite
     * comfortable given that dimensions are typically going to be less than a
     * hundred bytes each, but we're being paranoid here.
     */
    public static final int TSID_HASH_SENTINEL = 0xBAADCAFE;

    @Override
    public FieldMapper.Builder getMergeBuilder() {
        return new Builder().init(this);
    }

    public static class Builder extends MetadataFieldMapper.Builder {

        protected Builder() {
            super(NAME);
        }

        @Override
        protected Parameter<?>[] getParameters() {
            return EMPTY_PARAMETERS;
        }

        @Override
        public TimeSeriesIdFieldMapper build() {
            return INSTANCE;
        }
    }

    public static final TypeParser PARSER = new FixedTypeParser(c -> c.getIndexSettings().getMode().timeSeriesIdFieldMapper());

    public static final class TimeSeriesIdFieldType extends MappedFieldType {
        private TimeSeriesIdFieldType() {
            super(NAME, false, false, true, TextSearchInfo.NONE, Collections.emptyMap());
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public ValueFetcher valueFetcher(SearchExecutionContext context, String format) {
            return new DocValueFetcher(docValueFormat(format, null), context.getForField(this, FielddataOperation.SEARCH));
        }

        @Override
        public DocValueFormat docValueFormat(String format, ZoneId timeZone) {
            if (format != null) {
                throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] doesn't support formats.");
            }
            return DocValueFormat.TIME_SERIES_ID;
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(FieldDataContext fieldDataContext) {
            failIfNoDocValues();
            // TODO don't leak the TSID's binary format into the script
            return new SortedOrdinalsIndexFieldData.Builder(
                name(),
                CoreValuesSourceType.KEYWORD,
                (dv, n) -> new DelegateDocValuesField(
                    new ScriptDocValues.Strings(new ScriptDocValues.StringsSupplier(FieldData.toString(dv))),
                    n
                )
            );
        }

        @Override
        public Query termQuery(Object value, SearchExecutionContext context) {
            throw new IllegalArgumentException("[" + NAME + "] is not searchable");
        }
    }

    private TimeSeriesIdFieldMapper() {
        super(FIELD_TYPE);
    }

    @Override
    public void postParse(DocumentParserContext context) throws IOException {
        assert fieldType().isIndexed() == false;

        final TimeSeriesIdBuilder timeSeriesIdBuilder = (TimeSeriesIdBuilder) context.getDimensions();
        final BytesReference timeSeriesId = timeSeriesIdBuilder.build();
        context.doc().add(new SortedDocValuesField(fieldType().name(), timeSeriesIdBuilder.similarityHash(timeSeriesId).toBytesRef()));
        TsidExtractingIdFieldMapper.createField(context, timeSeriesIdBuilder.routingBuilder, timeSeriesId.toBytesRef());
    }

    // TODO: remove if using {@link TimeSeriesIdBuilder#similarityHash(BytesReference)}
    public static BytesReference hash128(final BytesReference timeSeriesId) throws IOException {
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            final byte[] buffer = new byte[16];
            out.writeVInt(TSID_HASH_SENTINEL);
            final BytesRef tsid = timeSeriesId.toBytesRef();
            final MurmurHash3.Hash128 hash = new MurmurHash3.Hash128();
            MurmurHash3.hash128(tsid.bytes, tsid.offset, tsid.length, 0, hash);
            ByteUtils.writeLongLE(hash.h1, buffer, 0);
            ByteUtils.writeLongLE(hash.h2, buffer, 8);
            // TODO: maybe remove Base64 encoding and do it in {@link TimeSeriesIdFieldMapper#decodeTsid(StreamInput)} )}
            final BytesRef encoded = new BytesRef(Base64.getUrlEncoder().withoutPadding().encodeToString(buffer));
            out.writeBytesRef(encoded);
            return out.bytes();
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public SourceLoader.SyntheticFieldLoader syntheticFieldLoader() {
        return SourceLoader.SyntheticFieldLoader.NOTHING;
    }

    /**
     * Decode the {@code _tsid} into a human readable map.
     */
    public static Map<String, Object> decodeTsid(StreamInput in) {
        try {
            int sizeOrTsidHashSentinel = in.readVInt();
            if (sizeOrTsidHashSentinel == TSID_HASH_SENTINEL) {
                final BytesRef bytesRef = in.readBytesRef();
                return Collections.singletonMap("_tsid", Base64.getUrlEncoder().withoutPadding().encodeToString(bytesRef.bytes));
            }
            Map<String, Object> result = new LinkedHashMap<>(sizeOrTsidHashSentinel);

            for (int i = 0; i < sizeOrTsidHashSentinel; i++) {
                String name = in.readBytesRef().utf8ToString();

                int type = in.read();
                switch (type) {
                    case (byte) 's' -> // parse a string
                        result.put(name, in.readBytesRef().utf8ToString());
                    case (byte) 'l' -> // parse a long
                        result.put(name, in.readLong());
                    case (byte) 'u' -> { // parse an unsigned_long
                        Object ul = DocValueFormat.UNSIGNED_LONG_SHIFTED.format(in.readLong());
                        result.put(name, ul);
                    }
                    default -> throw new IllegalArgumentException("Cannot parse [" + name + "]: Unknown type [" + type + "]");
                }
            }
            return result;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Error formatting " + NAME + ": " + e.getMessage(), e);
        }
    }

    public static class TimeSeriesIdBuilder implements DocumentDimensions {

        public static final int MAX_DIMENSIONS = 512;

        private record DimensionDataHolder(BytesReference fieldName, int fieldNameHash, BytesReference value, int valueHash) {}

        /**
         * A sorted set of the serialized values of dimension fields that will be used
         * for generating the _tsid field. The map will be used by {@link TimeSeriesIdFieldMapper}
         * to build the _tsid field for the document.
         */
        private final SortedSet<DimensionDataHolder> dimensions = new TreeSet<>(Comparator.comparing(o -> o.fieldName));
        /**
         * Builds the routing. Used for building {@code _id}. If null then skipped.
         */
        @Nullable
        private final IndexRouting.ExtractFromSource.Builder routingBuilder;

        public TimeSeriesIdBuilder(@Nullable IndexRouting.ExtractFromSource.Builder routingBuilder) {
            this.routingBuilder = routingBuilder;
        }

        public BytesReference build() throws IOException {
            if (dimensions.isEmpty()) {
                throw new IllegalArgumentException("Dimension fields are missing.");
            }

            try (BytesStreamOutput out = new BytesStreamOutput()) {
                out.writeVInt(dimensions.size());
                for (DimensionDataHolder entry : dimensions) {
                    out.writeBytesRef(entry.fieldName.toBytesRef());
                    entry.value.writeTo(out);
                }
                return out.bytes();
            }
        }

        /**
         * Here we build the hash of the tsid using a similarity function so that we have a result
         * with the following pattern:
         * ${similarityHash(_tsid)}-${hash(_tsid)}.
         * The idea is to be able to place 'similar' time series close to each other. Two time series
         * are considered 'similar' if they share the same values for a subset of the dimensions (sorted
         * names/values).
         */
        public BytesReference similarityHash(final BytesReference timeSeriesId) throws IOException {
            int bufferIndex = 0;
            // max 512 entries of (hash32(fieldName) + '=' + hash32(fieldValue) + ":") plus the has128(timeSeriesId)
            final byte[] buffer = new byte[MAX_DIMENSIONS * 10 + 16];
            for (final DimensionDataHolder dimensionDataHolder : dimensions) {
                if (bufferIndex >= MAX_DIMENSIONS) break;
                ByteUtils.writeIntLE(dimensionDataHolder.fieldNameHash, buffer, bufferIndex);
                ByteUtils.writeIntLE('=', buffer, bufferIndex + 4);
                ByteUtils.writeIntLE(dimensionDataHolder.valueHash, buffer, bufferIndex + 5);
                ByteUtils.writeIntLE(':', buffer, bufferIndex + 9);
                bufferIndex += 10;
            }
            final BytesRef timeSeriesIdBytesRef = timeSeriesId.toBytesRef();
            final MurmurHash3.Hash128 tsidFullHash = new MurmurHash3.Hash128();
            MurmurHash3.hash128(timeSeriesIdBytesRef.bytes, timeSeriesIdBytesRef.offset, timeSeriesIdBytesRef.length, 0, tsidFullHash);
            ByteUtils.writeLongLE(tsidFullHash.h1, buffer, bufferIndex);
            bufferIndex += 8;
            ByteUtils.writeLongLE(tsidFullHash.h2, buffer, bufferIndex);
            bufferIndex += 8;
            try (BytesStreamOutput out = new BytesStreamOutput()) {
                out.writeVInt(TSID_HASH_SENTINEL);
                final BytesRef hash = new BytesRef(Arrays.copyOfRange(buffer, 0, bufferIndex));
                out.writeBytesRef(hash);
                return out.bytes();
            }
        }

        @Override
        public void addString(String fieldName, BytesRef utf8Value) {
            try (BytesStreamOutput out = new BytesStreamOutput()) {
                out.write((byte) 's');
                /*
                 * Write in utf8 instead of StreamOutput#writeString which is utf-16-ish
                 * so it's easier for folks to reason about the space taken up. Mostly
                 * it'll be smaller too.
                 */
                out.writeBytesRef(utf8Value);
                add(fieldName, out.bytes());

                if (routingBuilder != null) {
                    routingBuilder.addMatching(fieldName, utf8Value);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Dimension field cannot be serialized.", e);
            }
        }

        @Override
        public void addIp(String fieldName, InetAddress value) {
            addString(fieldName, NetworkAddress.format(value));
        }

        @Override
        public void addLong(String fieldName, long value) {
            try (BytesStreamOutput out = new BytesStreamOutput()) {
                out.write((byte) 'l');
                out.writeLong(value);
                add(fieldName, out.bytes());
            } catch (IOException e) {
                throw new IllegalArgumentException("Dimension field cannot be serialized.", e);
            }
        }

        @Override
        public void addUnsignedLong(String fieldName, long value) {
            try (BytesStreamOutput out = new BytesStreamOutput()) {
                Object ul = DocValueFormat.UNSIGNED_LONG_SHIFTED.format(value);
                if (ul instanceof Long l) {
                    out.write((byte) 'l');
                    out.writeLong(l);
                } else {
                    out.write((byte) 'u');
                    out.writeLong(value);
                }
                add(fieldName, out.bytes());
            } catch (IOException e) {
                throw new IllegalArgumentException("Dimension field cannot be serialized.", e);
            }
        }

        private void add(String fieldName, BytesReference encoded) throws IOException {
            final BytesArray fieldNameBytesRef = new BytesArray(fieldName);
            final DimensionDataHolder dimension = new DimensionDataHolder(
                fieldNameBytesRef,
                StringHelper.murmurhash3_x86_32(fieldNameBytesRef.toBytesRef(), 0),
                encoded,
                StringHelper.murmurhash3_x86_32(encoded.toBytesRef(), 0)
            );
            if (dimensions.contains(dimension)) {
                throw new IllegalArgumentException("Dimension field [" + fieldName + "] cannot be a multi-valued field.");
            }
            dimensions.add(dimension);
        }
    }

    public static Map<String, Object> decodeTsid(BytesRef bytesRef) {
        try (StreamInput input = new BytesArray(bytesRef).streamInput()) {
            return decodeTsid(input);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Dimension field cannot be deserialized.", ex);
        }
    }
}
