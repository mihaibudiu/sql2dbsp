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

import org.dbsp.sqlCompiler.circuit.IDBSPDeclaration;
import org.dbsp.sqlCompiler.ir.DBSPFunction;
import org.dbsp.sqlCompiler.ir.DBSPParameter;
import org.dbsp.sqlCompiler.ir.expression.*;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPLiteral;
import org.dbsp.sqlCompiler.ir.pattern.*;
import org.dbsp.sqlCompiler.ir.type.*;
import org.dbsp.sqlCompiler.ir.type.primitive.*;
import org.dbsp.util.Unimplemented;
import org.dbsp.util.Utilities;

import javax.annotation.Nullable;
import java.io.*;
import java.util.*;

/**
 * This class generates Rust sources for the SQL
 * runtime library: support functions that implement the
 * SQL semantics.
 */
@SuppressWarnings({"FieldCanBeLocal", "MismatchedQueryAndUpdateOfCollection", "SpellCheckingInspection"})
public class RustSqlRuntimeLibrary {
    private final HashSet<String> aggregateFunctions = new HashSet<>();
    private final HashMap<String, String> arithmeticFunctions = new HashMap<>();
    private final HashMap<String, String> dateFunctions = new HashMap<>();
    private final HashMap<String, String> doubleFunctions = new HashMap<>();
    private final HashMap<String, String> stringFunctions = new HashMap<>();
    private final HashMap<String, String> booleanFunctions = new HashMap<>();
    private final Set<String> comparisons = new HashSet<>();
    private final Set<String> handWritten = new HashSet<>();

    public static final RustSqlRuntimeLibrary INSTANCE =new RustSqlRuntimeLibrary();
    final LinkedHashMap<String, IDBSPDeclaration> declarations = new LinkedHashMap<>();

    protected RustSqlRuntimeLibrary() {
        this.aggregateFunctions.add("count");
        this.aggregateFunctions.add("sum");
        this.aggregateFunctions.add("avg");
        this.aggregateFunctions.add("min");
        this.aggregateFunctions.add("max");
        this.aggregateFunctions.add("some");
        this.aggregateFunctions.add("any");
        this.aggregateFunctions.add("every");
        this.aggregateFunctions.add("array_agg");
        this.aggregateFunctions.add("set_agg");

        this.arithmeticFunctions.put("eq", "==");
        this.arithmeticFunctions.put("neq", "!=");
        this.arithmeticFunctions.put("lt", "<");
        this.arithmeticFunctions.put("gt", ">");
        this.arithmeticFunctions.put("lte", "<=");
        this.arithmeticFunctions.put("gte", ">=");
        this.arithmeticFunctions.put("plus", "+");
        this.arithmeticFunctions.put("minus", "-");
        this.arithmeticFunctions.put("mod", "%");
        this.arithmeticFunctions.put("times", "*");
        this.arithmeticFunctions.put("div", "/");
        this.arithmeticFunctions.put("shiftr", ">>");
        this.arithmeticFunctions.put("shiftl", "<<");
        this.arithmeticFunctions.put("band", "&");
        this.arithmeticFunctions.put("bor", "|");
        this.arithmeticFunctions.put("bxor", "^");
        this.arithmeticFunctions.put("min", "min");
        this.arithmeticFunctions.put("max", "max");
        this.arithmeticFunctions.put("is_distinct", "is_distinct");
        this.arithmeticFunctions.put("agg_plus", "agg_plus");
        this.arithmeticFunctions.put("agg_min", "agg_min");
        this.arithmeticFunctions.put("agg_max", "agg_max");
        this.arithmeticFunctions.put("mul_by_ref", "mul_weight");

        this.handWritten.add("is_false");
        this.handWritten.add("is_not_true");
        this.handWritten.add("is_not_false");
        this.handWritten.add("is_true");
        this.handWritten.add("&&");
        this.handWritten.add("||");
        this.handWritten.add("min");
        this.handWritten.add("max");
        this.handWritten.add("/");
        this.handWritten.add("is_distinct");
        this.handWritten.add("is_not_distinct");
        this.handWritten.add("agg_max");
        this.handWritten.add("agg_plus");
        this.handWritten.add("agg_min");
        this.handWritten.add("mul_weight");

        this.doubleFunctions.put("eq", "==");
        this.doubleFunctions.put("neq", "!=");
        this.doubleFunctions.put("lt", "<");
        this.doubleFunctions.put("gt", ">");
        this.doubleFunctions.put("lte", "<=");
        this.doubleFunctions.put("gte", ">=");
        this.doubleFunctions.put("plus", "+");
        this.doubleFunctions.put("minus", "-");
        this.doubleFunctions.put("mod", "%");
        this.doubleFunctions.put("times", "*");
        this.doubleFunctions.put("div", "/");

        this.dateFunctions.put("plus", "+");
        this.dateFunctions.put("minus", "-");
        this.dateFunctions.put("times", "*");
        this.dateFunctions.put("eq", "==");
        this.dateFunctions.put("neq", "!=");
        this.dateFunctions.put("lt", "<");
        this.dateFunctions.put("gt", ">");
        this.dateFunctions.put("lte", "<=");
        this.dateFunctions.put("gte", ">=");

        this.stringFunctions.put("concat", "||");
        this.stringFunctions.put("eq", "==");
        this.stringFunctions.put("neq", "!=");

        this.booleanFunctions.put("eq", "==");
        this.booleanFunctions.put("neq", "!=");
        this.booleanFunctions.put("and", "&&");
        this.booleanFunctions.put("or", "||");
        this.booleanFunctions.put("min", "min");
        this.booleanFunctions.put("max", "max");
        this.booleanFunctions.put("is_false", "is_false");
        this.booleanFunctions.put("is_not_true", "is_not_true");
        this.booleanFunctions.put("is_true", "is_true");
        this.booleanFunctions.put("is_not_false", "is_not_false");
        this.booleanFunctions.put("agg_min", "agg_min");
        this.booleanFunctions.put("agg_max", "agg_max");

        this.comparisons.add("==");
        this.comparisons.add("!=");
        this.comparisons.add(">=");
        this.comparisons.add("<=");
        this.comparisons.add(">");
        this.comparisons.add("<");
        this.comparisons.add("is_distinct");
    }

