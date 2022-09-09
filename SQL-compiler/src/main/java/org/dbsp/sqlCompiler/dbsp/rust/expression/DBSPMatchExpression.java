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

package org.dbsp.sqlCompiler.dbsp.rust.expression;

import org.dbsp.sqlCompiler.dbsp.circuit.DBSPNode;
import org.dbsp.sqlCompiler.dbsp.rust.pattern.DBSPPattern;
import org.dbsp.sqlCompiler.dbsp.rust.type.DBSPType;
import org.dbsp.util.IndentStringBuilder;

import java.util.List;

/**
 * A (Rust) match expression.
 */
public class DBSPMatchExpression extends DBSPExpression {
    public static class Case extends DBSPNode {
        public final DBSPPattern against;
        public final DBSPExpression result;

        public Case(DBSPPattern against, DBSPExpression result) {
            super(null);
            this.against = against;
            this.result = result;
        }

        @Override
        public IndentStringBuilder toRustString(IndentStringBuilder builder) {
            return builder.append(this.against)
                    .append(" => ")
                    .append(this.result);
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
            if (!c.result.getNonVoidType().same(type))
                throw new RuntimeException("Type mismatch in case " + c +
                        " expected " + type + " got " + c.result.getNonVoidType());
        }
    }

    @Override
    public IndentStringBuilder toRustString(IndentStringBuilder builder) {
        return builder.append("(match ")
                .append(this.matched)
                .append(" {").increase()
                .intercalate(",\n", this.cases)
                .decrease()
                .append("})");
    }
}
