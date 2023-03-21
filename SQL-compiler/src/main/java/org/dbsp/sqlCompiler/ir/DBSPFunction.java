/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.dbsp.sqlCompiler.ir;

import org.dbsp.sqlCompiler.circuit.DBSPNode;
import org.dbsp.sqlCompiler.circuit.IDBSPDeclaration;
import org.dbsp.sqlCompiler.ir.expression.DBSPApplyExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeFunction;
import org.dbsp.sqlCompiler.ir.type.IHasType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A (Rust) function.
 */
public class DBSPFunction extends DBSPNode implements IHasType, IDBSPDeclaration {
    public final String name;
    public final List<DBSPParameter> parameters;
    // Null if function returns void.
    @Nullable
    public final DBSPType returnType;
    public final DBSPExpression body;
    public final List<String> annotations;
    public final DBSPTypeFunction type;

    public DBSPFunction(String name, List<DBSPParameter> parameters, @Nullable DBSPType returnType, DBSPExpression body) {
        super(null);
        this.name = name;
        this.parameters = parameters;
        this.returnType = returnType;
        this.body = body;
        this.annotations = new ArrayList<>();
        DBSPType[] argTypes = new DBSPType[parameters.size()];
        for (int i = 0; i < argTypes.length; i++)
            argTypes[i] = parameters.get(i).getNonVoidType();
        this.type = new DBSPTypeFunction(returnType, argTypes);
    }

    public DBSPFunction addAnnotation(String annotation) {
        this.annotations.add(annotation);
        return this;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Nullable
    @Override
    public DBSPType getType() {
        return this.type;
    }

    @Override
    public void accept(InnerVisitor visitor) {
        if (!visitor.preorder(this)) return;
        if (this.returnType != null)
            this.returnType.accept(visitor);
        for (DBSPParameter argument: this.parameters)
            argument.accept(visitor);
        this.body.accept(visitor);
        visitor.postorder(this);
    }

    @Override
    public void accept(CircuitVisitor visitor) {
        if (!visitor.preorder(this)) return;
        visitor.postorder(this);
    }

    public DBSPExpression getReference() {
        return this.type.var(this.name);
    }

    public DBSPExpression call(DBSPExpression... arguments) {
        return new DBSPApplyExpression(this.getReference(), arguments);
    }
}
