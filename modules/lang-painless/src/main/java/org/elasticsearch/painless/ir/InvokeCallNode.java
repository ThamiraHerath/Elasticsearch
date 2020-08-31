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

package org.elasticsearch.painless.ir;

import org.elasticsearch.painless.ClassWriter;
import org.elasticsearch.painless.MethodWriter;
import org.elasticsearch.painless.lookup.PainlessMethod;
import org.elasticsearch.painless.phase.IRTreeVisitor;
import org.elasticsearch.painless.symbol.WriteScope;

public class InvokeCallNode extends ArgumentsNode {

    /* ---- begin node data ---- */

    private PainlessMethod method;
    private Class<?> box;

    public void setMethod(PainlessMethod method) {
        this.method = method;
    }

    public PainlessMethod getMethod() {
        return method;
    }

    public void setBox(Class<?> box) {
        this.box = box;
    }

    public Class<?> getBox() {
        return box;
    }

    /* ---- end node data, begin visitor ---- */

    @Override
    public <Scope> void visit(IRTreeVisitor<Scope> irTreeVisitor, Scope scope) {
        irTreeVisitor.visitInvokeCall(this, scope);
    }

    @Override
    public <Scope> void visitChildren(IRTreeVisitor<Scope> irTreeVisitor, Scope scope) {
        for (ExpressionNode argumentNode : getArgumentNodes()) {
            argumentNode.visit(irTreeVisitor, scope);
        }
    }

    /* ---- end visitor ---- */

    @Override
    protected void write(ClassWriter classWriter, MethodWriter methodWriter, WriteScope writeScope) {
        methodWriter.writeDebugInfo(location);

        if (box.isPrimitive()) {
            methodWriter.box(MethodWriter.getType(box));
        }

        // TODO(stu): Check for annotation, put constant on stack for this method
        // TODO(stu): Jack, how do I get this into writeScope?
        for (ExpressionNode argumentNode : getArgumentNodes()) {
            argumentNode.write(classWriter, methodWriter, writeScope);
        }

        methodWriter.invokeMethodCall(method);
    }
}
