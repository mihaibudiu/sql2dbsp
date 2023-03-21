package org.dbsp.sqlCompiler.compiler.backend.jit;

import com.fasterxml.jackson.databind.node.*;
import org.dbsp.sqlCompiler.ir.DBSPParameter;
import org.dbsp.sqlCompiler.ir.InnerVisitor;
import org.dbsp.sqlCompiler.ir.expression.*;
import org.dbsp.sqlCompiler.ir.expression.literal.*;
import org.dbsp.sqlCompiler.ir.pattern.DBSPIdentifierPattern;
import org.dbsp.sqlCompiler.ir.statement.DBSPLetStatement;
import org.dbsp.sqlCompiler.ir.statement.DBSPStatement;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeTuple;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeBool;
import org.dbsp.util.Unimplemented;
import org.dbsp.util.Utilities;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Generate code for the JIT compiler using a JSON representation.
 * Handles InnerNodes - i.e., expressions, closures, statements.
 */
public class ToJitInnerVisitor extends InnerVisitor {
    public static boolean isLegalId(int id) {
        return id >= 0;
    }

    /**
     * In the JIT representation tuples can have nullable fields,
     * but scalar variables cannot.  So we represent each scalar
     * variable that has a nullable type as a pair of expressions,
     * one carrying the value, and a second one, Boolean, carrying the nullness.
     */
    static class ExpressionIds {
        /**
         * Expression id.
         */
        public final int id;
        /**
         * Id of the expression carrying the nullability - if any.
         * Otherwise -1.
         */
        public final int isNullId;

        private ExpressionIds(int id, int isNullId) {
            this.id = id;
            this.isNullId = isNullId;
        }

        static ExpressionIds withNull(int id) {
            return new ExpressionIds(id, id + 1);
        }

        static ExpressionIds noNull(int id) {
            return new ExpressionIds(id, -1);
        }

        boolean hasNull() {
            return isLegalId(this.isNullId);
        }

        @Override
        public String toString() {
            return this.id + "," + this.isNullId;
        }
    }

    static class ExpressionJsonRepresentation {
        /**
         * The json instruction that computes the value of the expression.
         */
        public final ObjectNode instruction;
        /**
         * This field is also stored inside the instruction JSON node.
         */
        int instructionId;
        /**
         * The json instruction that computes the value of the null field - if needed.
         * null otherwise.
         */
        public final @Nullable ObjectNode isNullInstruction;

        public ExpressionJsonRepresentation(ObjectNode instruction, int id,
                                            @Nullable ObjectNode isNullInstruction) {
            this.instruction = instruction;
            this.instructionId = id;
            this.isNullInstruction = isNullInstruction;
        }

        public ObjectNode getNullObject() {
            return Objects.requireNonNull(this.isNullInstruction);
        }
    }

    /**
     * Contexts keep track of variables defined.
     * Variable names can be redefined, as in Rust, and a context
     * will always return the one in the current scope.
     */
    class Context {
        /**
         * Maps variable names to expression ids.
         */
        final Map<String, ExpressionIds> variables;

        public Context() {
            this.variables = new HashMap<>();
        }

        @Nullable
        ExpressionIds lookup(String varName) {
            if (this.variables.containsKey(varName))
                return this.variables.get(varName);
            return null;
        }

        /**
         * Add a new variable to the current context.
         *
         * @param varName   Variable name.
         * @param needsNull True if the variable is a scalar nullable variable.
         *                  Then we allocate an extra expression to hold its
         *                  nullability.
         */
        ExpressionIds addVariable(String varName, boolean needsNull) {
            if (this.variables.containsKey(varName)) {
                throw new RuntimeException("Duplicate declaration " + varName);
            }
            int id = ToJitInnerVisitor.this.nextExpressionId();
            ExpressionIds ids;
            if (needsNull) {
                ToJitInnerVisitor.this.nextExpressionId();
                ids = ExpressionIds.withNull(id);
            } else {
                ids = ExpressionIds.noNull(id);
            }
            this.variables.put(varName, ids);
            return ids;
        }
    }

