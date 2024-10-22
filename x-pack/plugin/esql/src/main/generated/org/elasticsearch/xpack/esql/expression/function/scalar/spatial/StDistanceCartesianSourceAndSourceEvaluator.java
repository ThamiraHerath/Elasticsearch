// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.xpack.esql.expression.function.scalar.spatial;

import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.core.tree.Source;

/**
 * {@link EvalOperator.ExpressionEvaluator} implementation for {@link StDistance}.
 * This class is generated. Do not edit it.
 */
public final class StDistanceCartesianSourceAndSourceEvaluator implements EvalOperator.ExpressionEvaluator {
  private final Source source;

  private final EvalOperator.ExpressionEvaluator left;

  private final EvalOperator.ExpressionEvaluator right;

  private final DriverContext driverContext;

  private Warnings warnings;

  public StDistanceCartesianSourceAndSourceEvaluator(Source source,
      EvalOperator.ExpressionEvaluator left, EvalOperator.ExpressionEvaluator right,
      DriverContext driverContext) {
    this.source = source;
    this.left = left;
    this.right = right;
    this.driverContext = driverContext;
  }

  @Override
  public Block eval(Page page) {
    try (BytesRefBlock leftBlock = (BytesRefBlock) left.eval(page)) {
      try (BytesRefBlock rightBlock = (BytesRefBlock) right.eval(page)) {
        return eval(page.getPositionCount(), leftBlock, rightBlock);
      }
    }
  }

  public DoubleBlock eval(int positionCount, BytesRefBlock leftBlock, BytesRefBlock rightBlock) {
    try(DoubleBlock.Builder result = driverContext.blockFactory().newDoubleBlockBuilder(positionCount)) {
      position: for (int p = 0; p < positionCount; p++) {
        boolean allBlocksAreNulls = true;
        if (!leftBlock.isNull(p)) {
          allBlocksAreNulls = false;
        }
        if (!rightBlock.isNull(p)) {
          allBlocksAreNulls = false;
        }
        if (allBlocksAreNulls) {
          result.appendNull();
          continue position;
        }
        try {
          StDistance.processCartesianSourceAndSource(result, p, leftBlock, rightBlock);
        } catch (IllegalArgumentException e) {
          warnings().registerException(e);
          result.appendNull();
        }
      }
      return result.build();
    }
  }

  @Override
  public String toString() {
    return "StDistanceCartesianSourceAndSourceEvaluator[" + "left=" + left + ", right=" + right + "]";
  }

  @Override
  public void close() {
    Releasables.closeExpectNoException(left, right);
  }

  private Warnings warnings() {
    if (warnings == null) {
      this.warnings = Warnings.createWarnings(
              driverContext.warningsMode(),
              source.source().getLineNumber(),
              source.source().getColumnNumber(),
              source.text()
          );
    }
    return warnings;
  }

  static class Factory implements EvalOperator.ExpressionEvaluator.Factory {
    private final Source source;

    private final EvalOperator.ExpressionEvaluator.Factory left;

    private final EvalOperator.ExpressionEvaluator.Factory right;

    public Factory(Source source, EvalOperator.ExpressionEvaluator.Factory left,
        EvalOperator.ExpressionEvaluator.Factory right) {
      this.source = source;
      this.left = left;
      this.right = right;
    }

    @Override
    public StDistanceCartesianSourceAndSourceEvaluator get(DriverContext context) {
      return new StDistanceCartesianSourceAndSourceEvaluator(source, left.get(context), right.get(context), context);
    }

    @Override
    public String toString() {
      return "StDistanceCartesianSourceAndSourceEvaluator[" + "left=" + left + ", right=" + right + "]";
    }
  }
}
