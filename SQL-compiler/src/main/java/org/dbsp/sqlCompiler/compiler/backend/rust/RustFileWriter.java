package org.dbsp.sqlCompiler.compiler.backend.rust;

import org.dbsp.sqlCompiler.circuit.DBSPCircuit;
import org.dbsp.sqlCompiler.circuit.IDBSPInnerNode;
import org.dbsp.sqlCompiler.circuit.IDBSPNode;
import org.dbsp.sqlCompiler.compiler.backend.optimize.BetaReduction;
import org.dbsp.sqlCompiler.compiler.backend.optimize.Simplify;
import org.dbsp.sqlCompiler.compiler.backend.visitors.CircuitFunctionRewriter;
import org.dbsp.sqlCompiler.ir.CircuitVisitor;
import org.dbsp.sqlCompiler.ir.DBSPFunction;
import org.dbsp.sqlCompiler.ir.InnerVisitor;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeSemigroup;
import org.dbsp.sqlCompiler.compiler.frontend.TypeCompiler;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeTuple;
import org.dbsp.util.IndentStream;
import org.dbsp.util.Linq;
import org.dbsp.util.Logger;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.stream.IntStream;

/**
 * This class helps generate Rust code.
 * It is given a set of circuit and functions and generates a compilable Rust file.
 */
public class RustFileWriter {
    final List<IDBSPNode> toWrite;
    final PrintStream outputStream;
    boolean emitHandles = false;

    static class StructuresUsed {
        final Set<Integer> tupleSizesUsed = new HashSet<>();
        final Set<Integer> semigroupSizesUsed = new HashSet<>();
    }
    StructuresUsed used = new StructuresUsed();

    /**
     * Visitor which discovers some data structures used.
     * Stores the result in the "used" structure.
     */
    class FindResources extends InnerVisitor {
        public FindResources() {
            super(true);
        }

        @Override
        public void postorder(DBSPTypeTuple type) {
            RustFileWriter.this.used.tupleSizesUsed.add(type.size());
        }

        @Override
        public void postorder(DBSPTypeSemigroup type) {
            RustFileWriter.this.used.semigroupSizesUsed.add(type.semigroupSize());
        }
    }

    /**
     * Find resources used.
     */
    FindResources finder = new FindResources();
    CircuitVisitor findInCircuit = this.finder.getCircuitVisitor();
    LowerCircuitVisitor lower = new LowerCircuitVisitor();
    BetaReduction reducer = new BetaReduction();
    CircuitFunctionRewriter circuitReducer = reducer.circuitRewriter();

    /**
     * If this is called with 'true' the emitted Rust code will use handles
     * instead of explicitly typed ZSets.
     */
    public void emitCodeWithHandle(boolean emit) {
        this.emitHandles = emit;
    }

    @SuppressWarnings("SpellCheckingInspection")
    static final String rustPreamble =
            "// Automatically-generated file\n" +
                    "#![allow(dead_code)]\n" +
                    "#![allow(non_snake_case)]\n" +
                    "#![allow(unused_imports)]\n" +
                    "#![allow(unused_parens)]\n" +
                    "#![allow(unused_variables)]\n" +
                    "#![allow(unused_mut)]\n" +
                    "\n" +
                    "use dbsp::{\n" +
                    "    algebra::{ZSet, MulByRef, F32, F64, Semigroup, SemigroupValue,\n" +
                    "    UnimplementedSemigroup, DefaultSemigroup},\n" +
                    "    circuit::{Circuit, Stream},\n" +
                    "    operator::{\n" +
                    "        Generator,\n" +
                    "        FilterMap,\n" +
                    "        Fold,\n" +
                    "        time_series::{RelRange, RelOffset, OrdPartitionedIndexedZSet},\n" +
                    "        MaxSemigroup,\n" +
                    "        MinSemigroup,\n" +
                    "    },\n" +
                    "    trace::ord::{OrdIndexedZSet, OrdZSet},\n" +
                    "    zset,\n" +
                    "    DBWeight,\n" +
                    "    DBData,\n" +
                    "    DBSPHandle,\n" +
                    "    Runtime,\n" +
                    "};\n" +
                    "use dbsp_adapters::Catalog;\n" +
                    "use genlib::*;\n" +
                    "use size_of::*;\n" +
                    "use ::serde::{Deserialize,Serialize};\n" +
                    "use compare::{Compare, Extract};\n" +
                    "use std::{\n" +
                    "    convert::identity,\n" +
                    "    fmt::{Debug, Formatter, Result as FmtResult},\n" +
                    "    cell::RefCell,\n" +
                    "    rc::Rc,\n" +
                    "    marker::PhantomData,\n" +
                    "    str::FromStr,\n" +
                    "};\n" +
                    "use rust_decimal::Decimal;\n" +
                    "use tuple::declare_tuples;\n" +
                    "use sqllib::{\n" +
                    "    casts::*,\n" +
                    "    geopoint::*,\n" +
                    "    timestamp::*,\n" +
                    "    interval::*,\n" +
                    "};\n" +
                    "use sqllib::*;\n" +
                    "use sqlvalue::*;\n" +
                    "use hashing::*;\n" +
                    "use readers::*;\n" +
                    "use sqlx::{AnyConnection, any::AnyRow, Row};\n";

    public RustFileWriter(PrintStream outputStream) {
        this.toWrite = new ArrayList<>();
        this.outputStream = outputStream;
    }