    /**
     * Prefix of the name of the fictitious parameters that are added to closures that return structs.
     * The JIT only supports returning scalar values, so a closure that returns a
     * tuple actually receives an additional parameter that is "output" (equivalent to a "mut").
     * Moreover, a closure that returns a RawTuple will return one value for each field of the raw tuple.
     */
    private static final String RETURN_PARAMETER_PREFIX = "$retval";
    /**
     * Used to allocate ids for expressions and blocks.
     */
    private int nextExpressionId = 1;
    private int nextBlockId = 1;
    final ObjectNode blocks;
    /**
     * Map expression to id.
     */
    final Map<DBSPExpression, Integer> expressionId;
    /**
     * A context for each block expression.
     */
    public final List<Context> declarations;
    /**
     * JSON object representing the body of the current block expression.
     */
    @Nullable
    public ArrayNode currentBlockBody;
    /**
     * JSON object representing the current block.
     */
    @Nullable
    public ObjectNode currentBlock;
    /**
     * The type catalog shared with the ToJitVisitor.
     */
    public final ToJitVisitor.TypeCatalog catalog;
    /**
     * The names of the variables currently being assigned.
     * This is a stack, because we can have nested blocks:
     * let var1 = { let v2 = 1 + 2; v2 + 1 }
     */
    final List<String> variableAssigned;

    public ToJitInnerVisitor(ObjectNode blocks, ToJitVisitor.TypeCatalog catalog) {
        super(true);
        this.blocks = blocks;
        this.catalog = catalog;
        this.expressionId = new HashMap<>();
        this.declarations = new ArrayList<>();
        this.currentBlock = null;
        this.currentBlockBody = null;
        this.variableAssigned = new ArrayList<>();
    }

    int nextBlockId() {
        int result = this.nextBlockId;
        this.nextBlockId++;
        return result;
    }

    int nextExpressionId() {
        int result = this.nextExpressionId;
        this.nextExpressionId++;
        return result;
    }

    ExpressionIds resolve(String varName) {
        // Look in the contexts in backwards order
        for (int i = 0; i < this.declarations.size(); i++) {
            int index = this.declarations.size() - 1 - i;
            Context current = this.declarations.get(index);
            ExpressionIds ids = current.lookup(varName);
            if (ids != null)
                return ids;
        }
        throw new RuntimeException("Could not resolve " + varName);
    }

    int map(DBSPExpression expression) {
        return Utilities.putNew(this.expressionId, expression, this.nextExpressionId());
    }

    int getExpressionId(DBSPExpression expression) {
        return Utilities.getExists(this.expressionId, expression);
    }

    ExpressionIds accept(DBSPExpression expression) {
        expression.accept(this);
        int id = this.getExpressionId(expression);
        if (needsNull(expression.getNonVoidType()))
            return ExpressionIds.withNull(id);
        return ExpressionIds.noNull(id);
    }

    public ExpressionIds constantBool(boolean value) {
        return this.accept(new DBSPBoolLiteral(value));
    }

    ExpressionJsonRepresentation insertInstruction(int id, boolean needsNull) {
        ArrayNode instruction = Objects.requireNonNull(this.currentBlockBody).addArray();
        instruction.add(id);
        ObjectNode isNull = null;
        if (needsNull) {
            ArrayNode isNullInstr = this.currentBlockBody.addArray();
            id = this.nextExpressionId();
            isNullInstr.add(id);
            isNull = isNullInstr.addObject();
        }
        return new ExpressionJsonRepresentation(instruction.addObject(), id, isNull);
    }

    ExpressionJsonRepresentation insertNewInstruction() {
        return this.insertInstruction(this.nextExpressionId(), false);
    }

    ExpressionJsonRepresentation insertInstruction(DBSPExpression expression) {
        int id = this.map(expression);
        return this.insertInstruction(id, needsNull(expression.getNonVoidType()));
    }

    static String baseTypeName(DBSPExpression expression) {
        return ToJitVisitor.baseTypeName(expression.getNonVoidType());
    }

    void newContext() {
        this.declarations.add(new Context());
    }

    Context getCurrentContext() {
        return this.declarations.get(this.declarations.size() - 1);
    }

    void popContext() {
        Utilities.removeLast(this.declarations);
    }

    ExpressionIds declare(String var, boolean needsNull) {
        return this.getCurrentContext().addVariable(var, needsNull);
    }

