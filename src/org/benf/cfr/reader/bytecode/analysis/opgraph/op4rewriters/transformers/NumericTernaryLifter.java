package org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.BooleanExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.Literal;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.LValueExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.StackValue;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.TernaryExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.literal.TypedLiteral;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.AbstractExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriterFlags;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.SSAIdentifiers;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.types.RawJavaType;

/**
 * Remove boolean-to-numeric coercion ternaries (x ? 1 : 0) where the condition is not
 * actually a boolean (i.e. CFR mistyped an int stack value / lvalue as boolean).  In
 * that case, the honest reading is that the condition itself is an integral value.
 */
public class NumericTernaryLifter extends AbstractExpressionRewriter implements StructuredStatementTransformer {
    public void transform(Op04StructuredStatement root) {
        root.transform(this, new StructuredScope());
    }

    @Override
    public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
        in.transformStructuredChildren(this, scope);
        in.rewriteExpressions(this);
        return in;
    }

    private Expression unwrapBool(Expression condition) {
        return condition instanceof BooleanExpression ? ((BooleanExpression) condition).getInner() : condition;
    }

    private RawJavaType lValueRawType(Expression inner) {
        LValue lValue = null;
        if (inner instanceof LValueExpression) {
            lValue = ((LValueExpression) inner).getLValue();
        } else if (inner instanceof StackValue) {
            lValue = ((StackValue) inner).getStackValue();
        }
        if (lValue == null) return null;
        return lValue.getInferredJavaType().getRawType();
    }

    @Override
    public Expression rewriteExpression(Expression expression, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
        if (!(expression instanceof TernaryExpression)) {
            return expression.applyExpressionRewriter(this, ssaIdentifiers, statementContainer, flags);
        }
        TernaryExpression ternaryExpression = (TernaryExpression) expression;
        ternaryExpression = (TernaryExpression) ternaryExpression.applyExpressionRewriter(this, ssaIdentifiers, statementContainer, flags);

        if (ternaryExpression.getInferredJavaType().getRawType() == RawJavaType.BOOLEAN) return ternaryExpression;

        Expression lhs = ternaryExpression.getLhs();
        Expression rhs = ternaryExpression.getRhs();
        if (!(lhs instanceof Literal) || !(rhs instanceof Literal)) return ternaryExpression;
        
        TypedLiteral lhsLit = ((Literal) lhs).getValue();
        TypedLiteral rhsLit = ((Literal) rhs).getValue();
        if (lhsLit.getType() != TypedLiteral.LiteralType.Integer || lhsLit.getIntValue() != 1) return ternaryExpression;
        if (rhsLit.getType() != TypedLiteral.LiteralType.Integer || rhsLit.getIntValue() != 0) return ternaryExpression;

        Expression inner = unwrapBool(ternaryExpression.getCondition());
        RawJavaType rawType = lValueRawType(inner);

        if (rawType == null || rawType == RawJavaType.BOOLEAN) return ternaryExpression;
        if (rawType.getStackType() != inner.getInferredJavaType().getRawType().getStackType()) return ternaryExpression;

        // The condition is an integral lvalue which has been mistyped as boolean -
        // the ternary is just a numeric coercion of that value.
        return inner;
    }
}
