/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.mapper;

import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.geo.Orientation;

import java.util.Map;
import java.util.function.Function;

/**
 * Base class for {@link GeoShapeFieldMapper}
 */
public abstract class AbstractShapeGeometryFieldMapper<T> extends AbstractGeometryFieldMapper<T> {

    public static Parameter<Explicit<Boolean>> coerceParam(Function<FieldMapper, Explicit<Boolean>> initializer, boolean coerceByDefault) {
        return Parameter.explicitBoolParam("coerce", true, initializer, coerceByDefault);
    }

    private static final Explicit<Orientation> IMPLICIT_RIGHT = new Explicit<>(Orientation.RIGHT, false);

    public static Parameter<Explicit<Orientation>> orientationParam(Function<FieldMapper, Explicit<Orientation>> initializer) {
        return new Parameter<>(
            "orientation",
            true,
            () -> IMPLICIT_RIGHT,
            (n, c, o) -> new Explicit<>(Orientation.fromString(o.toString()), true),
            initializer,
            (b, f, v) -> b.field(f, v.value()),
            v -> v.value().toString()
        );
    }

    public abstract static class AbstractShapeGeometryFieldType<T> extends AbstractGeometryFieldType<T> {

        private final Orientation orientation;

        protected AbstractShapeGeometryFieldType(
            boolean isSearchable,
            boolean isStored,
            boolean hasDocValues,
            Parser<T> parser,
            Orientation orientation,
            Map<String, String> meta
        ) {
            super(isSearchable, isStored, hasDocValues, parser, meta);
            this.orientation = orientation;
        }

        public Orientation orientation() {
            return this.orientation;
        }
    }

    protected Explicit<Boolean> coerce;
    protected Explicit<Orientation> orientation;

    protected AbstractShapeGeometryFieldMapper(
        String simpleName,
        MappedField mappedField,
        Explicit<Boolean> ignoreMalformed,
        Explicit<Boolean> coerce,
        Explicit<Boolean> ignoreZValue,
        Explicit<Orientation> orientation,
        MultiFields multiFields,
        CopyTo copyTo,
        Parser<T> parser
    ) {
        super(simpleName, mappedField, ignoreMalformed, ignoreZValue, multiFields, copyTo, parser);
        this.coerce = coerce;
        this.orientation = orientation;
    }

    public boolean coerce() {
        return coerce.value();
    }

    public Orientation orientation() {
        return orientation.value();
    }
}
