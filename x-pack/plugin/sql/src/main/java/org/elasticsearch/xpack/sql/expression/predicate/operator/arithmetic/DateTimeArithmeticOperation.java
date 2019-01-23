/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.expression.predicate.operator.arithmetic;

import org.elasticsearch.xpack.sql.SqlIllegalArgumentException;
import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.predicate.operator.arithmetic.BinaryArithmeticProcessor.BinaryArithmeticOperation;
import org.elasticsearch.xpack.sql.tree.Source;
import org.elasticsearch.xpack.sql.type.DataType;
import org.elasticsearch.xpack.sql.type.DataTypeConversion;
import org.elasticsearch.xpack.sql.type.DataTypes;

import static org.elasticsearch.common.logging.LoggerMessageFormat.format;
import static org.elasticsearch.xpack.sql.expression.predicate.operator.arithmetic.BinaryArithmeticProcessor.BinaryArithmeticOperation.SUB;

abstract class DateTimeArithmeticOperation extends ArithmeticOperation {

    DateTimeArithmeticOperation(Source source, Expression left, Expression right, BinaryArithmeticOperation operation) {
        super(source, left, right, operation);
    }
    
    @Override
    protected TypeResolution resolveType() {
        if (!childrenResolved()) {
            return new TypeResolution("Unresolved children");
        }

        // arithmetic operation can work on:
        // 1. numbers
        // 2. intervals (of compatible types)
        // 3. dates and intervals
        // 4. single unit intervals and numbers

        DataType l = left().dataType();
        DataType r = right().dataType();

        // 1. both are numbers
        if (l.isNumeric() && r.isNumeric()) {
            return TypeResolution.TYPE_RESOLVED;
        }
        // 2. 3. 4. intervals
        if ((DataTypes.isInterval(l) || DataTypes.isInterval(r))) {
            if (DataTypeConversion.commonType(l, r) == null) {
                return new TypeResolution(format("[{}] has arguments with incompatible types [{}] and [{}]", symbol(), l, r));
            } else {
                if (function() == SUB && right().dataType().isDateBased() && DataTypes.isInterval(left().dataType())) {
                    throw new SqlIllegalArgumentException("Cannot subtract a date from an interval; do you mean the reverse?");
                }
                return TypeResolution.TYPE_RESOLVED;
            }
        }

        // fall-back to default checks
        return super.resolveType();
    }
    
}
