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

import org.dbsp.sqlCompiler.circuit.*;
import org.dbsp.sqlCompiler.circuit.operator.*;
import org.dbsp.sqlCompiler.ir.CircuitVisitor;
import org.dbsp.sqlCompiler.ir.InnerVisitor;
import org.dbsp.sqlCompiler.ir.type.*;
import org.dbsp.util.*;

import javax.annotation.Nullable;

/**
 * This visitor generate a Rust implementation of the program.
 */
public class ToRustVisitor extends CircuitVisitor {
    protected final IndentStream builder;
    public final InnerVisitor innerVisitor;

    public ToRustVisitor(IndentStream builder) {
        super(true);
        this.builder = builder;
        this.innerVisitor = new ToRustInnerVisitor(builder);
    }

    //////////////// Operators

    private void genRcCell(DBSPOperator op) {
        this.builder.append("let ")
                .append(op.getName())
                .append(" = Rc::new(RefCell::<");
        op.getNonVoidType().accept(this.innerVisitor);
        this.builder.append(">::new(Default::default()));")
                .newline();
        this.builder.append("let ")
                .append(op.getName())
                .append("_external = ")
                .append(op.getName())
                .append(".clone();")
                .newline();
        if (op instanceof DBSPSourceOperator) {
            this.builder.append("let ")
                    .append(op.getName())
                    .append(" = Generator::new(move || ")
                    .append(op.getName())
                    .append(".borrow().clone());")
                    .newline();
        }
    }

    void processNode(IDBSPNode node) {
        DBSPOperator op = node.as(DBSPOperator.class);
        if (op != null)
            this.generateOperator(op);
        IDBSPInnerNode inner = node.as(IDBSPInnerNode.class);
        if (inner != null) {
            inner.accept(this.innerVisitor);
            this.builder.newline();
        }
    }

    void generateOperator(DBSPOperator operator) {
        if (operator.getNode() != null) {
            String str = operator.getNode().toString();
            this.writeComments(str);
        }
        operator.accept(this);
        this.builder.newline();
    }

    public void generateBody(DBSPPartialCircuit circuit) {
        this.builder.append("let root = dbsp::RootCircuit::build(|circuit| {")
                .increase();
        for (IDBSPNode node : circuit.getCode())
            this.processNode(node);
        this.builder.decrease()
                .append("})")
                .append(".unwrap();")
                .newline();
    }

    @Override
    public boolean preorder(DBSPCircuit circuit) {
        this.builder.append("fn ")
                .append(circuit.name);
        circuit.circuit.accept(this);
        return false;
    }

    @Override
    public boolean preorder(DBSPPartialCircuit circuit) {
        // function prototype:
        // fn name() -> impl FnMut(T0, T1) -> (O0, O1) {
        boolean first = true;
        this.builder.append("() -> impl FnMut(");
        for (DBSPOperator i : circuit.inputOperators) {
            if (!first)
                this.builder.append(",");
            first = false;
            i.getNonVoidType().accept(this.innerVisitor);
        }
        this.builder.append(") -> ");
        DBSPTypeRawTuple tuple = new DBSPTypeRawTuple(null, Linq.map(circuit.outputOperators, DBSPOperator::getNonVoidType));
        tuple.accept(this.innerVisitor);
        this.builder.append(" {").increase();
        // For each input and output operator a corresponding Rc cell
        for (DBSPOperator i : circuit.inputOperators)
            this.genRcCell(i);

        for (DBSPOperator o : circuit.outputOperators)
            this.genRcCell(o);

        // Circuit body
        this.generateBody(circuit);

        // Create the closure and return it.
        this.builder.append("return move |")
                .joinS(", ", Linq.map(circuit.inputOperators, DBSPOperator::getName))
                .append("| {")
                .increase();

        for (DBSPOperator i : circuit.inputOperators)
            builder.append("*")
                    .append(i.getName())
                    .append("_external.borrow_mut() = ")
                    .append(i.getName())
                    .append(";")
                    .newline();
        this.builder.append("root.0.step().unwrap();")
                .newline()
                .append("return ")
                .append("(")
                .intercalateS(", ",
                        Linq.map(circuit.outputOperators, o -> o.getName() + "_external.borrow().clone()"))
                .append(")")
                .append(";")
                .newline()
                .decrease()
                .append("};")
                .newline()
                .decrease()
                .append("}")
                .newline();
        return false;
    }

    @Override
    public boolean preorder(DBSPSourceOperator operator) {
        this.writeComments(operator)
                .append("let ")
                .append(operator.getName())
                .append(" = ")
                .append("circuit.add_source(")
                .append(operator.outputName)
                .append(");");
        return false;
    }

    @Override
    public boolean preorder(DBSPIncrementalDistinctOperator operator) {
        DBSPType streamType = new DBSPTypeStream(operator.outputType);
        this.writeComments(operator)
                .append("let ")
                .append(operator.getName())
                .append(": ");
        streamType.accept(this.innerVisitor);
        this.builder.append(" = ")
                .append(operator.input().getName())
                .append(".")
                .append(operator.operation)
                .append("();");
        return false;
    }

