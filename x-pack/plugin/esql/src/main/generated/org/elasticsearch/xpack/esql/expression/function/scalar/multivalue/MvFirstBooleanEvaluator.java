// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.xpack.esql.expression.function.scalar.multivalue;

import java.lang.Override;
import java.lang.String;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BooleanVector;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;

/**
 * {@link EvalOperator.ExpressionEvaluator} implementation for {@link MvFirst}.
 * This class is generated. Do not edit it.
 */
public final class MvFirstBooleanEvaluator extends AbstractMultivalueFunction.AbstractEvaluator {
  public MvFirstBooleanEvaluator(EvalOperator.ExpressionEvaluator field,
      DriverContext driverContext) {
    super(driverContext, field);
  }

  @Override
  public String name() {
    return "MvFirst";
  }

  /**
   * Evaluate blocks containing at least one multivalued field.
   */
  @Override
  public Block evalNullable(Block fieldVal) {
    BooleanBlock v = (BooleanBlock) fieldVal;
    int positionCount = v.getPositionCount();
    try (BooleanBlock.Builder builder = driverContext.blockFactory().newBooleanBlockBuilder(positionCount)) {
      for (int p = 0; p < positionCount; p++) {
        int valueCount = v.getValueCount(p);
        if (valueCount == 0) {
          builder.appendNull();
          continue;
        }
        int first = v.getFirstValueIndex(p);
        int end = first + valueCount;
        boolean result = MvFirst.process(v, first, end);
        builder.appendBoolean(result);
      }
      return builder.build();
    }
  }

  /**
   * Evaluate blocks containing at least one multivalued field.
   */
  @Override
  public Block evalNotNullable(Block fieldVal) {
    BooleanBlock v = (BooleanBlock) fieldVal;
    int positionCount = v.getPositionCount();
    try (BooleanVector.FixedBuilder builder = driverContext.blockFactory().newBooleanVectorFixedBuilder(positionCount)) {
      for (int p = 0; p < positionCount; p++) {
        int valueCount = v.getValueCount(p);
        int first = v.getFirstValueIndex(p);
        int end = first + valueCount;
        boolean result = MvFirst.process(v, first, end);
        builder.appendBoolean(result);
      }
      return builder.build().asBlock();
    }
  }

  public static class Factory implements EvalOperator.ExpressionEvaluator.Factory {
    private final EvalOperator.ExpressionEvaluator.Factory field;

    public Factory(EvalOperator.ExpressionEvaluator.Factory field) {
      this.field = field;
    }

    @Override
    public MvFirstBooleanEvaluator get(DriverContext context) {
      return new MvFirstBooleanEvaluator(field.get(context), context);
    }

    @Override
    public String toString() {
      return "MvFirst[field=" + field + "]";
    }
  }
}
