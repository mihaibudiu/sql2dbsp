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

package org.dbsp.sqlCompiler.ir.expression;

import org.dbsp.sqlCompiler.circuit.DBSPNode;
import org.dbsp.sqlCompiler.circuit.IDBSPInnerNode;
import org.dbsp.sqlCompiler.ir.InnerVisitor;
import org.dbsp.sqlCompiler.ir.pattern.DBSPPattern;
import org.dbsp.sqlCompiler.ir.type.DBSPType;

import java.util.List;

/**
 * A (Rust) match expression.
 */
public class DBSPMatchExpression extends DBSPExpression {
    public static class Case extends DBSPNode implements IDBSPInnerNode {
        public final DBSPPattern against;
        public final DBSPExpression result;

        public Case(DBSPPattern against, DBSPExpression result) {
            super(null);
            this.against = against;
            this.result = result;
        }

        @Override
        public void accept(InnerVisitor visitor) {
            if (!visitor.preorder(this)) return;
            this.against.accept(visitor);
            this.result.accept(visitor);
            visitor.postorder(this);
        }
    }

    public final DBSPExpression matched;
    public final List<Case> cases;

    public DBSPMatchExpression(DBSPExpression matched, List<Case> cases, DBSPType type) {
        super(null, type);
        this.matched = matched;
        this.cases = cases;
        if (cases.isEmpty())
            throw new RuntimeException("Empty list of cases for match");
        for (Case c: cases) {
            if (!c.result.getNonVoidType().sameType(type))
                throw new RuntimeException("Type mismatch in case " + c +
                        " expected " + type + " got " + c.result.getNonVoidType());
        }
    }

    @Override
    public void accept(InnerVisitor visitor) {
        if (!visitor.preorder(this)) return;
        this.matched.accept(visitor);
        for (Case c: this.cases)
            c.accept(visitor);
        visitor.postorder(this);
    }
}