    /**
     * True if this type needs to store an "is_null" value in a separate place.
     * Tuples don't.  Only scalar nullable types may.
     */
    static boolean needsNull(DBSPType type) {
        if (type.is(DBSPTypeTuple.class))
            return false;
        return type.mayBeNull;
    }

    /////////////////////////// Code generation

    @Override
    public boolean preorder(DBSPExpression expression) {
        throw new Unimplemented(expression);
    }

    boolean createJsonLiteral(DBSPLiteral expression, String type, @Nullable BaseJsonNode value) {
        ExpressionJsonRepresentation ev = this.insertInstruction(expression);
        if (expression.isNull) {
            Objects.requireNonNull(ev.isNullInstruction);
            ObjectNode n = ev.isNullInstruction.putObject("Constant");
            n.put("is_null", true);
        } else {
            ObjectNode constant = ev.instruction.putObject("Constant");
            constant.set(type, Objects.requireNonNull(value));
            if (ev.isNullInstruction != null) {
                ObjectNode n = ev.isNullInstruction.putObject("Constant");
                n.put("is_null", false);
            }
        }
        return false;
    }

    @Override
    public boolean preorder(DBSPI32Literal expression) {
        return createJsonLiteral(expression, "I32",
                expression.value == null ? null : new IntNode(expression.value));
    }

    @Override
    public boolean preorder(DBSPI64Literal expression) {
        return createJsonLiteral(expression, "I64",
                expression.value == null ? null : new LongNode(expression.value));
    }

    @Override
    public boolean preorder(DBSPBoolLiteral expression) {
        return this.createJsonLiteral(expression, "Bool",
                expression.value == null ? null : BooleanNode.valueOf(expression.value));
    }

    @Override
    public boolean preorder(DBSPStringLiteral expression) {
        return this.createJsonLiteral(expression, "String",
                expression.value == null ? null : new TextNode(expression.value));
    }

    public boolean preorder(DBSPDoubleLiteral expression) {
        return this.createJsonLiteral(expression, "F64",
                expression.value == null ? null : new DoubleNode(expression.value));
    }

    public boolean preorder(DBSPFloatLiteral expression) {
        return this.createJsonLiteral(expression, "F32",
                expression.value == null ? null : new FloatNode(expression.value));
    }

    @Override
    public boolean preorder(DBSPCastExpression expression) {
        // "Cast": {
        //    "value": 2,
        //    "from": "I32",
        //    "to": "I64"
        //  }
        ExpressionIds sourceId = this.accept(expression.source);
        ExpressionJsonRepresentation ev = this.insertInstruction(expression);
        ObjectNode cast = ev.instruction.putObject("Cast");
        cast.put("value", sourceId.id);
        cast.put("from", baseTypeName(expression.source));
        cast.put("to", baseTypeName(expression));

        if (sourceId.hasNull()) {
            ObjectNode isNull = ev.getNullObject().putObject("CopyVal");
            isNull.put("value", sourceId.isNullId);
            isNull.put("value_ty", "Bool");
        }
        return false;
    }

    static final Map<String, String> opNames = new HashMap<>();

    static {
        // https://github.com/vmware/database-stream-processor/blob/dataflow-jit/crates/dataflow-jit/src/ir/expr.rs, the BinaryOpKind enum
        opNames.put("+", "Add");
        opNames.put("-", "Sub");
        opNames.put("*", "Mul");
        opNames.put("/", "Div");
        opNames.put("==", "Eq");
        opNames.put("!=", "Neq");
        opNames.put("<", "LessThan");
        opNames.put(">", "GreaterThan");
        opNames.put("<=", "LessThanOrEqual");
        opNames.put(">=", "GreaterThanOrEqual");
        opNames.put("&", "And");
        opNames.put("&&", "And");
        opNames.put("|", "Or");
        opNames.put("||", "Or");
        opNames.put("^", "Xor");
    }

