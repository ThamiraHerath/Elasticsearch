/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless.node;

import org.elasticsearch.painless.AnalyzerCaster;
import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.SematicScope;
import org.elasticsearch.painless.ir.ClassNode;
import org.elasticsearch.painless.ir.ElvisNode;
import org.elasticsearch.painless.lookup.PainlessCast;
import org.elasticsearch.painless.symbol.ScriptScope;

import static java.util.Objects.requireNonNull;

/**
 * The Elvis operator ({@code ?:}), a null coalescing operator. Binary operator that evaluates the first expression and return it if it is
 * non null. If the first expression is null then it evaluates the second expression and returns it.
 */
public class EElvis extends AExpression {

    private final AExpression leftNode;
    private final AExpression rightNode;

    public EElvis(int identifier, Location location, AExpression leftNode, AExpression rightNode) {
        super(identifier, location);

        this.leftNode = requireNonNull(leftNode);
        this.rightNode = requireNonNull(rightNode);
    }

    public AExpression getLeftNode() {
        return leftNode;
    }

    public AExpression getRightNode() {
        return rightNode;
    }

    @Override
    Output analyze(ClassNode classNode, ScriptScope scriptScope, SematicScope sematicScope, Input input) {
        if (input.write) {
            throw createError(new IllegalArgumentException("invalid assignment: cannot assign a value to elvis operation [?:]"));
        }

        if (input.read == false) {
            throw createError(new IllegalArgumentException("not a statement: result not used from elvis operation [?:]"));
        }

        Output output = new Output();

        if (input.expected != null && input.expected.isPrimitive()) {
            throw createError(new IllegalArgumentException("Elvis operator cannot return primitives"));
        }

        Input leftInput = new Input();
        leftInput.expected = input.expected;
        leftInput.explicit = input.explicit;
        leftInput.internal = input.internal;
        Output leftOutput = analyze(leftNode, classNode, scriptScope, sematicScope, leftInput);

        Input rightInput = new Input();
        rightInput.expected = input.expected;
        rightInput.explicit = input.explicit;
        rightInput.internal = input.internal;
        Output rightOutput = analyze(rightNode, classNode, scriptScope, sematicScope, rightInput);

        output.actual = input.expected;

        if (leftNode instanceof ENull) {
            throw createError(new IllegalArgumentException("Extraneous elvis operator. LHS is null."));
        }
        if (leftNode instanceof EBoolean || leftNode instanceof ENumeric || leftNode instanceof EDecimal
                || leftNode instanceof EString || leftNode instanceof EConstant) {
            throw createError(new IllegalArgumentException("Extraneous elvis operator. LHS is a constant."));
        }
        if (leftOutput.actual.isPrimitive()) {
            throw createError(new IllegalArgumentException("Extraneous elvis operator. LHS is a primitive."));
        }
        if (rightNode instanceof ENull) {
            throw createError(new IllegalArgumentException("Extraneous elvis operator. RHS is null."));
        }

        if (input.expected == null) {
            Class<?> promote = AnalyzerCaster.promoteConditional(leftOutput.actual, rightOutput.actual);

            leftInput.expected = promote;
            rightInput.expected = promote;
            output.actual = promote;
        }

        PainlessCast leftCast = AnalyzerCaster.getLegalCast(leftNode.getLocation(),
                leftOutput.actual, leftInput.expected, leftInput.explicit, leftInput.internal);
        PainlessCast rightCast = AnalyzerCaster.getLegalCast(rightNode.getLocation(),
                rightOutput.actual, rightInput.expected, rightInput.explicit, rightInput.internal);

        ElvisNode elvisNode = new ElvisNode();

        elvisNode.setLeftNode(cast(leftOutput.expressionNode, leftCast));
        elvisNode.setRightNode(cast(rightOutput.expressionNode, rightCast));

        elvisNode.setLocation(getLocation());
        elvisNode.setExpressionType(output.actual);

        output.expressionNode = elvisNode;

        return output;
    }
}
