// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.xpack.esql.expression.function.scalar.convert;

import java.lang.Override;
import java.lang.String;
import org.elasticsearch.common.geo.SpatialPoint;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.PointBlock;
import org.elasticsearch.compute.data.PointVector;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.xpack.ql.tree.Source;

/**
 * {@link EvalOperator.ExpressionEvaluator} implementation for {@link ToLong}.
 * This class is generated. Do not edit it.
 */
public final class ToLongFromGeoPointEvaluator extends AbstractConvertFunction.AbstractEvaluator {
  public ToLongFromGeoPointEvaluator(EvalOperator.ExpressionEvaluator field, Source source,
      DriverContext driverContext) {
    super(driverContext, field, source);
  }

  @Override
  public String name() {
    return "ToLongFromGeoPoint";
  }

  @Override
  public Block evalVector(Vector v) {
    PointVector vector = (PointVector) v;
    int positionCount = v.getPositionCount();
    if (vector.isConstant()) {
      return driverContext.blockFactory().newConstantLongBlockWith(evalValue(vector, 0), positionCount);
    }
    try (LongBlock.Builder builder = driverContext.blockFactory().newLongBlockBuilder(positionCount)) {
      for (int p = 0; p < positionCount; p++) {
        builder.appendLong(evalValue(vector, p));
      }
      return builder.build();
    }
  }

  private static long evalValue(PointVector container, int index) {
    double x = container.getX(index);
    double y = container.getY(index);
    SpatialPoint value = new SpatialPoint(x, y);
    return ToLong.fromGeoPoint(value);
  }

  @Override
  public Block evalBlock(Block b) {
    PointBlock block = (PointBlock) b;
    int positionCount = block.getPositionCount();
    try (LongBlock.Builder builder = driverContext.blockFactory().newLongBlockBuilder(positionCount)) {
      for (int p = 0; p < positionCount; p++) {
        int valueCount = block.getValueCount(p);
        int start = block.getFirstValueIndex(p);
        int end = start + valueCount;
        boolean positionOpened = false;
        boolean valuesAppended = false;
        for (int i = start; i < end; i++) {
          long value = evalValue(block, i);
          if (positionOpened == false && valueCount > 1) {
            builder.beginPositionEntry();
            positionOpened = true;
          }
          builder.appendLong(value);
          valuesAppended = true;
        }
        if (valuesAppended == false) {
          builder.appendNull();
        } else if (positionOpened) {
          builder.endPositionEntry();
        }
      }
      return builder.build();
    }
  }

  private static long evalValue(PointBlock container, int index) {
    double x = container.getX(index);
    double y = container.getY(index);
    SpatialPoint value = new SpatialPoint(x, y);
    return ToLong.fromGeoPoint(value);
  }

  public static class Factory implements EvalOperator.ExpressionEvaluator.Factory {
    private final Source source;

    private final EvalOperator.ExpressionEvaluator.Factory field;

    public Factory(EvalOperator.ExpressionEvaluator.Factory field, Source source) {
      this.field = field;
      this.source = source;
    }

    @Override
    public ToLongFromGeoPointEvaluator get(DriverContext context) {
      return new ToLongFromGeoPointEvaluator(field.get(context), source, context);
    }

    @Override
    public String toString() {
      return "ToLongFromGeoPointEvaluator[field=" + field + "]";
    }
  }
}