    @Override
    public boolean preorder(DBSPBinaryExpression expression) {
        // "BinOp": {
        //   "lhs": 4,
        //   "rhs": 5,
        //   "operand_ty": "I64",
        //   "kind": "GreaterThan"
        // }
        ExpressionIds leftId = this.accept(expression.left);
        ExpressionIds rightId = this.accept(expression.right);
        ExpressionIds cf = this.constantBool(false);
        int leftNullId;
        if (leftId.hasNull())
            leftNullId = leftId.isNullId;
        else
            // Not nullable: use false.
            leftNullId = cf.id;
        int rightNullId;
        if (rightId.hasNull())
            rightNullId = rightId.isNullId;
        else
            rightNullId = cf.id;
        if (needsNull(expression.getNonVoidType())) {
            if (expression.operation.equals("&&")) {
                // Nullable bit computation
                // (a && b).is_null = a.is_null ? (b.is_null ? true     : !b.value)
                //                              : (b.is_null ? !a.value : false)

                // !b.value
                ExpressionJsonRepresentation notB = this.insertNewInstruction();
                ObjectNode op = notB.instruction.putObject("UnOp");
                op.put("value", rightId.id);
                op.put("kind", "Not");
                op.put("value_ty", "Bool");
                // true
                ExpressionIds trueLit = this.constantBool(true);
                // cond1 = (b.is_null ? true : !b.value)
                ExpressionJsonRepresentation cond1 = this.insertNewInstruction();
                ObjectNode cond = cond1.instruction.putObject("Select");
                cond.put("cond", rightNullId);
                cond.put("if_true", trueLit.id);
                cond.put("if_false", notB.instructionId);
                // false
                ExpressionIds falseLit = this.constantBool(false);
                // !a
                ExpressionJsonRepresentation notA = this.insertNewInstruction();
                op = notA.instruction.putObject("UnOp");
                op.put("value", leftId.id);
                op.put("kind", "Not");
                op.put("value_ty", "Bool");
                // cond2 = (b.is_null ? !a.value   : false)
                ExpressionJsonRepresentation cond2 = this.insertNewInstruction();
                cond = cond2.instruction.putObject("Select");
                cond.put("cond", rightNullId);
                cond.put("if_true", notA.instructionId);
                cond.put("if_false", falseLit.id);
                // Top-level condition
                ExpressionJsonRepresentation topCond = this.insertNewInstruction();
                cond = topCond.instruction.putObject("Select");
                cond.put("cond", leftNullId);
                cond.put("if_true", cond1.instructionId);
                cond.put("if_false", cond2.instructionId);

                // (a && b).value = a.is_null ? b.value
                //                            : (b.is_null ? a.value : a.value && b.value)
                // (The value for a.is_null & b.is_null does not matter, so we can choose it to be b.value)
                // a.value && b.value
                ExpressionJsonRepresentation and = this.insertNewInstruction();
                ObjectNode binOp = and.instruction.putObject("BinOp");
                binOp.put("lhs", leftId.id);
                binOp.put("rhs", rightId.id);
                binOp.put("kind", "And");
                binOp.put("operand_ty", baseTypeName(expression.left));
                // (b.is_null ? a.value : a.value && b.value)
                ExpressionJsonRepresentation secondBranch = this.insertNewInstruction();
                cond = secondBranch.instruction.putObject("Select");
                cond.put("cond", rightNullId);
                cond.put("if_true", leftId.id);
                cond.put("if_false", and.instructionId);
                // Final Mux
                ExpressionJsonRepresentation er = this.insertInstruction(expression);
                ObjectNode topBranch = er.instruction.putObject("Select");
                topBranch.put("if_true", rightId.id);
                topBranch.put("if_false", secondBranch.instructionId);
                return false;
            } else if (expression.operation.equals("||") &&
                    expression.getNonVoidType().is(DBSPTypeBool.class)) {
                // Nullable bit computation
                // (a || b).is_null = a.is_null ? (b.is_null ? true : b.value)
                //                              : (b.is_null ? a.value : false)
                // true
                ExpressionIds trueLit = this.constantBool(true);
                // cond1 = (b.is_null ? true : b.value)
                ExpressionJsonRepresentation cond1 = this.insertNewInstruction();
                ObjectNode cond = cond1.instruction.putObject("Select");
                cond.put("cond", rightNullId);
                cond.put("if_true", trueLit.id);
                cond.put("if_false", rightId.id);
                // false
                ExpressionIds falseLit = this.constantBool(false);
                // cond2 = (b.is_null ? a.value : false)
                ExpressionJsonRepresentation cond2 = this.insertNewInstruction();
                cond = cond2.instruction.putObject("Select");
                cond.put("cond", rightNullId);
                cond.put("if_true", leftId.id);
                cond.put("if_false", falseLit.id);
                // Top-level condition
                ExpressionJsonRepresentation topCond = this.insertNewInstruction();
                cond = topCond.instruction.putObject("Select");
                cond.put("cond", leftNullId);
                cond.put("if_true", cond1.instructionId);
                cond.put("if_false", cond2.instructionId);

                // (a || b).value = a.is_null ? b.value
                //                            : a.value || b.value
                // a.value || b.value
                ExpressionJsonRepresentation or = this.insertNewInstruction();
                ObjectNode binOp = or.instruction.putObject("BinOp");
                binOp.put("lhs", leftId.id);
                binOp.put("rhs", rightId.id);
                binOp.put("kind", "Or");
                binOp.put("operand_ty", baseTypeName(expression.left));
                // Result
                ExpressionJsonRepresentation secondBranch = this.insertInstruction(expression);
                cond = secondBranch.instruction.putObject("Select");
                cond.put("cond", leftNullId);
                cond.put("if_true", rightId.id);
                cond.put("if_false", or.instructionId);
                return false;
            } else if (expression.operation.equals("agg_plus")) {
                ExpressionJsonRepresentation er = this.insertInstruction(expression);
                ObjectNode binOp = er.instruction.putObject("BinOp");
                binOp.put("lhs", leftId.id);
                binOp.put("rhs", rightId.id);
                binOp.put("kind", Utilities.getExists(opNames, expression.operation));
                binOp.put("operand_ty", baseTypeName(expression.left));
                if (er.isNullInstruction != null) {
                    // The result is null if both operands are null.
                    binOp = er.isNullInstruction.putObject("BinOp");
                    binOp.put("lhs", leftNullId);
                    binOp.put("rhs", rightNullId);
                    binOp.put("kind", Utilities.getExists(opNames, "&&"));
                    binOp.put("operand_ty", "Bool");
                }
            }
        }

        ExpressionJsonRepresentation er = this.insertInstruction(expression);
        ObjectNode binOp = er.instruction.putObject("BinOp");
        binOp.put("lhs", leftId.id);
        binOp.put("rhs", rightId.id);
        binOp.put("kind", Utilities.getExists(opNames, expression.operation));
        binOp.put("operand_ty", baseTypeName(expression.left));
        if (er.isNullInstruction != null) {
            // The result is null if either operand is null.
            binOp = er.isNullInstruction.putObject("BinOp");
            binOp.put("lhs", leftNullId);
            binOp.put("rhs", rightNullId);
            binOp.put("kind", Utilities.getExists(opNames, "||"));
            binOp.put("operand_ty", "Bool");
        }
        return false;
    }

