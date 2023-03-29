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

package org.dbsp.sqlCompiler.ir.type;

import org.dbsp.sqlCompiler.ir.InnerVisitor;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPRawTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPVariablePath;
import org.dbsp.util.Linq;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * A Raw Rust tuple.
 */
public class DBSPTypeRawTuple extends DBSPTypeTupleBase {
    public static final DBSPTypeRawTuple EMPTY_TUPLE_TYPE = new DBSPTypeRawTuple();

    private DBSPTypeRawTuple(@Nullable Object node, boolean mayBeNull, DBSPType... tupArgs) {
        super(node, mayBeNull, tupArgs);
    }

    public DBSPTypeRawTuple(DBSPType... tupArgs) {
        this(null, false, tupArgs);
    }

    public DBSPTypeRawTuple(@Nullable Object node, List<DBSPType> tupArgs) {
        this(node, false, tupArgs.toArray(new DBSPType[0]));
    }

    public int size() {
        return this.tupFields.length;
    }

    @Override
    public DBSPType setMayBeNull(boolean mayBeNull) {
        if (mayBeNull == this.mayBeNull)
            return this;
        return new DBSPTypeRawTuple(this.getNode(), mayBeNull, this.tupFields);
    }

    public DBSPTypeRawTuple concat(DBSPTypeRawTuple with) {
        DBSPType[] args = Linq.concat(this.tupFields, with.tupFields);
        return new DBSPTypeRawTuple(args);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(tupFields);
    }

    @Override
    public boolean sameType(@Nullable DBSPType type) {
        if (!super.sameType(type))
            return false;
        assert type != null;
        if (!type.is(DBSPTypeRawTuple.class))
            return false;
        DBSPTypeRawTuple other = type.to(DBSPTypeRawTuple.class);
        return DBSPType.sameTypes(this.tupFields, other.tupFields);
    }

    @Override
    public void accept(InnerVisitor visitor) {
        if (!visitor.preorder(this)) return;
        for (DBSPType type: this.tupFields)
            type.accept(visitor);
        visitor.postorder(this);
    }

    @Override
    public DBSPExpression caster(DBSPType to) {
        if (!to.is(DBSPTypeRawTuple.class))
            return super.caster(to);  // throw
        DBSPTypeRawTuple tuple = to.to(DBSPTypeRawTuple.class);
        if (tuple.size() != this.size())
            return super.caster(to);  // throw
        DBSPVariablePath var = new DBSPVariablePath("x", this);
        DBSPExpression[] casts = new DBSPExpression[this.tupFields.length];
        for (int i = 0; i < this.tupFields.length; i++) {
            casts[i] = this.tupFields[i].caster(tuple.tupFields[i]);
            casts[i] = casts[i].call(var.field(i));
        }
        return new DBSPRawTupleExpression(casts).closure(var.asRefParameter());
    }
}
