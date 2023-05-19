package org.dbsp.sqlCompiler.ir.expression.literal;

import org.dbsp.sqlCompiler.ir.InnerVisitor;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeInteger;

import javax.annotation.Nullable;

public class DBSPI16Literal extends DBSPLiteral {
    @Nullable
    public final Short value;

    public DBSPI16Literal() {
        this(null, true);
    }

    public DBSPI16Literal(short value) {
        this(value, false);
    }

    public DBSPI16Literal(@Nullable Short value, boolean nullable) {
        super(null, DBSPTypeInteger.SIGNED_16.setMayBeNull(nullable), value);
        if (value == null && !nullable)
            throw new RuntimeException("Null value with non-nullable type");
        this.value = value;
    }

    @Override
    public void accept(InnerVisitor visitor) {
        if (!visitor.preorder(this)) return;
        visitor.postorder(this);
    }

    public DBSPTypeInteger getIntegerType() {
        assert this.type != null;
        return this.type.to(DBSPTypeInteger.class);
    }
}
