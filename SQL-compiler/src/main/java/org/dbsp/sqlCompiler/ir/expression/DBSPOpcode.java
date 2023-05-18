package org.dbsp.sqlCompiler.ir.expression;

public enum DBSPOpcode {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
    MOD("%"),
    EQ("=="),
    NEQ("!="),
    LT("<"),
    GT(">"),
    LTE("<="),
    GTE(">="),
    SHR(">>"),
    SHL("<<"),
    AND("&&"),
    BW_AND("&"),
    MUL_WEIGHT("mul_weight"),
    OR("||"),
    BW_OR("|"), // bitwise or
    XOR("^"),
    MAX("max"),
    MIN("min"),
    AGG_MAX("agg_max"),
    AGG_MIN("agg_min"),
    CONCAT("||"),
    IS_DISTINCT("is_distinct"),
    IS_NOT_DISTINCT("is_not_distinct"),
    IS_FALSE("is_false"),
    IS_TRUE("is_true"),
    IS_NOT_TRUE("is_not_true"),
    IS_NOT_FALSE("is_not_false"),
    AGG_PLUS("+"),  // plus used in aggregation, different nullability rules
    AGG_ADD("agg_plus");

    private final String text;

    DBSPOpcode(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return this.text;
    }
}
