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

import org.elasticsearch.painless.Locals;
import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.ir.ExpressionNode;
import org.elasticsearch.painless.symbol.ScriptRoot;

import java.util.Objects;
import java.util.Set;

/**
 * Represents a string constant.
 */
public final class EString extends AExpression {

    public EString(Location location, String string) {
        super(location);

        this.constant = Objects.requireNonNull(string);
    }

    @Override
    void extractVariables(Set<String> variables) {
        // Do nothing.
    }

    @Override
    void analyze(ScriptRoot scriptRoot, Locals locals) {
        if (!read) {
            throw createError(new IllegalArgumentException("Must read from constant [" + constant + "]."));
        }

        actual = String.class;
    }

    @Override
    ExpressionNode write() {
        throw new IllegalStateException("Illegal tree structure.");
    }

    @Override
    public String toString() {
        return singleLineToString("'" + constant.toString() + "'");
    }
}