    boolean isComparison(String op) {
        return this.comparisons.contains(op);
    }

    public static class FunctionDescription {
        public final String function;
        public final DBSPType returnType;

        public FunctionDescription(String function, DBSPType returnType) {
            this.function = function;
            this.returnType = returnType;
        }

        public DBSPApplyExpression getCall(DBSPExpression... arguments) {
            return new DBSPApplyExpression(this.function, this.returnType, arguments);
        }

        @Override
        public String toString() {
            return "FunctionDescription{" +
                    "function='" + function + '\'' +
                    ", returnType=" + returnType +
                    '}';
        }
    }

    public FunctionDescription getImplementation(
            String op, @Nullable DBSPType expectedReturnType, DBSPType ltype, @Nullable DBSPType rtype) {
        boolean isAggregate = op.startsWith("agg_");
        if (ltype.is(DBSPTypeAny.class) || (rtype != null && rtype.is(DBSPTypeAny.class)))
            throw new RuntimeException("Unexpected type _ for operand of " + op);
        HashMap<String, String> map = null;
        boolean anyNull = ltype.mayBeNull || (rtype != null && rtype.mayBeNull);
        String suffixReturn = "";  // suffix based on the return type

        DBSPType returnType = ltype.setMayBeNull(anyNull);
        if (ltype.as(DBSPTypeBool.class) != null) {
            map = this.booleanFunctions;
        } else if (ltype.is(IsDateType.class)) {
            map = this.dateFunctions;
            if (op.equals("-")) {
                if (ltype.is(DBSPTypeTimestamp.class) || ltype.is(DBSPTypeDate.class)) {
                    assert expectedReturnType != null;
                    returnType = expectedReturnType;
                    suffixReturn = "_" + returnType.baseTypeWithSuffix();
                }
            }
        } else if (ltype.is(IsNumericType.class)) {
            map = this.arithmeticFunctions;
        } else if (ltype.is(DBSPTypeString.class)) {
            map = this.stringFunctions;
        }
        if (isComparison(op))
            returnType = DBSPTypeBool.INSTANCE.setMayBeNull(anyNull);
        if (op.equals("/"))
            // Always, for division by 0
            returnType = returnType.setMayBeNull(true);
        if (op.equals("is_true") || op.equals("is_not_true") ||
                op.equals("is_false") || op.equals("is_not_false") ||
                op.equals("is_distinct"))
            returnType = DBSPTypeBool.INSTANCE;
        String suffixl = ltype.nullableSuffix();
        String suffixr = rtype == null ? "" : rtype.nullableSuffix();
        String tsuffixl;
        String tsuffixr;
        if (isAggregate || op.equals("is_distinct")) {
            tsuffixl = "";
            tsuffixr = "";
        } else {
            tsuffixl = ltype.to(DBSPTypeBaseType.class).shortName();
            tsuffixr = (rtype == null) ? "" : rtype.to(DBSPTypeBaseType.class).shortName();
        }
        if (map == null)
            throw new Unimplemented(op);
        for (String k: map.keySet()) {
            if (map.get(k).equals(op)) {
                return new FunctionDescription(
                        k + "_" + tsuffixl + suffixl + "_" + tsuffixr + suffixr + suffixReturn,
                        returnType);
            }
        }
        throw new Unimplemented("Could not find `" + op + "` for type " + ltype);
    }

