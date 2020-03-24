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

import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.Scope;
import org.elasticsearch.painless.ir.ClassNode;
import org.elasticsearch.painless.ir.DotSubDefNode;
import org.elasticsearch.painless.lookup.def;
import org.elasticsearch.painless.symbol.ScriptRoot;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Represents a field load/store or shortcut on a def type.  (Internal only.)
 */
public class PSubDefField extends AStoreable {

    protected final String value;

    PSubDefField(Location location, String value) {
        super(location);

        this.value = Objects.requireNonNull(value);
    }

    @Override
    Output analyze(ClassNode classNode, ScriptRoot scriptRoot, Scope scope, AStoreable.Input input) {
        Output output = new Output();

        // TODO: remove ZonedDateTime exception when JodaCompatibleDateTime is removed
        output.actual = input.expected == null || input.expected == ZonedDateTime.class || input.explicit ? def.class : input.expected;

        DotSubDefNode dotSubDefNode = new DotSubDefNode();

        dotSubDefNode.setLocation(location);
        dotSubDefNode.setExpressionType(output.actual);
        dotSubDefNode.setValue(value);

        output.expressionNode = dotSubDefNode;

        return output;
    }

    @Override
    boolean isDefOptimized() {
        return true;
    }

    @Override
    public String toString() {
        return singleLineToString(prefix, value);
    }
}
