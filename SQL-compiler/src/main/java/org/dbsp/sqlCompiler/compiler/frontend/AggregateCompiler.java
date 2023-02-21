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

package org.dbsp.sqlCompiler.compiler.frontend;

import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.*;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPI64Literal;
import org.dbsp.sqlCompiler.ir.type.*;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeInteger;
import org.dbsp.sqlCompiler.ir.expression.*;
import org.dbsp.util.ICastable;
import org.dbsp.util.Linq;
import org.dbsp.util.Unimplemented;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Compiles SQL aggregate functions.
 */
public class AggregateCompiler {
    /**
     * An aggregate is compiled as functional fold operation,
     * described by a zero (initial value), an increment
     * function, and a postprocessing step that makes any necessary conversions.
     * For example, AVG has a zero of (0,0), an increment of (1, value),
     * and a postprocessing step of |a| a.1/a.0.
     * Notice that the DBSP `Fold` structure has a slightly different signature
     * for the increment.
     */
    public static class AggregateImplementation {
        public final SqlOperator operator;
        /**
         * Zero of the fold function.
         */
        public final DBSPExpression zero;
        /**
         * A closure with signature |accum, value, weight| -> accum
         */
        public final DBSPClosureExpression increment;
        /**
         * Function that may postprocess the accumulator to produce the final result.
         */
        @Nullable
        public final DBSPClosureExpression postprocess;
        /**
         * Result produced for an empty set (DBSP produces no result in this case).
         */
        public final DBSPExpression emptySetResult;
        /**
         * Name of the Type that implements the semigroup for this operation.
         */
        public final DBSPType semigroup;

        public AggregateImplementation(
                SqlOperator operator,
                DBSPExpression zero,
                DBSPClosureExpression increment,
                @Nullable
                DBSPClosureExpression postprocess,
                DBSPExpression emptySetResult,
                DBSPType semigroup) {
            this.operator = operator;
            this.zero = zero;
            this.increment = increment;
            this.postprocess = postprocess;
            this.emptySetResult = emptySetResult;
            this.semigroup = semigroup;
            this.validate();
        }

        public AggregateImplementation(
                SqlOperator operator,
                DBSPExpression zero,
                DBSPClosureExpression increment,
                DBSPExpression emptySetResult,
                DBSPType semigroup) {
            this(operator, zero, increment, null, emptySetResult, semigroup);
        }

        void validate() {
            if (true)
                return;
            // These validation rules actually don't apply for window-based aggregates.
            // TODO: check them for standard aggregates.
            if (this.postprocess != null) {
                if (!this.emptySetResult.getNonVoidType().sameType(this.postprocess.getResultType()))
                    throw new RuntimeException("Postprocess result type " + this.postprocess.getResultType() +
                            " different from empty set type " + this.emptySetResult.getNonVoidType());
            } else {
                if (!this.emptySetResult.getNonVoidType().sameType(this.increment.getResultType())) {
                    throw new RuntimeException("Increment result type " + this.increment.getResultType() +
                            " different from empty set type " + this.emptySetResult.getNonVoidType());
                }
            }
        }
    }

    /**
     * Aggregate that is being compiled.
     */
    public final Object call;
    /**
     * Type of result expected.
     */
    public final DBSPType resultType;
    /**
     * Almost all aggregates may return nullable results, even if Calcite pretends it's not true.
     */
    public final DBSPType nullableResultType;
    // Deposit compilation result here
    @Nullable
    private AggregateImplementation foldingFunction;
    
    /**
     * Expression that stands for the a whole input row in the input zset.
     */
    private final DBSPVariablePath v;
    private final boolean isDistinct;
    private final SqlAggFunction aggFunction;
    // null only for COUNT(*)
    @Nullable
    private final DBSPExpression aggArgument;

    public AggregateCompiler(
            AggregateCall call, DBSPType resultType,
            DBSPVariablePath v) {
        this.resultType = resultType;
        this.nullableResultType = resultType.setMayBeNull(true);
        this.foldingFunction = null;
        this.v = v;
        this.isDistinct = call.isDistinct();
        this.aggFunction = call.getAggregation();
        this.call = call;
        List<Integer> argList = call.getArgList();
        if (argList.size() == 0) {
            this.aggArgument = null;
        } else if (argList.size() == 1) {
            int fieldNumber = call.getArgList().get(0);
            this.aggArgument = this.v.field(fieldNumber);
        } else {
            throw new Unimplemented(call);
        }
    }