    @Override
    public boolean preorder(DBSPUnaryExpression expression) {
        // "UnOp": {
        //   "lhs": 4,
        //   "operand_ty": "I64",
        //   "kind": "Minus"
        // }
        boolean isWrapBool = expression.operation.equals("wrap_bool");
        ExpressionIds cf = null;
        if (isWrapBool)
            cf = this.constantBool(false);
        ExpressionIds leftId = this.accept(expression.source);
        String kind;
        switch (expression.operation) {
            case "-":
                kind = "Neg";
                break;
            case "!":
                kind = "Not";
                break;
            case "wrap_bool": {
                ExpressionJsonRepresentation er = this.insertInstruction(expression);
                ObjectNode cond = er.instruction.putObject("Select");
                cond.put("cond", leftId.isNullId);
                cond.put("if_true", cf.id);
                cond.put("if_false", leftId.id);
                return false;
            }
            case "is_false": {
                if (leftId.hasNull()) {
                    // result = left.is_null ? false : !left.value
                    ExpressionJsonRepresentation ni = this.insertNewInstruction();
                    // ! left.value
                    ObjectNode op = ni.instruction.putObject("UnOp");
                    op.put("value", leftId.id);
                    op.put("kind", "Not");
                    op.put("value_ty", baseTypeName(expression.source));
                    ExpressionIds False = this.constantBool(false);
                    // result
                    ExpressionJsonRepresentation er = this.insertInstruction(expression);
                    ObjectNode cond = er.instruction.putObject("Select");
                    cond.put("cond", leftId.isNullId);
                    cond.put("if_true", False.id);
                    cond.put("if_false", ni.instructionId);
                    return false;
                } else {
                    kind = "Not";
                }
                break;
            }
            case "is_true": {
                if (leftId.hasNull()) {
                    // result = left.is_null ? false : left.value
                    ExpressionIds False = this.constantBool(false);
                    // result
                    ExpressionJsonRepresentation er = this.insertInstruction(expression);
                    ObjectNode cond = er.instruction.putObject("Select");
                    cond.put("cond", leftId.isNullId);
                    cond.put("if_true", False.id);
                    cond.put("if_false", leftId.id);
                    return false;
                } else {
                    Utilities.putNew(this.expressionId, expression, leftId.id);
                    return false;
                }
            }
            case "is_not_true": {
                if (leftId.hasNull()) {
                    // result = left.is_null ? true : !left.value
                    ExpressionJsonRepresentation ni = this.insertNewInstruction();
                    // ! left.value
                    ObjectNode op = ni.instruction.putObject("UnOp");
                    op.put("value", leftId.id);
                    op.put("kind", "Not");
                    op.put("value_ty", baseTypeName(expression.source));
                    ExpressionIds True = this.constantBool(true);
                    // result
                    ExpressionJsonRepresentation er = this.insertInstruction(expression);
                    ObjectNode cond = er.instruction.putObject("Select");
                    cond.put("cond", leftId.isNullId);
                    cond.put("if_true", True.id);
                    cond.put("if_false", ni.instructionId);
                    return false;
                } else {
                    kind = "Not";
                }
                break;
            }
            case "is_not_false": {
                if (leftId.hasNull()) {
                    // result = left.is_null ? true : left.value
                    ExpressionIds True = this.constantBool(true);
                    // result
                    ExpressionJsonRepresentation er = this.insertInstruction(expression);
                    ObjectNode cond = er.instruction.putObject("Select");
                    cond.put("cond", leftId.isNullId);
                    cond.put("if_true", True.id);
                    cond.put("if_false", leftId.id);
                    return false;
                } else {
                    Utilities.putNew(this.expressionId, expression, leftId.id);
                    return false;
                }
            }
            default:
                throw new Unimplemented(expression);
        }
        ExpressionJsonRepresentation er = this.insertInstruction(expression);
        ObjectNode op = er.instruction.putObject("UnOp");
        op.put("value", leftId.id);
        op.put("kind", kind);
        op.put("value_ty", baseTypeName(expression.source));
        if (leftId.hasNull()) {
            int leftNullId = leftId.isNullId;
            op = er.getNullObject().putObject("CopyVal");
            op.put("value", leftNullId);
            op.put("value_ty", "Bool");
        }
        return false;
    }