    public RustFileWriter(String outputFile) throws FileNotFoundException, UnsupportedEncodingException {
        this(new PrintStream(outputFile, "UTF-8"));
    }

    static void generateStructures(StructuresUsed used, IndentStream stream) {
        /*
        #[derive(Clone)]
        pub struct Semigroup2<T0, T1, TS0, TS1>(PhantomData<(T0, T1, TS0, TS1)>);

        impl<T0, T1, TS0, TS1> Semigroup<(T0, T1)> for Semigroup2<T0, T1, TS0, TS1>
        where
            TS0: Semigroup<T0>,
            TS1: Semigroup<T1>,
        {
            fn combine(left: &(T0, T1), right: &(T0, T1)) -> (T0, T1) {
                (
                    TS0::combine(&left.0, &right.0),
                    TS1::combine(&left.1, &right.1),
                )
            }
        }
         */
        for (int i: used.semigroupSizesUsed) {
            Integer[] indexes = new Integer[i];
            IntStream.range(0, i).forEach(ix -> indexes[ix] = ix);
            String[] ts = Linq.map(indexes, ix -> "T" + ix, String.class);
            String[] tts = Linq.map(indexes, ix -> "TS" + ix, String.class);

            stream.append("#[derive(Clone)]").newline()
                    .append("pub struct Semigroup")
                    .append(i)
                    .append("<")
                    .intercalate(", ", ts)
                    .join(", ", tts)
                    .append(">(PhantomData<(")
                    .intercalate(", ", ts)
                    .join(", ", tts)
                    .append(")>);")
                    .newline()
                    .newline();

            stream.append("impl<")
                    .intercalate(", ", ts)
                    .join(", ", tts)
                    .append("> Semigroup")
                    .append("<(")
                    .intercalate(", ", indexes, ix -> "T" + ix)
                    .append(")> for Semigroup")
                    .append(i)
                    .append("<")
                    .intercalate(", ", ts)
                    .join(", ", tts)
                    .append(">")
                    .newline()
                    .append("where").increase()
                    .join(",\n", indexes, ix -> "TS" + ix + ": Semigroup<T" + ix + ">")
                    .newline().decrease()
                    .append("{").increase()
                    .append("fn combine(left: &(")
                    .intercalate(", ", ts)
                    .append("), right:&(")
                    .intercalate(", ", ts)
                    .append(")) -> (")
                    .intercalate(", ", ts)
                    .append(") {").increase()
                    .append("(").increase()
                    .join("\n", indexes, ix -> "TS" + ix + "::combine(&left." + ix + ", &right." + ix + "),")
                    .newline().decrease()
                    .append(")").newline()
                    .decrease()
                    .append("}").newline()
                    .decrease()
                    .append("}").newline();
        }

        stream.append("declare_tuples! {").increase();
        for (int i: used.tupleSizesUsed) {
            if (i == 0)
                continue;
            stream.append("Tuple")
                    .append(i)
                    .append("<");
            for (int j = 0; j < i; j++) {
                if (j > 0)
                    stream.append(", ");
                stream.append("T")
                        .append(j);
            }
            stream.append(">,\n");
        }
        stream.decrease().append("}\n\n");
    }

    public static String generatePreamble(StructuresUsed used) {
        IndentStream stream = new IndentStream(new StringBuilder());
        stream.append(rustPreamble)
                .newline();
        stream.append("type ")
                .append(TypeCompiler.WEIGHT_TYPE_NAME)
                .append(" = ")
                .append(TypeCompiler.WEIGHT_TYPE_IMPLEMENTATION.toString())
                .append(";")
                .newline();
        generateStructures(used, stream);
        return stream.toString();
    }

    public void add(DBSPCircuit circuit) {
        this.toWrite.add(circuit);
    }

    public void add(DBSPFunction function) {
        this.toWrite.add(function);
    }

    public void write() throws FileNotFoundException, UnsupportedEncodingException {
        Simplify simplify = new Simplify();
        CircuitFunctionRewriter simplifier = simplify.circuitRewriter();
        // Lower the circuits
        List<IDBSPNode> lowered = new ArrayList<>();
        for (IDBSPNode node: this.toWrite) {
            IDBSPInnerNode inner = node.as(IDBSPInnerNode.class);
            if (inner != null) {
                inner = simplify.apply(inner);
                inner.accept(this.finder);
                lowered.add(inner);
            } else {
                DBSPCircuit outer = node.to(DBSPCircuit.class);
                outer = this.lower.apply(outer);
                outer = this.circuitReducer.apply(outer);
                outer = simplifier.apply(outer);
                outer.accept(this.findInCircuit);
                lowered.add(outer);
            }
        }
        // Emit code
        this.outputStream.println(generatePreamble(used));
        for (IDBSPNode node: lowered) {
            String str;
            IDBSPInnerNode inner = node.as(IDBSPInnerNode.class);
            if (inner != null) {
                str = ToRustInnerVisitor.toRustString(inner);
            } else {
                DBSPCircuit outer = node.to(DBSPCircuit.class);
                if (this.emitHandles)
                    str = ToRustHandleVisitor.toRustString(outer, outer.name);
                else
                    str = ToRustVisitor.toRustString(outer);
            }
            this.outputStream.println(str);
        }
    }

    public void writeAndClose() throws FileNotFoundException, UnsupportedEncodingException {
        Logger.INSTANCE.setDebugLevel(FindResources.class, 3);
        this.write();
        this.outputStream.close();
    }
}