    @Override
    public boolean preorder(DBSPSinkOperator operator) {
        this.writeComments(operator.query);
        this.writeComments(operator)
                .append(operator.input().getName())
                .append(".")
                .append(operator.operation) // inspect
                .append("(move |m| { *")
                .append(operator.getName())
                .append(".borrow_mut() = ")
                .append("m.clone() });");
        return false;
    }

    @Override
    public boolean preorder(DBSPOperator operator) {
        DBSPType streamType = new DBSPTypeStream(operator.outputType);
        this.writeComments(operator)
                .append("let ")
                .append(operator.getName())
                .append(": ");
        streamType.accept(this.innerVisitor);
        this.builder.append(" = ");
        if (!operator.inputs.isEmpty())
            builder.append(operator.inputs.get(0).getName())
                    .append(".");
        builder.append(operator.operation)
                .append("(");
        for (int i = 1; i < operator.inputs.size(); i++) {
            if (i > 1)
                builder.append(",");
            builder.append("&")
                    .append(operator.inputs.get(i).getName());
        }
        if (operator.function != null) {
            if (operator.inputs.size() > 1)
                builder.append(", ");
            operator.function.accept(this.innerVisitor);
        }
        builder.append(");");
        return false;
    }

    @Override
    public boolean preorder(DBSPWindowAggregateOperator operator) {
        // We generate two DBSP operator calls: partitioned_rolling_aggregate
        // and map_index
        DBSPType streamType = new DBSPTypeStream(operator.outputType);
        String tmp = new NameGen("stream").toString();
        this.writeComments(operator)
                .append("let ")
                .append(tmp)
                .append(" = ")
                .append(operator.input().getName())
                .append(".partitioned_rolling_aggregate(");
        operator.getFunction().accept(this.innerVisitor);
        builder.append(", ");
        operator.window.accept(this.innerVisitor);
        builder.append(");")
                .newline();

        this.builder.append("let ")
                .append(operator.getName())
                .append(": ");
        streamType.accept(this.innerVisitor);
        builder.append(" = " )
                .append(tmp)
                .append(".map_index(|(key, (ts, agg))| { ((key.clone(), *ts), agg.unwrap_or_default())});");
        return false;
    }

    @Override
    public boolean preorder(DBSPIncrementalAggregateOperator operator) {
        DBSPType streamType = new DBSPTypeStream(operator.outputType);
        this.writeComments(operator)
                .append("let ")
                .append(operator.getName())
                .append(": ");
        streamType.accept(this.innerVisitor);
        this.builder.append(" = ");
        builder.append(operator.input().getName())
                    .append(".");
        builder.append(operator.operation)
                .append("(");
        operator.getFunction().accept(this.innerVisitor);
        builder.append(");");
        return false;
    }

    @Override
    public boolean preorder(DBSPSumOperator operator) {
        this.writeComments(operator)
                    .append("let ")
                    .append(operator.getName())
                    .append(": ");
        new DBSPTypeStream(operator.outputType).accept(this.innerVisitor);
        this.builder.append(" = ");
        if (!operator.inputs.isEmpty())
            this.builder.append(operator.inputs.get(0).getName())
                        .append(".");
        this.builder.append(operator.operation)
                    .append("([");
        for (int i = 1; i < operator.inputs.size(); i++) {
            if (i > 1)
                this.builder.append(", ");
            this.builder.append("&").append(operator.inputs.get(i).getName());
        }
        this.builder.append("]);");
        return false;
    }

    IIndentStream writeComments(@Nullable String comment) {
        if (comment == null)
            return this.builder;
        String[] parts = comment.split("\n");
        parts = Linq.map(parts, p -> "// " + p, String.class);
        return this.builder.intercalate("\n", parts);
    }

     IIndentStream writeComments(DBSPOperator operator) {
        return this.writeComments(operator.getClass().getSimpleName() + " " + operator.id +
                (operator.comment != null ? "\n" + operator.comment : ""));
    }

    @Override
    public boolean preorder(DBSPIncrementalJoinOperator operator) {
        this.writeComments(operator)
                .append("let ")
                .append(operator.getName())
                .append(": ");
        new DBSPTypeStream(operator.outputType).accept(this.innerVisitor);
        this.builder.append(" = ");
        if (!operator.inputs.isEmpty())
            this.builder.append(operator.inputs.get(0).getName())
                    .append(".");
        this.builder.append(operator.operation)
                .append("(&");
        this.builder.append(operator.inputs.get(1).getName());
        this.builder.append(", ");
        operator.getFunction().accept(this.innerVisitor);
        this.builder.append(");");
        return false;
    }

    @Override
    public boolean preorder(DBSPConstantOperator operator) {
        assert operator.function != null;
        builder.append("let ")
                .append(operator.getName())
                .append(" = ")
                .append("circuit.add_source(Generator::new(|| ");
        operator.function.accept(this.innerVisitor);
        this.builder.append("));");
        return false;
    }

    public static String toRustString(IDBSPOuterNode node) {
        StringBuilder builder = new StringBuilder();
        IndentStream stream = new IndentStream(builder);
        LowerCircuitVisitor lower = new LowerCircuitVisitor();
        node = lower.apply(node.to(DBSPCircuit.class));
        ToRustVisitor visitor = new ToRustVisitor(stream);
        node.accept(visitor);
        return builder.toString();
    }
}
