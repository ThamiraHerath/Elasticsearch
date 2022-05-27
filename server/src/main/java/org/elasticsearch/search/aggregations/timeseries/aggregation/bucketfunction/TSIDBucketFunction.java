/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.aggregations.timeseries.aggregation.bucketfunction;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.ObjectArray;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.timeseries.aggregation.TSIDValue;
import org.elasticsearch.search.aggregations.timeseries.aggregation.internal.TSIDInternalAggregation;

import java.util.HashMap;
import java.util.Map;

/**
 * The function is used to aggregator time series lines in the coordinate reduce phase.
 * The _tsid may be exist in many indices, when the bucket ranges will overflow the range of the index,
 * it may be exist
 * e.g a index settings and query config is:<ul>
 * <li>time_series.start_time = 10
 * <li>time_series.end_time = 20
 * <li>interval = 2
 * </ul>
 * When the bucket range is 11-13, the bucket must only in the index.
 * But if the bucket range is 9-11, the bucket may be include other index, so the aggregator function
 * can't compute in the datanode. the tsid bucket function gather all _tsid and the value, and aggregator
 * the result in the coordinate reduce phase.
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class TSIDBucketFunction implements AggregatorBucketFunction<TSIDValue> {
    private final BigArrays bigArrays;
    private ObjectArray<Map<BytesRef, InternalAggregation>> values;
    private final AggregatorBucketFunction aggregatorBucketFunction;

    public TSIDBucketFunction(BigArrays bigArrays, AggregatorBucketFunction aggregatorBucketFunction) {
        this.aggregatorBucketFunction = aggregatorBucketFunction;
        this.bigArrays = bigArrays;
        values = bigArrays.newObjectArray(1);
    }

    @Override
    public String name() {
        return TSIDInternalAggregation.NAME;
    }

    @Override
    public void collect(TSIDValue tsidValue, long bucket) {
        values = bigArrays.grow(values, bucket + 1);
        if (tsidValue.value instanceof InternalAggregation) {
            Map<BytesRef, InternalAggregation> tsidValues = values.get(bucket);
            if (tsidValues == null) {
                tsidValues = new HashMap<>();
                values.set(bucket, tsidValues);
            }
            tsidValues.put(tsidValue.tsid, (InternalAggregation) tsidValue.value);
        } else if (aggregatorBucketFunction instanceof TopkBucketFunction) {
            aggregatorBucketFunction.collect(tsidValue, bucket);
        } else if (tsidValue.value instanceof Double) {
            aggregatorBucketFunction.collect(tsidValue.value, bucket);
        } else {
            throw new UnsupportedOperationException(
                "aggregator [" + aggregatorBucketFunction.name() + "] unsupported collect non-double value"
            );
        }
    }

    @Override
    public void close() {
        Releasables.close(values);
    }

    @Override
    public InternalAggregation getAggregation(
        long bucket,
        Map<String, Object> aggregatorParams,
        DocValueFormat formatter,
        Map<String, Object> metadata
    ) {
        Map<BytesRef, InternalAggregation> value = values.get(bucket);
        if (value != null) {
            return new TSIDInternalAggregation(name(), value, aggregatorBucketFunction.name(), aggregatorParams, formatter, metadata);
        } else {
            return aggregatorBucketFunction.getAggregation(bucket, aggregatorParams, formatter, metadata);
        }
    }

}
