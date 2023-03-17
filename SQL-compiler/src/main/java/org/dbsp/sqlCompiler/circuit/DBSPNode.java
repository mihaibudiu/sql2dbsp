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

package org.dbsp.sqlCompiler.circuit;

import org.dbsp.sqlCompiler.compiler.backend.ToRustInnerVisitor;
import org.dbsp.sqlCompiler.compiler.backend.ToRustVisitor;
import org.dbsp.util.IdGen;

import javax.annotation.Nullable;

/**
 * Base interface for all DBSP nodes.
 */
public abstract class DBSPNode
        extends IdGen
        implements IDBSPNode {

    /**
     * Original query Sql node that produced this node.
     */
    private final @Nullable
    Object node;

    protected DBSPNode(@Nullable Object node) {
        this.node = node;
    }

    @Nullable
    public Object getNode() { return this.node; }

    @Override
    public String toString() {
        if (this.is(IDBSPInnerNode.class))
            return ToRustInnerVisitor.toRustString(this.to(IDBSPInnerNode.class));
        return ToRustVisitor.circuitToRustString(this.to(IDBSPOuterNode.class));
    }
}