    <T> boolean process(SqlAggFunction function, Class<T> clazz, Consumer<T> method) {
        T value = ICastable.as(function, clazz);
        if (value != null) {
            method.accept(value);
            return true;
        }
        return false;
    }

    /**
     * Given the body of a closure, make a closure with arguments accum, row, weight
     */
    DBSPClosureExpression makeRowClosure(DBSPExpression body, DBSPVariablePath accum) {
        return body.closure(
                accum.asParameter(), this.v.asParameter(), CalciteToDBSPCompiler.weight.asParameter());
    }

    void processCount(SqlCountAggFunction function) {
        // This can never be null.
        DBSPExpression zero = this.resultType.to(IsNumericType.class).getZero();
        DBSPExpression increment;
        DBSPExpression argument;
        DBSPExpression one = this.resultType.to(DBSPTypeInteger.class).getOne();
        if (this.aggArgument == null) {
            // COUNT(*)
            argument = one;
        } else {
            DBSPExpression agg = this.getAggregatedValue();
            if (agg.getNonVoidType().mayBeNull)
                argument = new DBSPApplyExpression("indicator", this.resultType.setMayBeNull(false), agg);
            else
                argument = one;
        }

        DBSPVariablePath accum = this.resultType.var("a");
        if (this.isDistinct) {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.resultType, accum, argument);
        } else {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.resultType,
                    accum, new DBSPApplyMethodExpression("mul_by_ref",
                            DBSPTypeInteger.SIGNED_64,
                            argument,
                            CalciteToDBSPCompiler.weight.borrow()));
        }
        DBSPType semigroup = new DBSPTypeUser(null, "DefaultSemigroup", false, this.resultType);
        this.foldingFunction = new AggregateImplementation(
                function, zero, this.makeRowClosure(increment, accum), zero, semigroup);
    }

    private DBSPExpression getAggregatedValue() {
        return Objects.requireNonNull(this.aggArgument);
    }

    private DBSPType getAggregatedValueType() {
        return this.getAggregatedValue().getNonVoidType();
    }

    void processMinMax(SqlMinMaxAggFunction function) {
        DBSPExpression zero = DBSPLiteral.none(this.nullableResultType);
        String call;
        String semigroupName;
        switch (function.getKind()) {
            case MIN:
                call = "min";
                semigroupName = "MinSemigroup";
                break;
            case MAX:
                call = "max";
                semigroupName = "MaxSemigroup";
                break;
            default:
                throw new Unimplemented(this.call);
        }
        DBSPExpression aggregatedValue = this.getAggregatedValue();
        DBSPVariablePath accum = this.nullableResultType.var("a");
        DBSPExpression increment = ExpressionCompiler.aggregateOperation(
                function, call, this.nullableResultType, accum, aggregatedValue);
        DBSPType semigroup = new DBSPTypeUser(null, semigroupName, false, accum.getNonVoidType());
        this.foldingFunction = new AggregateImplementation(
                function, zero, this.makeRowClosure(increment, accum), zero, semigroup);
    }

    void processSum(SqlSumAggFunction function) {
        DBSPExpression zero = DBSPLiteral.none(this.nullableResultType);
        DBSPExpression increment;
        DBSPExpression aggregatedValue = this.getAggregatedValue();
        DBSPVariablePath accum = this.nullableResultType.var("a");

        if (this.isDistinct) {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.nullableResultType, accum, aggregatedValue);
        } else {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.nullableResultType,
                    accum, new DBSPApplyMethodExpression("mul_by_ref",
                            aggregatedValue.getNonVoidType(),
                            aggregatedValue,
                            CalciteToDBSPCompiler.weight.borrow()));
        }
        DBSPType semigroup = new DBSPTypeUser(null, "DefaultOptSemigroup", false, accum.getNonVoidType().setMayBeNull(false));
        this.foldingFunction = new AggregateImplementation(
                function, zero, this.makeRowClosure(increment, accum), zero, semigroup);
    }

    void processSumZero(SqlSumEmptyIsZeroAggFunction function) {
        DBSPExpression zero = this.resultType.to(IsNumericType.class).getZero();
        DBSPExpression increment;
        DBSPExpression aggregatedValue = this.getAggregatedValue();
        DBSPVariablePath accum = this.resultType.var("a");

        if (this.isDistinct) {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.resultType, accum, aggregatedValue);
        } else {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.resultType,
                    accum, new DBSPApplyMethodExpression("mul_by_ref",
                            aggregatedValue.getNonVoidType(),
                            aggregatedValue,
                            CalciteToDBSPCompiler.weight.borrow()));
        }
        String semigroupName = "DefaultSemigroup";
        if (accum.getNonVoidType().mayBeNull)
            semigroupName = "DefaultOptSemigroup";
        DBSPType semigroup = new DBSPTypeUser(null, semigroupName, false, accum.getNonVoidType().setMayBeNull(false));
        this.foldingFunction = new AggregateImplementation(
                function, zero, this.makeRowClosure(increment, accum), zero, semigroup);
    }

    void processAvg(SqlAvgAggFunction function) {
        DBSPType aggregatedValueType = this.getAggregatedValueType();
        DBSPType i64 = DBSPTypeInteger.SIGNED_64.setMayBeNull(true);
        DBSPExpression zero = new DBSPRawTupleExpression(
                DBSPLiteral.none(i64), DBSPLiteral.none(i64));
        DBSPType pairType = zero.getNonVoidType();
        DBSPExpression count, sum;
        DBSPVariablePath accum = pairType.var("a");
        final int sumIndex = 0;
        final int countIndex = 1;
        DBSPExpression countAccumulator = accum.field(countIndex);
        DBSPExpression sumAccumulator = accum.field(sumIndex);
        DBSPExpression aggregatedValue = ExpressionCompiler.makeCast(function, this.getAggregatedValue(), i64);
        DBSPExpression plusOne = new DBSPI64Literal(1L);
        if (aggregatedValueType.mayBeNull)
            plusOne = new DBSPApplyExpression("indicator", DBSPTypeInteger.SIGNED_64, aggregatedValue);
        if (this.isDistinct) {
            count = ExpressionCompiler.aggregateOperation(
                    function, "+", i64, countAccumulator, plusOne);
            sum = ExpressionCompiler.aggregateOperation(
                    function, "+", i64, sumAccumulator, aggregatedValue);
        } else {
            count = ExpressionCompiler.aggregateOperation(
                    function, "+", i64,
                    countAccumulator, new DBSPApplyMethodExpression("mul_by_ref",
                            DBSPTypeInteger.SIGNED_64.setMayBeNull(plusOne.getNonVoidType().mayBeNull),
                            plusOne,
                            CalciteToDBSPCompiler.weight.borrow()));
            sum = ExpressionCompiler.aggregateOperation(
                    function, "+", i64,
                    sumAccumulator, new DBSPApplyMethodExpression("mul_by_ref",
                            i64,
                            aggregatedValue,
                            CalciteToDBSPCompiler.weight.borrow()));
        }

        DBSPExpression increment = new DBSPRawTupleExpression(sum, count);
        DBSPVariablePath a = pairType.var("a");
        DBSPExpression divide = ExpressionCompiler.makeBinaryExpression(
                function, this.resultType, "/",
                Linq.list(a.field(sumIndex), a.field(countIndex)));
        divide = ExpressionCompiler.makeCast(function, divide, this.nullableResultType);
        DBSPClosureExpression post = new DBSPClosureExpression(
                null, divide, a.asParameter());
        DBSPExpression postZero = DBSPLiteral.none(this.nullableResultType);
        DBSPType semigroup = new DBSPTypeUser(null,"PairSemigroup", false, i64, i64,
                new DBSPTypeUser(null, "DefaultOptSemigroup", false, DBSPTypeInteger.SIGNED_64),
                new DBSPTypeUser(null, "DefaultOptSemigroup", false, DBSPTypeInteger.SIGNED_64));
        this.foldingFunction = new AggregateImplementation(
                function, zero, this.makeRowClosure(increment, accum), post, postZero, semigroup);
    }

    public AggregateImplementation compile() {
        boolean success =
                this.process(this.aggFunction, SqlCountAggFunction.class, this::processCount) ||
                this.process(this.aggFunction, SqlMinMaxAggFunction.class, this::processMinMax) ||
                this.process(this.aggFunction, SqlSumAggFunction.class, this::processSum) ||
                this.process(this.aggFunction, SqlSumEmptyIsZeroAggFunction.class, this::processSumZero) ||
                this.process(this.aggFunction, SqlAvgAggFunction.class, this::processAvg);
        if (!success || this.foldingFunction == null)
            throw new Unimplemented(this.call);
        return this.foldingFunction;
    }
}
