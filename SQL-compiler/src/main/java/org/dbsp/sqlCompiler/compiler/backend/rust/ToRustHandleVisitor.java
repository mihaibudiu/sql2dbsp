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

package org.dbsp.sqlCompiler.compiler.backend.rust;

import org.dbsp.sqlCompiler.circuit.DBSPPartialCircuit;
import org.dbsp.sqlCompiler.circuit.IDBSPNode;
import org.dbsp.sqlCompiler.circuit.DBSPCircuit;
import org.dbsp.sqlCompiler.circuit.operator.DBSPOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSinkOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSourceOperator;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeZSet;
import org.dbsp.util.IndentStream;
import org.dbsp.util.Utilities;

/**
 * Generate Rust for a circuit, but with an API using handles.
 * Output generated has this structure:
 * pub fn test_circuit(workers: usize) -> (DBSPHandle, Catalog) {
 *     let mut catalog = Catalog::new();
 *     let (circuit, handles) = Runtime::init_circuit(workers, |circuit| {
 *         let (input, handle0) = circuit.add_input_zset::<TestStruct, i32>();
 *         let handle1 = input.output();
 *         (handle0, handle1)
 *     }).unwrap();
 *     catalog.register_input_zset_handle("test_input1", handles.0);
 *     catalog.register_output_batch_handle("test_output1", handles.1);
 *     (circuit, catalog)
 * }
 */
public class ToRustHandleVisitor extends ToRustVisitor {
    private final String functionName;
    int handleCount = 0;

    public ToRustHandleVisitor(IndentStream builder, String functionName) {
        super(builder);
        this.functionName = functionName;
    }

    @Override
    public boolean preorder(DBSPSourceOperator operator) {
        this.writeComments(operator)
                .append("let (")
                .append(operator.outputName)
                .append(", handle")
                .append(this.handleCount++)
                .append(") = circuit.add_input_zset::<");
        DBSPTypeZSet type = operator.getNonVoidType().to(DBSPTypeZSet.class);
        type.elementType.accept(this.innerVisitor);
        this.builder.append(", ");
        type.weightType.accept(this.innerVisitor);
        this.builder.append(">();");
        return false;
    }

    @Override
    public boolean preorder(DBSPSinkOperator operator) {
        this.writeComments(operator.query);
        this.writeComments(operator)
                .append("let handle")
                .append(this.handleCount++)
                .append(" = ")
                .append(operator.input().getName())
                .append(".output();");
        return false;
    }

    @Override
    public boolean preorder(DBSPCircuit circuit) {
        this.setCircuit(circuit);
        circuit.circuit.accept(this);
        return false;
    }

    @Override
    public boolean preorder(DBSPPartialCircuit circuit) {
        this.builder.append("pub fn ")
                .append(this.functionName)
                .append("(workers: usize) -> (DBSPHandle, Catalog) {")
                .increase()
                .append("let mut catalog = Catalog::new();")
                .newline()
                .append("let (circuit, handles) = Runtime::init_circuit(workers, |circuit| {")
                .increase();

        for (IDBSPNode node : circuit.getCode())
            super.processNode(node);
        this.builder.append("(");
        for (int i = 0; i < this.handleCount; i++)
            this.builder.append("handle")
                    .append(i)
                    .append(",");
        this.builder.append(")")
                .newline()
                .decrease()
                .append("}).unwrap();")
                .newline();

        this.handleCount = 0;
        for (DBSPOperator i : circuit.inputOperators) {
            this.builder.append("catalog.register_input_zset_handle(")
                    .append(Utilities.doubleQuote(i.getName()))
                    .append(", handles.")
                    .append(this.handleCount++)
                    .append(");")
                    .newline();
        }
        for (DBSPOperator o : circuit.outputOperators) {
            this.builder.append("catalog.register_output_batch_handle(")
                    .append(Utilities.doubleQuote(o.getName()))
                    .append(", handles.")
                    .append(this.handleCount++)
                    .append(");")
                    .newline();
        }

        this.builder
                .append("(circuit, catalog)")
                .newline()
                .decrease()
                .append("}")
                .newline();
        return false;
    }
}
