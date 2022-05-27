/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.aggregations.timeseries.aggregation.bucketfunction;

import org.apache.lucene.util.PriorityQueue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.ObjectArray;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.timeseries.aggregation.TSIDValue;
import org.elasticsearch.search.aggregations.timeseries.aggregation.function.TopkFunction;
import org.elasticsearch.search.aggregations.timeseries.aggregation.internal.TimeSeriesTopk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TopkBucketFunction implements AggregatorBucketFunction<TSIDValue<Double>> {

    private final BigArrays bigArrays;
    private ObjectArray<PriorityQueue<TSIDValue<Double>>> values;
    private final int topkSize;
    private final boolean isTop;

    public TopkBucketFunction(BigArrays bigArrays, int size, boolean isTop) {
        this.bigArrays = bigArrays;
        values = bigArrays.newObjectArray(1);
        this.topkSize = size;
        this.isTop = isTop;
    }

    @Override
    public String name() {
        return "topk";
    }

    @Override
    public void collect(TSIDValue<Double> number, long bucket) {
        values = bigArrays.grow(values, bucket + 1);
        PriorityQueue<TSIDValue<Double>> queue = values.get(bucket);
        if (queue == null) {
            queue = TopkFunction.getTopkQueue(topkSize, isTop);
            values.set(bucket, queue);
        }

        queue.insertWithOverflow(number);
    }

    @Override
    public InternalAggregation getAggregation(
        long bucket,
        Map<String, Object> aggregatorParams,
        DocValueFormat formatter,
        Map<String, Object> metadata
    ) {
        PriorityQueue<TSIDValue<Double>> queue = values.get(bucket);
        List<TSIDValue<Double>> values = new ArrayList<>(queue.size());
        for (int b = queue.size() - 1; b >= 0; --b) {
            values.add(queue.pop());
        }
        return new TimeSeriesTopk(name(), values, topkSize, isTop, formatter, metadata);
    }

    @Override
    public void close() {
        Releasables.close(values);
    }
}
