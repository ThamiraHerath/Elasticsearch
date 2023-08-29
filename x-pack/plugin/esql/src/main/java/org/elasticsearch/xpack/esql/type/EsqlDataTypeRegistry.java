/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.type;

import org.elasticsearch.index.mapper.TimeSeriesParams;
import org.elasticsearch.xpack.ql.type.DataType;
import org.elasticsearch.xpack.ql.type.DataTypeConverter;
import org.elasticsearch.xpack.ql.type.DataTypeRegistry;
import org.elasticsearch.xpack.ql.type.DataTypes;

import java.util.Collection;

public class EsqlDataTypeRegistry implements DataTypeRegistry {

    public static final DataTypeRegistry INSTANCE = new EsqlDataTypeRegistry();

    private EsqlDataTypeRegistry() {}

    @Override
    public Collection<DataType> dataTypes() {
        return EsqlDataTypes.types();
    }

    @Override
    public DataType fromEs(String typeName, TimeSeriesParams.MetricType metricType) {
        if (metricType == TimeSeriesParams.MetricType.COUNTER) {
            // Counter fields will be a counter type, for now they are unsupported
            return DataTypes.UNSUPPORTED;
        }
        return EsqlDataTypes.fromName(typeName);
    }

    @Override
    public DataType fromJava(Object value) {
        return EsqlDataTypes.fromJava(value);
    }

    @Override
    public boolean isUnsupported(DataType type) {
        return EsqlDataTypes.isUnsupported(type);
    }

    @Override
    public boolean canConvert(DataType from, DataType to) {
        return DataTypeConverter.canConvert(from, to);
    }

    @Override
    public Object convert(Object value, DataType type) {
        return DataTypeConverter.convert(value, type);
    }

    @Override
    public DataType commonType(DataType left, DataType right) {
        return DataTypeConverter.commonType(left, right);
    }
}