    @Override
    public boolean preorder(DBSPVariablePath expression) {
        ExpressionIds ids = this.resolve(expression.variable);
        this.expressionId.put(expression, ids.id);
        // may already be there, but this may be a new variable with the same name,
        // and then we overwrite with the new definition.
        return false;
    }

    @Override
    public boolean preorder(DBSPClosureExpression expression) {
        for (DBSPParameter param: expression.parameters) {
            DBSPIdentifierPattern identifier = param.pattern.to(DBSPIdentifierPattern.class);
            this.declare(identifier.identifier, needsNull(param.type));
        }
        DBSPType ret = expression.getResultType();
        int variablesToAdd = 0;
        if (!ToJitVisitor.isScalarType(ret)) {
            // If the closure returns a RawTuple, create a variable for each field of the tuple.
            List<DBSPTypeTuple> fields = ToJitVisitor.TypeCatalog.expandToTuples(ret);
            variablesToAdd = fields.size();
            for (int i = 0; i < variablesToAdd; i++) {
                DBSPType type = fields.get(i);
                String varName = RETURN_PARAMETER_PREFIX + "_" + i;
                // For the outermost closure this will assign the same expression
                // ids to these variables as were assigned to the "output" parameters
                // by ToJitVisitor.createFunction.  So assigning to these expressions
                // will in fact assign to the "output" parameters.
                this.declare(varName, type.mayBeNull);
                this.variableAssigned.add(varName);
            }
        }
        expression.body.accept(this);
        for (int i = 0; i < variablesToAdd; i++) {
            String removed = Utilities.removeLast(this.variableAssigned);
            if (!removed.startsWith(RETURN_PARAMETER_PREFIX))
                throw new RuntimeException("Unexpected variable removed " + removed);
        }
        return false;
    }

