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

import org.dbsp.sqlCompiler.dbsp.rust.type.DBSPType;
import org.dbsp.sqlCompiler.dbsp.rust.type.DBSPTypeAny;
import org.dbsp.sqlCompiler.dbsp.rust.type.DBSPTypeFunction;
import org.dbsp.util.IndentStringBuilder;

import javax.annotation.Nullable;

/**
 * Function application expression.
 * Note: the type of the expression is the type of the result returned by the function.
 */
public class DBSPApplyExpression extends DBSPExpression {
    public final DBSPExpression function;
    public final DBSPExpression[] arguments;

    public DBSPApplyExpression(String function, @Nullable DBSPType returnType, DBSPExpression... arguments) {
        super(null, returnType);
        this.function = new DBSPPathExpression(DBSPTypeAny.instance, function);
        this.arguments = arguments;
    }

    @Nullable
    private static DBSPType getReturnType(DBSPType type) {
        if (type.is(DBSPTypeAny.class))
            return type;
        DBSPTypeFunction func = type.to(DBSPTypeFunction.class);
        return func.resultType;
    }

    public DBSPApplyExpression(DBSPExpression function, DBSPExpression... arguments) {
        super(null, DBSPApplyExpression.getReturnType(function.getNonVoidType()));
        this.function = function;
        this.arguments = arguments;
    }

    @Override
    public IndentStringBuilder toRustString(IndentStringBuilder builder) {
        return builder.append(this.function)
                .append("(")
                .join(", ", this.arguments)
                .append(")");
    }
}