    void generateProgram() {
        this.declarations.clear();
        DBSPType[] numericTypes = new DBSPType[] {
                DBSPTypeInteger.SIGNED_16,
                DBSPTypeInteger.SIGNED_32,
                DBSPTypeInteger.SIGNED_64,
        };
        DBSPType[] boolTypes = new DBSPType[] {
                DBSPTypeBool.INSTANCE
        };
        DBSPType[] stringTypes = new DBSPType[] {
                DBSPTypeString.INSTANCE
        };
        DBSPType[] fpTypes = new DBSPType[] {
                DBSPTypeDouble.INSTANCE,
                DBSPTypeFloat.INSTANCE
        };

        for (HashMap<String, String> h: Arrays.asList(
                this.arithmeticFunctions, this.booleanFunctions, this.stringFunctions, this.doubleFunctions)) {
            for (String f : h.keySet()) {
                String op = h.get(f);
                if (this.handWritten.contains(op))
                    // Hand-written rules in a separate library
                    continue;
                for (int i = 0; i < 4; i++) {
                    DBSPType leftType;
                    DBSPType rightType;
                    DBSPType[] raw;
                    DBSPType withNull;
                    if (h.equals(this.stringFunctions)) {
                        raw = stringTypes;
                    } else if (h == this.booleanFunctions) {
                        raw = boolTypes;
                    } else if (h == this.doubleFunctions) {
                        raw = fpTypes;
                    } else {
                        raw = numericTypes;
                    }
                    for (DBSPType rawType: raw) {
                        if (op.equals("%") && rawType.is(DBSPTypeFP.class))
                            continue;
                        withNull = rawType.setMayBeNull(true);
                        DBSPPattern leftMatch = new DBSPIdentifierPattern("l");
                        DBSPPattern rightMatch = new DBSPIdentifierPattern("r");
                        if ((i & 1) == 1) {
                            leftType = withNull;
                            leftMatch = DBSPTupleStructPattern.somePattern(leftMatch);
                        } else {
                            leftType = rawType;
                        }
                        if ((i & 2) == 2) {
                            rightType = withNull;
                            rightMatch = DBSPTupleStructPattern.somePattern(rightMatch);
                        } else {
                            rightType = rawType;
                        }
                        /*
                        fn add_i32N_i32N(left: Option<i32>, right: Option<i32>): Option<i32> =
                        match ((left, right)) {
                            (Some{a}, Some{b}) -> Some{a + b},
                            (_, _)             -> None
                        }
                        */

                        // The general rule is: if any operand is NULL, the result is NULL.
                        FunctionDescription function = this.getImplementation(op, null, leftType, rightType);
                        DBSPParameter left = new DBSPParameter("left", leftType);
                        DBSPParameter right = new DBSPParameter("right", rightType);
                        DBSPType type = function.returnType;
                        DBSPExpression def;
                        if (i == 0) {
                            DBSPExpression leftVar = rawType.var("left");
                            DBSPExpression rightVar = rawType.var("right");
                            def = new DBSPBinaryExpression(type, op, leftVar, rightVar, true);
                        } else {
                            def = new DBSPBinaryExpression(type, op,
                                    rawType.var("l"),
                                    rawType.var("r"), true);
                            def = new DBSPMatchExpression(
                                    new DBSPRawTupleExpression(
                                            leftType.var("left"),
                                            rightType.var("right")),
                                    Arrays.asList(
                                            new DBSPMatchExpression.Case(
                                                    new DBSPTuplePattern(leftMatch, rightMatch),
                                                    def.some()),
                                            new DBSPMatchExpression.Case(
                                                    new DBSPTuplePattern(
                                                            DBSPWildcardPattern.INSTANCE,
                                                            DBSPWildcardPattern.INSTANCE),
                                                    DBSPLiteral.none(type))),
                                    type);
                        }
                        DBSPFunction func = new DBSPFunction(function.function, Arrays.asList(left, right), type, def);
                        func.addAnnotation("#[inline(always)]");
                        Utilities.putNew(this.declarations, func.name, func);
                    }
                }
            }
        }
    }

    /**
     * Writes in the specified file the Rust code for the SQL runtime.
     * @param filename   File to write the code to.
     */
    public void writeSqlLibrary(String filename) throws IOException {
        this.generateProgram();
        File file = new File(filename);
        FileWriter writer = new FileWriter(file, false);
        writer.append("// Automatically-generated file\n");
        writer.append("#![allow(unused_parens)]\n");
        writer.append("#![allow(non_snake_case)]\n");
        writer.append("use dbsp::algebra::{F32, F64};\n");
        writer.append("\n");
        for (IDBSPDeclaration declaration: this.declarations.values()) {
            writer.append(ToRustInnerVisitor.toRustString(declaration));
            writer.append("\n\n");
        }
        writer.close();
    }
}
