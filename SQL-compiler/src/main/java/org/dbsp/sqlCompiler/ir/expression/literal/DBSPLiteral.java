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

package org.dbsp.sqlCompiler.ir.expression.literal;

import org.dbsp.sqlCompiler.ir.InnerVisitor;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeAny;

import javax.annotation.Nullable;

/**
 * This is not just a base class, it also can be used to represent NULL
 * literals of any type.  Maybe that's a bad idea.
 */
public class DBSPLiteral extends DBSPExpression {
    public final boolean isNull;
    @Nullable
    public final Object value;

    protected DBSPLiteral(@Nullable Object node, DBSPType type, @Nullable Object value) {
        super(node, type);
        this.isNull = value == null;
        this.value = value;
        if (this.isNull && !type.mayBeNull && !type.is(DBSPTypeAny.class))
            throw new RuntimeException("Type " + type + " cannot represent null");
    }

    /**
     * Represents a "null" value of the specified type.
     */
    public static DBSPLiteral none(DBSPType type) {
        return new DBSPLiteral(null, type, null);
    }

    public String noneString() {
        return "None::<" + this.getNonVoidType().setMayBeNull(false) + ">";
    }

    public String wrapSome(String value) {
        if (this.getNonVoidType().mayBeNull)
            return "Some(" + value + ")";
        return value;
    }

    public boolean mayBeNull() {
        return this.getNonVoidType().mayBeNull;
    }

    @Override
    public void accept(InnerVisitor visitor) {
        if (!visitor.preorder(this)) return;
        visitor.postorder(this);
    }

    /*
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DBSPLiteral that = (DBSPLiteral) o;
        return isNull == that.isNull && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isNull, value);
    }
     */
}
