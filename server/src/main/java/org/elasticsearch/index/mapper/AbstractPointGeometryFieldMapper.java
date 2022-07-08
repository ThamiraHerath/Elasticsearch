/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.mapper;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.TriFunction;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Base class for spatial fields that only support indexing points */
public abstract class AbstractPointGeometryFieldMapper<T> extends AbstractGeometryFieldMapper<T> {

    public static <T> Parameter<T> nullValueParam(
        Function<FieldMapper, T> initializer,
        TriFunction<String, MappingParserContext, Object, T> parser,
        Supplier<T> def,
        Serializer<T> serializer
    ) {
        return new Parameter<T>("null_value", false, def, parser, initializer, serializer, Objects::toString);
    }

    protected final T nullValue;

    protected AbstractPointGeometryFieldMapper(
        String simpleName,
        MappedField<? extends AbstractGeometryFieldType<T>> mappedField,
        MultiFields multiFields,
        Explicit<Boolean> ignoreMalformed,
        Explicit<Boolean> ignoreZValue,
        T nullValue,
        CopyTo copyTo,
        Parser<T> parser
    ) {
        super(simpleName, mappedField, ignoreMalformed, ignoreZValue, multiFields, copyTo, parser);
        this.nullValue = nullValue;
    }

    protected AbstractPointGeometryFieldMapper(
        String simpleName,
        MappedField<? extends AbstractGeometryFieldType<T>> mappedField,
        MultiFields multiFields,
        CopyTo copyTo,
        Parser<T> parser,
        String onScriptError
    ) {
        super(simpleName, mappedField, multiFields, copyTo, parser, onScriptError);
        this.nullValue = null;
    }

    public T getNullValue() {
        return nullValue;
    }

    /** A base parser implementation for point formats */
    protected abstract static class PointParser<T> extends Parser<T> {
        protected final String field;
        private final CheckedFunction<XContentParser, T, IOException> objectParser;
        private final T nullValue;
        private final boolean ignoreZValue;
        protected final boolean ignoreMalformed;

        protected PointParser(
            String field,
            CheckedFunction<XContentParser, T, IOException> objectParser,
            T nullValue,
            boolean ignoreZValue,
            boolean ignoreMalformed
        ) {
            this.field = field;
            this.objectParser = objectParser;
            this.nullValue = nullValue == null ? null : validate(nullValue);
            this.ignoreZValue = ignoreZValue;
            this.ignoreMalformed = ignoreMalformed;
        }

        protected abstract T validate(T in);

        protected abstract T createPoint(double x, double y);

        @Override
        public void parse(XContentParser parser, CheckedConsumer<T, IOException> consumer, Consumer<Exception> onMalformed)
            throws IOException {
            if (parser.currentToken() == XContentParser.Token.START_ARRAY) {
                XContentParser.Token token = parser.nextToken();
                if (token == XContentParser.Token.VALUE_NUMBER) {
                    double x = parser.doubleValue();
                    parser.nextToken();
                    double y = parser.doubleValue();
                    token = parser.nextToken();
                    if (token == XContentParser.Token.VALUE_NUMBER) {
                        if (ignoreZValue == false) {
                            throw new ElasticsearchParseException(
                                "Exception parsing coordinates: found Z value [{}] but [ignore_z_value] " + "parameter is [{}]",
                                parser.doubleValue(),
                                ignoreZValue
                            );
                        }
                    } else if (token != XContentParser.Token.END_ARRAY) {
                        throw new ElasticsearchParseException("field type does not accept > 3 dimensions");
                    }

                    T point = createPoint(x, y);
                    consumer.accept(validate(point));
                } else {
                    while (token != XContentParser.Token.END_ARRAY) {
                        if (parser.currentToken() == XContentParser.Token.VALUE_NULL) {
                            if (nullValue != null) {
                                consumer.accept(nullValue);
                            }
                        } else {
                            parseAndConsumeFromObject(parser, consumer, onMalformed);
                        }
                        token = parser.nextToken();
                    }
                }
            } else if (parser.currentToken() == XContentParser.Token.VALUE_NULL) {
                if (nullValue != null) {
                    consumer.accept(nullValue);
                }
            } else {
                parseAndConsumeFromObject(parser, consumer, onMalformed);
            }
        }

        private void parseAndConsumeFromObject(
            XContentParser parser,
            CheckedConsumer<T, IOException> consumer,
            Consumer<Exception> onMalformed
        ) {
            try {
                T point = objectParser.apply(parser);
                consumer.accept(validate(point));
            } catch (Exception e) {
                onMalformed.accept(e);
            }
        }
    }
}
