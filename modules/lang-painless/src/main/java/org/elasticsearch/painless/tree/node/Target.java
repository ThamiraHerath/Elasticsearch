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

package org.elasticsearch.painless.tree.node;

import org.elasticsearch.painless.tree.utility.Operation;
import org.objectweb.asm.commons.GeneratorAdapter;

public abstract class Target {
    protected final String location;

    public Target(final String location) {
        this.location = location;
    }

    protected abstract void load(final GeneratorAdapter adapter);
    protected abstract void store(final GeneratorAdapter adapter, final Expression expression);
    protected abstract void pre(final GeneratorAdapter adapter, final Expression expression, final Operation operation);
    protected abstract void post(final GeneratorAdapter adapter, final Expression expression, final Operation operation);
    protected abstract void compound(final GeneratorAdapter adapter, final Expression expression);
}
