/*
 * Copyright 2023 VMware, Inc.
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

import org.dbsp.sqlCompiler.ir.InnerVisitor;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeAny;

import javax.annotation.Nullable;

/**
 * A comparator that looks at the field of a tuple.
 * A comparator takes a field of a tuple and compares tuples on the specified field.
 * It also takes a direction, indicating whether the sort is ascending or descending.
 */
public class DBSPFieldComparatorExpression extends DBSPComparatorExpression {
    public DBSPComparatorExpression source;
    public final boolean ascending;
    public final int fieldNo;

    public DBSPFieldComparatorExpression(@Nullable Object node, DBSPComparatorExpression source, int fieldNo, boolean ascending) {
        super(node);
        this.source = source;
        this.fieldNo = fieldNo;
        this.ascending = ascending;
    }

    @Override
    public void accept(InnerVisitor visitor) {
        if (!visitor.preorder(this)) return;
        this.source.accept(visitor);
        visitor.postorder(this);
    }

    public DBSPType tupleType() {
        return source.tupleType();
    }
}
