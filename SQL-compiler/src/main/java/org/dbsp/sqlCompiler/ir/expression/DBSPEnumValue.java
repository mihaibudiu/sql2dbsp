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

import org.dbsp.sqlCompiler.ir.Visitor;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeUser;

/**
 * For now only support simple enums, with no additional arguments.
 */
public class DBSPEnumValue extends DBSPExpression {
    public final String enumName;
    public final String constructor;

    public DBSPEnumValue(String enumName, String constructor) {
        super(null, new DBSPTypeUser(null, enumName, false));
        this.enumName = enumName;
        this.constructor = constructor;
    }

   @Override
    public void accept(Visitor visitor) {
        if (!visitor.preorder(this)) return;
        if (this.type != null)
            this.type.accept(visitor);
        visitor.postorder(this);
    }

    @Override
    public boolean shallowSameExpression(DBSPExpression other) {
        if (this == other)
            return true;
        DBSPEnumValue ev = other.as(DBSPEnumValue.class);
        if (ev == null)
            return false;
        return this.enumName.equals(ev.enumName) &&
                this.constructor.equals(ev.constructor);
    }
}