    @Override
    public boolean preorder(DBSPLetStatement statement) {
        boolean isTuple = statement.type.is(DBSPTypeTuple.class);
        ExpressionIds ids = this.declare(statement.variable, needsNull(statement.type));
        ArrayNode instruction = Objects.requireNonNull(this.currentBlockBody).addArray();
        instruction.add(ids.id);
        this.variableAssigned.add(statement.variable);
        if (isTuple) {
            ObjectNode uninit = instruction.addObject();
            ObjectNode storeNode = uninit.putObject("UninitRow");
            int typeId = this.catalog.getTypeId(statement.type);
            storeNode.put("layout", typeId);
        }
        if (statement.initializer != null)
            statement.initializer.accept(this);
        Utilities.removeLast(this.variableAssigned);
        return false;
    }

    @Override
    public boolean preorder(DBSPFieldExpression expression) {
        // "Load": {
        //   "source": 1,
        //   "source_layout": 2,
        //   "column": 1,
        //   "column_type": "I32"
        // }
        ExpressionJsonRepresentation er = this.insertInstruction(expression);
        ObjectNode load = er.instruction.putObject("Load");
        ExpressionIds sourceId = this.accept(expression.expression);
        load.put("source", sourceId.id);
        int typeId = this.catalog.getTypeId(expression.expression.getNonVoidType());
        load.put("source_layout", typeId);
        load.put("column", expression.fieldNo);
        load.put("column_type", baseTypeName(expression));
        if (er.isNullInstruction != null) {
            ObjectNode isNull = er.isNullInstruction.putObject("IsNull");
            isNull.put("target", sourceId.id);
            isNull.put("target_layout", typeId);
            isNull.put("column", expression.fieldNo);
        }
        return false;
    }

    @Override
    public boolean preorder(DBSPIsNullExpression expression) {
        ExpressionIds sourceId = this.accept(expression.expression);
        Utilities.putNew(this.expressionId, expression, sourceId.isNullId);
        return false;
    }

    @Override
    public boolean preorder(DBSPCloneExpression expression) {
        // Treat clone as a noop.
        ExpressionIds source = this.accept(expression.expression);
        Utilities.putNew(this.expressionId, expression, source.id);
        return false;
    }

    @Override
    public boolean preorder(DBSPIfExpression expression) {
        // "Branch": {
        //    "cond": {
        //      "Expr": 3
        //    },
        //    "truthy": 3,
        //    "falsy": 2
        // }
        // TODO: handle nulls
        ExpressionIds condId = this.accept(expression.condition);
        ExpressionJsonRepresentation er = this.insertInstruction(expression);
        ObjectNode branch = er.instruction.putObject("Branch");
        ObjectNode branchExpr = branch.putObject("cond");
        branchExpr.put("Expr", condId.id);
        ExpressionIds posId = this.accept(expression.positive);
        ExpressionIds negId = this.accept(expression.negative);
        branch.put("truthy", posId.id);
        branch.put("falsy", negId.id);
        return false;
    }

    @Override
    public boolean preorder(DBSPRawTupleExpression expression) {
        // Each field is assigned to a different variable.
        // Remove the last n variables
        List<String> tail = new ArrayList<>();
        for (DBSPExpression ignored: expression.fields)
            tail.add(Utilities.removeLast(this.variableAssigned));
        for (DBSPExpression field: expression.fields) {
            // Add each variable and process the corresponding field.
            this.variableAssigned.add(Utilities.removeLast(tail));
            // Convert RawTuples inside RawTuples to regular Tuples
            if (field.is(DBSPRawTupleExpression.class))
                field = new DBSPTupleExpression(field.to(DBSPRawTupleExpression.class).fields);
            field.accept(this);
        }
        return false;
    }

