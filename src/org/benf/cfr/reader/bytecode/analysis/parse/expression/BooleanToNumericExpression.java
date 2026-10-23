package org.benf.cfr.reader.bytecode.analysis.parse.expression;

import org.benf.cfr.reader.bytecode.analysis.loc.BytecodeLoc;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.literal.TypedLiteral;
import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.bytecode.analysis.types.RawJavaType;

final class BooleanToNumericExpression {
    private BooleanToNumericExpression() {
    }

    static Expression coerce(Expression expression, JavaTypeInstance numericType) {
        if (expression.getInferredJavaType().getJavaTypeInstance() != RawJavaType.BOOLEAN || !(numericType instanceof RawJavaType)) return expression;
        RawJavaType target = (RawJavaType) numericType;
        if (!target.isNumber()) return expression;
        if (expression instanceof Literal) {
            Boolean value = ((Literal) expression).getValue().getMaybeBoolValue();
            if (value != null) return numericLiteral(target, value ? 1 : 0);
        }
        return new TernaryExpression(expression.getLoc(), BooleanExpression.of(expression), numericLiteral(target, 1), numericLiteral(target, 0));
    }

    private static Literal numericLiteral(RawJavaType type, int value) {
        switch (type) {
            case LONG:
                return new Literal(TypedLiteral.getLongLocked(value));
            case FLOAT:
                return new Literal(TypedLiteral.getFloatLocked(value));
            case DOUBLE:
                return new Literal(TypedLiteral.getDoubleLocked(value));
            default:
                return value == 0 ? Literal.INT_ZERO : Literal.INT_ONE;
        }
    }
}
