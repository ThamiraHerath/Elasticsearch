/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.analytics.rate;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.search.ScoreMode;
import org.elasticsearch.common.Rounding;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.util.DoubleArray;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.bucket.histogram.SizedBucketAggregator;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregator;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.internal.SearchContext;

public abstract class AbstractRateAggregator extends NumericMetricsAggregator.SingleValue {

    protected final ValuesSource valuesSource;
    private final DocValueFormat format;
    private final Rounding.DateTimeUnit rateUnit;
    private final SizedBucketAggregator sizedBucketAggregator;

    protected DoubleArray sums;
    protected DoubleArray compensations;

    public AbstractRateAggregator(
        String name,
        ValuesSourceConfig valuesSourceConfig,
        Rounding.DateTimeUnit rateUnit,
        SearchContext context,
        Aggregator parent,
        Map<String, Object> metadata
    ) throws IOException {
        super(name, context, parent, metadata);
        this.valuesSource = valuesSourceConfig.getValuesSource();
        this.format = valuesSourceConfig.format();
        if (valuesSource != null) {
            sums = context.bigArrays().newDoubleArray(1, true);
            compensations = context.bigArrays().newDoubleArray(1, true);
        }
        this.rateUnit = rateUnit;
        this.sizedBucketAggregator = findSizedBucketAncestor();
    }

    private SizedBucketAggregator findSizedBucketAncestor() {
        SizedBucketAggregator sizedBucketAggregator = null;
        for (Aggregator ancestor = parent; ancestor != null; ancestor = ancestor.parent()) {
            if (ancestor instanceof SizedBucketAggregator) {
                sizedBucketAggregator = (SizedBucketAggregator) ancestor;
                break;
            }
        }
        if (sizedBucketAggregator == null) {
            throw new IllegalArgumentException("The rate aggregation can only be used inside a date histogram");
        }
        return sizedBucketAggregator;
    }

    @Override
    public ScoreMode scoreMode() {
        return valuesSource != null && valuesSource.needsScores() ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES;
    }

    @Override
    public double metric(long owningBucketOrd) {
        if (sizedBucketAggregator == null || valuesSource == null || owningBucketOrd >= sums.size()) {
            return 0.0;
        }
        return sums.get(owningBucketOrd) / sizedBucketAggregator.bucketSize(owningBucketOrd, rateUnit);
    }

    @Override
    public InternalAggregation buildAggregation(long bucket) {
        if (valuesSource == null || bucket >= sums.size()) {
            return buildEmptyAggregation();
        }
        return new InternalRate(name, sums.get(bucket), sizedBucketAggregator.bucketSize(bucket, rateUnit), format, metadata());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalRate(name, 0.0, 1.0, format, metadata());
    }

    @Override
    public void doClose() {
        Releasables.close(sums, compensations);
    }
}