    @Override
    public boolean preorder(DBSPTupleExpression expression) {
        // Compile this as an assignment to the currently assigned variable
        String variableAssigned = this.variableAssigned.get(this.variableAssigned.size() - 1);
        ExpressionIds retValId = this.resolve(variableAssigned);
        int tupleTypeId = this.catalog.getTypeId(expression.getNonVoidType());
        int index = 0;
        for (DBSPExpression field: expression.fields) {
            // Generates 1 or 2 instructions for each field (depending on nullability)
            // "Store": {
            //   "target": 2,
            //   "target_layout": 3,
            //   "column": 0,
            //   "value": {
            //     "Expr": 3
            //   },
            //   "value_type": "I32"
            // }
            ExpressionIds fieldId = this.accept(field);
            ArrayNode instruction = Objects.requireNonNull(this.currentBlockBody).addArray();
            instruction.add(this.nextExpressionId());
            ObjectNode store = instruction.addObject();
            ObjectNode storeNode = store.putObject("Store");
            storeNode.put("target", retValId.id);
            storeNode.put("target_layout", tupleTypeId);
            storeNode.put("column", index);
            ObjectNode value = storeNode.putObject("value");
            value.put("Expr", fieldId.id);
            storeNode.put("value_type", ToJitVisitor.baseTypeName(field.getNonVoidType()));
            if (fieldId.hasNull()) {
                // "SetNull": {
                //  "target": 2,
                //  "target_layout": 3,
                //  "column": 0,
                //  "is_null": {
                //    "Expr": 4 }}
                instruction = Objects.requireNonNull(this.currentBlockBody).addArray();
                instruction.add(this.nextExpressionId());
                store = instruction.addObject();
                storeNode = store.putObject("SetNull");
                storeNode.put("target", retValId.id);
                storeNode.put("target_layout", tupleTypeId);
                storeNode.put("column", index);
                ObjectNode isNull = storeNode.putObject("is_null");
                isNull.put("Expr", fieldId.isNullId);
            }
            index++;
        }
        return false;
    }

    @Override
    public boolean preorder(DBSPBlockExpression expression) {
        this.newContext();
        ObjectNode saveBlock = this.currentBlock;
        ArrayNode saveBody = this.currentBlockBody;
        int blockId = this.nextBlockId();
        this.currentBlock = this.blocks.putObject(Integer.toString(blockId));
        this.currentBlock.put("id", blockId);
        this.currentBlockBody = this.currentBlock.putArray("body");
        for (DBSPStatement stat: expression.contents)
            stat.accept(this);

        // TODO: handle nullability
        ExpressionIds resultId = null;
        if (expression.lastExpression != null) {
            if (ToJitVisitor.isScalarType(expression.lastExpression.getType())) {
                resultId = this.accept(expression.lastExpression);
            } else {
                // If the result is a tuple, this will store the result in the return value
                // and this block will return Unit.
                expression.lastExpression.accept(this);
                if (ToJitVisitor.isScalarType(expression.getNonVoidType())) {
                    // Otherwise the result of the block
                    // is the result computed by the last expression
                    int id = this.getExpressionId(expression.lastExpression);
                    Utilities.putNew(this.expressionId, expression, id);
                }
            }
        }
        ObjectNode terminator = this.currentBlock.putObject("terminator");
        ObjectNode ret = terminator.putObject("Return");
        ObjectNode retValue = ret.putObject("value");
        if (resultId != null) {
            retValue.put("Expr", resultId.id);
        } else {
            retValue.put("Imm", "Unit");
        }
        this.popContext();
        this.currentBlock = saveBlock;
        this.currentBlockBody = saveBody;
        return false;
    }

    /**
     * Convert the body of a closure expression to a JSON representation
     * @param expression  Expression to generate code for.
     * @param blocks      Insert here the blocks of the body of the closure.
     * @param catalog     The catalog of Tuple types.
     */
    static void convertClosure(DBSPClosureExpression expression,
                               ObjectNode blocks,
                               ToJitVisitor.TypeCatalog catalog) {
        ToJitInnerVisitor visitor = new ToJitInnerVisitor(blocks, catalog);
        visitor.newContext();
        expression.accept(visitor);
        visitor.popContext();
    }
}