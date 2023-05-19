package org.dbsp.sqlCompiler.ir.expression.literal;

import org.dbsp.sqlCompiler.ir.InnerVisitor;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeNull;

/**
 * A literal with type NULL, the only value of this type.
 */
public class DBSPNullLiteral extends DBSPLiteral {
    public static final DBSPNullLiteral INSTANCE = new DBSPNullLiteral();

    private DBSPNullLiteral() {
        super(null, DBSPTypeNull.INSTANCE, null);
    }

    @Override
    public void accept(InnerVisitor visitor) {
        if (!visitor.preorder(this)) return;
        visitor.postorder(this);
    }
}
