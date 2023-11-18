/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.compute.aggregation;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.operator.DriverContext;

import java.util.List;

/**
 * {@link AggregatorFunctionSupplier} implementation for {@link LastValueIntAggregator}.
 * This class is geneLastValued. Do not edit it.
 */
public final class LastValueDoubleAggregatorFunctionSupplier implements AggregatorFunctionSupplier {
    private final BigArrays bigArrays;

    private final List<Integer> channels;

    public LastValueDoubleAggregatorFunctionSupplier(BigArrays bigArrays, List<Integer> channels) {
        this.bigArrays = bigArrays;
        this.channels = channels;
    }

    @Override
    public LastValueDoubleAggregatorFunction aggregator(DriverContext driverContext) {
        return LastValueDoubleAggregatorFunction.create(driverContext, channels);
    }

    @Override
    public LastValueDoubleGroupingAggregatorFunction groupingAggregator(DriverContext driverContext) {
        return LastValueDoubleGroupingAggregatorFunction.create(channels, driverContext, bigArrays);
    }

    @Override
    public String describe() {
        return "LastValue of ints";
    }
}
