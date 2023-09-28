// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.xpack.esql.expression.function.scalar.convert;

import java.lang.Override;
import java.lang.String;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.DoubleVector;
import org.elasticsearch.compute.data.Vector;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.xpack.ql.tree.Source;

/**
 * {@link EvalOperator.ExpressionEvaluator} implementation for {@link ToRadians}.
 * This class is generated. Do not edit it.
 */
public final class ToRadiansEvaluator extends AbstractConvertFunction.AbstractEvaluator {
  private final DriverContext driverContext;

  public ToRadiansEvaluator(EvalOperator.ExpressionEvaluator field, Source source,
      DriverContext driverContext) {
    super(field, source);
    this.driverContext = driverContext;
  }

  @Override
  public String name() {
    return "ToRadians";
  }

  @Override
  public Block evalVector(Vector v) {
    DoubleVector vector = (DoubleVector) v;
    int positionCount = v.getPositionCount();
    if (vector.isConstant()) {
      try {
        return driverContext.blockFactory().newConstantDoubleBlockWith(evalValue(vector, 0), positionCount);
      } catch (Exception e) {
        registerException(e);
        return Block.constantNullBlock(positionCount, driverContext.blockFactory());
      }
    }
    DoubleBlock.Builder builder = DoubleBlock.newBlockBuilder(positionCount, driverContext.blockFactory());
    for (int p = 0; p < positionCount; p++) {
      try {
        builder.appendDouble(evalValue(vector, p));;
      } catch (Exception e) {
        registerException(e);
        builder.appendNull();
      }
    }
    return builder.build();
  }

  private static double evalValue(DoubleVector container, int index) {
    double value = container.getDouble(index);
    return ToRadians.process(value);
  }

  @Override
  public Block evalBlock(Block b) {
    DoubleBlock block = (DoubleBlock) b;
    int positionCount = block.getPositionCount();
    DoubleBlock.Builder builder = DoubleBlock.newBlockBuilder(positionCount, driverContext.blockFactory());
    for (int p = 0; p < positionCount; p++) {
      int valueCount = block.getValueCount(p);
      int start = block.getFirstValueIndex(p);
      int end = start + valueCount;
      boolean positionOpened = false;
      boolean valuesAppended = false;
      for (int i = start; i < end; i++) {
        try {
          double value = evalValue(block, i);
          if (positionOpened == false && valueCount > 1) {
            builder.beginPositionEntry();
            positionOpened = true;
          }
          builder.appendDouble(value);
          valuesAppended = true;
        } catch (Exception e) {
          registerException(e);
        }
      }
      if (valuesAppended == false) {
        builder.appendNull();
      } else if (positionOpened) {
        builder.endPositionEntry();
      }
    }
    return builder.build();
  }

  private static double evalValue(DoubleBlock container, int index) {
    double value = container.getDouble(index);
    return ToRadians.process(value);
  }
}
