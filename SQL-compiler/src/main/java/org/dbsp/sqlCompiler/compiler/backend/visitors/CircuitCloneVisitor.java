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

package org.dbsp.sqlCompiler.compiler.backend.visitors;

import org.dbsp.sqlCompiler.circuit.DBSPCircuit;
import org.dbsp.sqlCompiler.circuit.DBSPPartialCircuit;
import org.dbsp.sqlCompiler.circuit.IDBSPDeclaration;
import org.dbsp.sqlCompiler.circuit.IDBSPNode;
import org.dbsp.sqlCompiler.circuit.operator.*;
import org.dbsp.sqlCompiler.ir.CircuitVisitor;
import org.dbsp.util.IModule;
import org.dbsp.util.Linq;
import org.dbsp.util.Logger;
import org.dbsp.util.Utilities;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;

/**
 * This visitor clones a circuit into an equivalent one.
 * Each operator is cloned in one of two cases:
 * - any of its inputs has changed
 * - the 'force' flag is 'true'.
 * The declarations in the circuit are left unchanged.
 */
public class CircuitCloneVisitor extends CircuitVisitor
        implements Function<DBSPCircuit, DBSPCircuit>, IModule {
    @Nullable
    protected DBSPPartialCircuit result;
    /**
     * For each operator in the original circuit an operator in the
     * result circuit which computes the same result.
     */
    protected final Map<DBSPOperator, DBSPOperator> remap;
    protected final boolean force;
    protected final Set<DBSPOperator> visited = new HashSet<>();

    public CircuitCloneVisitor(boolean force) {
        super(true);
        this.remap = new HashMap<>();
        this.force = force;
    }

    public DBSPOperator mapped(DBSPOperator original) {
        return Utilities.getExists(this.remap, original);
    }

    protected void map(DBSPOperator old, DBSPOperator newOp, boolean add) {
        Logger.INSTANCE.from(this, 1)
                .append(this.toString())
                .append(":")
                .append(old.toString())
                .append(" -> ")
                .append(newOp.toString())
                .newline();
        Utilities.putNew(this.remap, old, newOp);
        if (add)
            this.addOperator(newOp);
    }

    protected void map(DBSPOperator old, DBSPOperator newOp) {
        this.map(old, newOp, true);
    }

    protected void addOperator(DBSPOperator operator) {
        Logger.INSTANCE.from(this, 1)
                .append(this.toString())
                .append(" adding ")
                .append(operator.toString())
                .newline();
        this.getResult().addOperator(operator);
    }

    @Override
    public boolean preorder(DBSPPartialCircuit circuit) {
        super.preorder(circuit);
        for (IDBSPNode node : circuit.getCode()) {
            DBSPOperator op = node.as(DBSPOperator.class);
            if (op != null)
                op.accept(this);
            else {
                this.getResult().declare(node.to(IDBSPDeclaration.class));
            }
        }
        return false;
    }

    public void replace(DBSPOperator operator) {
        if (this.visited.contains(operator))
            // Graph can be a DAG
            return;
        this.visited.add(operator);
        List<DBSPOperator> sources = Linq.map(operator.inputs, this::mapped);
        Logger.INSTANCE.from(this, 1)
                .append(this.toString())
                .append(" replacing inputs of ")
                .increase()
                .append(operator.toString())
                .append(":")
                .join(", ", Linq.map(operator.inputs, DBSPOperator::toString))
                .newline()
                .append("with:")
                .join(", ", Linq.map(sources, DBSPOperator::toString))
                .newline()
                .decrease();
        DBSPOperator result = operator.withInputs(sources, this.force);
        this.map(operator, result);
    }

    @Override
    public void postorder(DBSPWindowAggregateOperator operator) { this.replace(operator); }

    @Override
    public void postorder(DBSPNoopOperator operator) { this.replace(operator); }

    @Override
    public void postorder(DBSPMapIndexOperator operator) { this.replace(operator); }

    @Override
    public void postorder(DBSPUnaryOperator operator) { this.replace(operator); }

    @Override
    public void postorder(DBSPAggregateOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPConstantOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPDifferentialOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPDistinctOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPFilterOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPFlatMapOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPIndexOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPIntegralOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPMapOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPNegateOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPSinkOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPSourceOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPSubtractOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPSumOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPJoinOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPIncrementalJoinOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPIncrementalDistinctOperator operator) {
        this.replace(operator);
    }

    @Override
    public void postorder(DBSPIncrementalAggregateOperator operator) {
        this.replace(operator);
    }

    public DBSPPartialCircuit getResult() {
        return Objects.requireNonNull(this.result);
    }

    @Override
    public DBSPCircuit apply(DBSPCircuit circuit) {
        this.startVisit(circuit);
        this.result = new DBSPPartialCircuit(circuit.circuit.compiler);
        circuit.accept(this);
        this.endVisit();
        DBSPPartialCircuit result = this.getResult();
        if (circuit.circuit.sameCircuit(result))
            return circuit;
        return result.seal(circuit.name);
    }
}
