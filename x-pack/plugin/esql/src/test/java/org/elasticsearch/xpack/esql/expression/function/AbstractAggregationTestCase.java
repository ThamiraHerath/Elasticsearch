/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function;

import org.elasticsearch.compute.aggregation.Aggregator;
import org.elasticsearch.compute.aggregation.AggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.AggregatorMode;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.expression.function.aggregate.AggregateFunction;
import org.elasticsearch.xpack.esql.optimizer.FoldNull;
import org.elasticsearch.xpack.esql.planner.PlannerUtils;
import org.elasticsearch.xpack.esql.planner.ToAggregator;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.elasticsearch.compute.data.BlockUtils.toJavaObject;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Base class for aggregation tests.
 */
public abstract class AbstractAggregationTestCase extends AbstractFunctionTestCase {
    /**
     * Converts a list of aggregation test cases into a list of parameter suppliers.
     * Also, adds a default set of extra test cases.
     * <p>
     *     Use if possible, as this method may get updated with new checks in the future.
     * </p>
     *
     * @param entirelyNullPreservesType See {@link #anyNullIsNull(boolean, List)}.
     *                                  Currently unused for aggregations. Kept to avoid calling the wrong method.
     */
    protected static Iterable<Object[]> parameterSuppliersFromTypedDataWithDefaultChecks(
        boolean entirelyNullPreservesType,
        List<TestCaseSupplier> suppliers
    ) {
        return parameterSuppliersFromTypedData(randomizeBytesRefsOffset(suppliers));
    }

    public void testAggregate() {
        var aggregatorFunctionSupplier = resolveAggregatorFunctionSupplier();

        if (aggregatorFunctionSupplier == null) {
            return;
        }

        Object result;
        try (var aggregator = new Aggregator(aggregatorFunctionSupplier.aggregator(driverContext()), AggregatorMode.SINGLE)) {
            Page inputPage = rows(testCase.getMultiRowDataValues());
            try {
                aggregator.processPage(inputPage);
            } finally {
                inputPage.releaseBlocks();
            }

            // ElementType from DataType
            result = extractResultFromAggregator(aggregator, PlannerUtils.toElementType(testCase.expectedType()));
        }

        // TODO: Tests for grouping aggregators
        // TODO: Tests for different AggregatorModes

        assertThat(result, not(equalTo(Double.NaN)));
        assert testCase.getMatcher().matches(Double.POSITIVE_INFINITY) == false;
        assertThat(result, not(equalTo(Double.POSITIVE_INFINITY)));
        assert testCase.getMatcher().matches(Double.NEGATIVE_INFINITY) == false;
        assertThat(result, not(equalTo(Double.NEGATIVE_INFINITY)));
        assertThat(result, testCase.getMatcher());
        if (testCase.getExpectedWarnings() != null) {
            assertWarnings(testCase.getExpectedWarnings());
        }
    }

    public void testAggregateNoInput() {
        var aggregatorFunctionSupplier = resolveAggregatorFunctionSupplier();

        if (aggregatorFunctionSupplier == null) {
            return;
        }

        Object result;
        try (var aggregator = new Aggregator(aggregatorFunctionSupplier.aggregator(driverContext()), AggregatorMode.SINGLE)) {
            result = extractResultFromAggregator(aggregator, ElementType.NULL);
        }

        assertThat(result, nullValue());
    }

    private AggregatorFunctionSupplier resolveAggregatorFunctionSupplier() {
        logger.info(
            "Test Values: " + testCase.getData().stream().map(TestCaseSupplier.TypedData::toString).collect(Collectors.joining(","))
        );
        boolean readFloating = randomBoolean();
        Expression expression = readFloating ? buildDeepCopyOfFieldExpression(testCase) : buildFieldExpression(testCase);
        if (testCase.getExpectedTypeError() != null) {
            assertTypeResolutionFailure(expression);
            return null;
        }
        assertThat(expression, instanceOf(AggregateFunction.class));
        assertThat(expression, instanceOf(ToAggregator.class));
        Expression.TypeResolution resolution = expression.typeResolved();
        if (resolution.unresolved()) {
            throw new AssertionError("expected resolved " + resolution.message());
        }
        expression = new FoldNull().rule(expression);
        assertThat(expression.dataType(), equalTo(testCase.expectedType()));
        logger.info("Result type: " + expression.dataType());

        assertThat(expression, instanceOf(ToAggregator.class));

        var inputChannels = inputChannels();
        return ((ToAggregator) expression).supplier(inputChannels);
    }

    private Object extractResultFromAggregator(Aggregator aggregator, ElementType expectedElementType) {
        var blocksArraySize = randomIntBetween(1, 10);
        var resultBlockIndex = randomIntBetween(0, blocksArraySize - 1);
        var blocks = new Block[blocksArraySize];
        try {
            aggregator.evaluate(blocks, resultBlockIndex, driverContext());

            var block = blocks[resultBlockIndex];

            assertThat(block.elementType(), equalTo(expectedElementType));

            return toJavaObject(blocks[resultBlockIndex], 0);
        } finally {
            Releasables.close(blocks);
        }
    }

    private List<Integer> inputChannels() {
        // TODO: Randomize channels
        return IntStream.range(0, testCase.getMultiRowDataValues().size()).boxed().toList();
    }
}
