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
import org.apache.calcite.sql.fun.*;
import org.dbsp.sqlCompiler.ir.DBSPAggregate;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPI64Literal;
import org.dbsp.sqlCompiler.ir.type.*;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeInteger;
import org.dbsp.sqlCompiler.ir.expression.*;
import org.dbsp.util.*;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Compiles SQL aggregate functions.
 */
public class AggregateCompiler {
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
    private DBSPAggregate.Implementation foldingFunction;
    
    /**
     * Expression that stands for the whole input row in the input zset.
     */
    private final DBSPVariablePath v;
    private final boolean isDistinct;
    private final SqlAggFunction aggFunction;
    // null only for COUNT(*)
    @Nullable
    private final DBSPExpression aggArgument;
    private final NameGen generator;

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
        this.generator = new NameGen("a");
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

    public String genAccumulatorName() {
        return this.generator.nextName();
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
                accum.asParameter(), this.v.asParameter(), CalciteToDBSPCompiler.WEIGHT_VAR.asParameter());
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
                argument = new DBSPUnaryExpression(function, this.resultType.setMayBeNull(false), "indicator", agg);
            else
                argument = one;
        }

        DBSPVariablePath accum = this.resultType.var(this.genAccumulatorName());
        if (this.isDistinct) {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.resultType, accum, argument);
        } else {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.resultType,
                    accum, new DBSPBinaryExpression(function, DBSPTypeInteger.SIGNED_64,
                            "mul_weight",
                            argument,
                            CalciteToDBSPCompiler.WEIGHT_VAR.borrow()));
        }
        DBSPType semigroup = new DBSPTypeUser(null, "DefaultSemigroup", false, this.resultType);
        this.foldingFunction = new DBSPAggregate.Implementation(
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
        DBSPVariablePath accum = this.nullableResultType.var(this.genAccumulatorName());
        DBSPExpression increment = ExpressionCompiler.aggregateOperation(
                function, call, this.nullableResultType, accum, aggregatedValue);
        DBSPType semigroup = new DBSPTypeUser(null, semigroupName, false, accum.getNonVoidType());
        this.foldingFunction = new DBSPAggregate.Implementation(
                function, zero, this.makeRowClosure(increment, accum), zero, semigroup);
    }

    void processSum(SqlSumAggFunction function) {
        DBSPExpression zero = DBSPLiteral.none(this.nullableResultType);
        DBSPExpression increment;
        DBSPExpression aggregatedValue = this.getAggregatedValue();
        DBSPVariablePath accum = this.nullableResultType.var(this.genAccumulatorName());

        if (this.isDistinct) {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.nullableResultType, accum, aggregatedValue);
        } else {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.nullableResultType,
                    accum, new DBSPBinaryExpression(function, 
                            aggregatedValue.getNonVoidType(),
                            "mul_weight",
                            aggregatedValue,
                            CalciteToDBSPCompiler.WEIGHT_VAR.borrow()));
        }
        DBSPType semigroup = new DBSPTypeUser(null, "DefaultOptSemigroup", false, accum.getNonVoidType().setMayBeNull(false));
        this.foldingFunction = new DBSPAggregate.Implementation(
                function, zero, this.makeRowClosure(increment, accum), zero, semigroup);
    }

    void processSumZero(SqlSumEmptyIsZeroAggFunction function) {
        DBSPExpression zero = this.resultType.to(IsNumericType.class).getZero();
        DBSPExpression increment;
        DBSPExpression aggregatedValue = this.getAggregatedValue();
        DBSPVariablePath accum = this.resultType.var(this.genAccumulatorName());

        if (this.isDistinct) {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.resultType, accum, aggregatedValue);
        } else {
            increment = ExpressionCompiler.aggregateOperation(
                    function, "+", this.resultType,
                    accum, new DBSPBinaryExpression(
                            function,
                            aggregatedValue.getNonVoidType(),
                            "mul_weight",
                            aggregatedValue,
                            CalciteToDBSPCompiler.WEIGHT_VAR.borrow()));
        }
        String semigroupName = "DefaultSemigroup";
        if (accum.getNonVoidType().mayBeNull)
            semigroupName = "DefaultOptSemigroup";
        DBSPType semigroup = new DBSPTypeUser(null, semigroupName, false, accum.getNonVoidType().setMayBeNull(false));
        this.foldingFunction = new DBSPAggregate.Implementation(
                function, zero, this.makeRowClosure(increment, accum), zero, semigroup);
    }

    void processAvg(SqlAvgAggFunction function) {
        DBSPType aggregatedValueType = this.getAggregatedValueType();
        DBSPType i64 = DBSPTypeInteger.SIGNED_64.setMayBeNull(true);
        DBSPExpression zero = new DBSPRawTupleExpression(
                DBSPLiteral.none(i64), DBSPLiteral.none(i64));
        DBSPType pairType = zero.getNonVoidType();
        DBSPExpression count, sum;
        DBSPVariablePath accum = pairType.var(this.genAccumulatorName());
        final int sumIndex = 0;
        final int countIndex = 1;
        DBSPExpression countAccumulator = accum.field(countIndex);
        DBSPExpression sumAccumulator = accum.field(sumIndex);
        DBSPExpression aggregatedValue = this.getAggregatedValue().cast(i64);
        DBSPExpression plusOne = new DBSPI64Literal(1L);
        if (aggregatedValueType.mayBeNull)
            plusOne = new DBSPUnaryExpression(function, DBSPTypeInteger.SIGNED_64,
                    "indicator", aggregatedValue);
        if (this.isDistinct) {
            count = ExpressionCompiler.aggregateOperation(
                    function, "+", i64, countAccumulator, plusOne);
            sum = ExpressionCompiler.aggregateOperation(
                    function, "+", i64, sumAccumulator, aggregatedValue);
        } else {
            count = ExpressionCompiler.aggregateOperation(
                    function, "+", i64,
                    countAccumulator, new DBSPBinaryExpression(
                            function,
                            DBSPTypeInteger.SIGNED_64.setMayBeNull(plusOne.getNonVoidType().mayBeNull),
                            "mul_weight",
                            plusOne,
                            CalciteToDBSPCompiler.WEIGHT_VAR.borrow()));
            sum = ExpressionCompiler.aggregateOperation(
                    function, "+", i64,
                    sumAccumulator, new DBSPBinaryExpression(
                            function,
                            i64,
                            "mul_weight",
                            aggregatedValue,
                            CalciteToDBSPCompiler.WEIGHT_VAR.borrow()));
        }
        DBSPExpression increment = new DBSPRawTupleExpression(sum, count);

        DBSPVariablePath a = pairType.var(this.genAccumulatorName());
        DBSPExpression divide = ExpressionCompiler.makeBinaryExpression(
                function, this.resultType, "/",
                Linq.list(a.field(sumIndex), a.field(countIndex)));
        divide = divide.cast(this.nullableResultType);
        DBSPClosureExpression post = new DBSPClosureExpression(
                null, divide, a.asParameter());
        DBSPExpression postZero = DBSPLiteral.none(this.nullableResultType);
        DBSPType semigroup = new DBSPTypeUser(null,"PairSemigroup", false, i64, i64,
                new DBSPTypeUser(null, "DefaultOptSemigroup", false, DBSPTypeInteger.SIGNED_64),
                new DBSPTypeUser(null, "DefaultOptSemigroup", false, DBSPTypeInteger.SIGNED_64));
        this.foldingFunction = new DBSPAggregate.Implementation(
                function, zero, this.makeRowClosure(increment, accum), post, postZero, semigroup);
    }

    public DBSPAggregate.Implementation compile() {
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
