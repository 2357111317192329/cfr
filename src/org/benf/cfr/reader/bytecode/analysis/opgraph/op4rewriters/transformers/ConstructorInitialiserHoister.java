package org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.AbstractAssignmentExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.AssignmentExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ConditionalExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.LValueExpression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.MemberFunctionInvokation;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.SuperFunctionInvokation;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.AbstractExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.CloneHelper;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriterFlags;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.SSAIdentifiers;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.Block;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredAssignment;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredExpressionStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredLocalDeclaration;
import org.benf.cfr.reader.util.collections.MapFactory;
import org.benf.cfr.reader.util.collections.SetFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Obfuscated code (and to a lesser degree verify-able bytecode in general) is allowed to run
 * arbitrary statements before calling a constructor's super(...) / this(...) initialiser.
 * Java is not - the initialiser call must be the first statement of a constructor body, so
 * if we have emitted it anywhere else, the code will not compile.
 *
 * This hoists such a call to the front of the constructor body.  Note that emitting the
 * call anywhere other than the first statement was already a compile error, so this can
 * not make reasonable output worse.
 *
 * Additionally, any argument which is a variable assigned exactly once in the method is
 * replaced by the value of that assignment - the assignment may end up after the
 * initialiser call, where it could not provide the argument.  Definitions which themselves
 * reference single-assignment variables are expanded recursively, with cycles prevented,
 * so chains of obfuscated assignments at the front of a constructor collapse conveniently.
 * Definition chains containing nested assignments are left alone, to be safe.
 */
public class ConstructorInitialiserHoister implements StructuredStatementTransformer {

    private static final int MAX_EXPANSION_DEPTH = 16;

    private Op04StructuredStatement initStatement;
    private Block containingBlock;

    private final Map<LValue, StructuredAssignment> assignments = MapFactory.newMap();
    private final Set<LValue> ambiguous = SetFactory.newSet();
    private final Map<LValue, Expression> expandedDefs = MapFactory.newMap();
    private Set<LValue> expanding;

    private static boolean isInitialiserExpression(Expression expression) {
        if (expression instanceof SuperFunctionInvokation) {
            return ((SuperFunctionInvokation) expression).isInit();
        }
        // <init> on a member function invocation is the implicit this(...) initialiser.
        return expression instanceof MemberFunctionInvokation &&
                ((MemberFunctionInvokation) expression).isInitMethod();
    }

    @Override
    public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
        if (in instanceof StructuredAssignment) {
            LValue lValue = ((StructuredAssignment) in).getLvalue();
            if (assignments.containsKey(lValue)) {
                ambiguous.add(lValue);
            } else {
                assignments.put(lValue, (StructuredAssignment) in);
            }
        }
        in.transformStructuredChildren(this, scope);
        if (initStatement != null) return in;
        if (in instanceof StructuredExpressionStatement) {
            if (isInitialiserExpression(((StructuredExpressionStatement) in).getExpression())) {
                initStatement = in.getContainer();
                StructuredStatement parent = scope.get(0);
                if (parent instanceof Block) {
                    containingBlock = (Block) parent;
                } else {
                    // Shouldn't happen - a naked statement can't be hoisted.
                    initStatement = null;
                }
            }
        }
        return in;
    }

    // Build the fully expanded definition of lValue (if it has a unique, pure assignment).
    private Expression buildExpanded(LValue lValue, int depth) {
        if (depth > MAX_EXPANSION_DEPTH) return null;
        if (expandedDefs.containsKey(lValue)) return expandedDefs.get(lValue);
        if (!assignments.containsKey(lValue) || ambiguous.contains(lValue)) return null;
        if (expanding.contains(lValue)) return null;
        StructuredAssignment definition = assignments.get(lValue);
        Expression rvalue = definition.getRvalue();
        // Chained assignments (v0 = var1_1 = ... ) - take the innermost rvalue.
        while (rvalue instanceof AssignmentExpression) {
            rvalue = ((AssignmentExpression) rvalue).getrValue();
        }
        if (ContainsAssignment.contains(rvalue)) return null;
        StatementContainer<StructuredStatement> container = definition.getContainer();
        expanding.add(lValue);
        Expression clone = rvalue.deepClone(new CloneHelper());
        Expression expanded = new Expander(this, depth + 1).rewriteLocal(clone, container);
        expanding.remove(lValue);
        expandedDefs.put(lValue, expanded);
        return expanded;
    }

    // Expands uses of single-assignment variables inside an expression.
    private static class Expander extends AbstractExpressionRewriter {
        private final ConstructorInitialiserHoister hoister;
        private final int depth;

        private Expander(ConstructorInitialiserHoister hoister, int depth) {
            this.hoister = hoister;
            this.depth = depth;
        }

        private Expression rewriteLocal(Expression expression, StatementContainer statementContainer) {
            return rewriteExpression(expression, null, statementContainer, ExpressionRewriterFlags.RVALUE);
        }

        @Override
        public Expression rewriteExpression(Expression expression, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
            if (expression instanceof LValueExpression) {
                LValue lValue = ((LValueExpression) expression).getLValue();
                Expression expanded = hoister.buildExpanded(lValue, 1);
                if (expanded != null) {
                    return expanded.deepClone(new CloneHelper());
                }
                return expression;
            }
            return expression.applyExpressionRewriter(this, ssaIdentifiers, statementContainer, flags);
        }

        @Override
        public ConditionalExpression rewriteExpression(ConditionalExpression expression, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
            return (ConditionalExpression) rewriteExpression((Expression) expression, ssaIdentifiers, statementContainer, flags);
        }
    }

    private static class ContainsAssignment extends AbstractExpressionRewriter {
        private boolean found;

        private ContainsAssignment() {
        }

        private static boolean contains(Expression expression) {
            ContainsAssignment containsAssignment = new ContainsAssignment();
            containsAssignment.rewriteExpression(expression, null, null, ExpressionRewriterFlags.RVALUE);
            return containsAssignment.found;
        }

        @Override
        public Expression rewriteExpression(Expression expression, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
            if (expression instanceof AbstractAssignmentExpression) {
                found = true;
            }
            return expression.applyExpressionRewriter(this, ssaIdentifiers, statementContainer, flags);
        }
    }

    public void hoist(Op04StructuredStatement root) {
        StructuredStatement rootStatement = root.getStatement();
        if (!(rootStatement instanceof Block)) return;
        initStatement = null;
        containingBlock = null;
        StructuredScope scope = new StructuredScope();
        scope.add(rootStatement);
        rootStatement.transformStructuredChildren(this, scope);
        if (initStatement == null) return;

        List<Op04StructuredStatement> topLevel = ((Block) rootStatement).getBlockStatements();
        List<Op04StructuredStatement> containing = containingBlock.getBlockStatements();
        if (containing == topLevel && containing.indexOf(initStatement) == 0) return;

        // Arguments of the initialiser may reference (or be declared amongst) the leading
        // local variable declarations of the body - those need to come first.
        int insertAt = 0;
        while (insertAt < topLevel.size() &&
                topLevel.get(insertAt).getStatement() instanceof StructuredLocalDeclaration) {
            ++insertAt;
        }
        containing.remove(initStatement);
        topLevel.add(insertAt, initStatement);

        // Expand any arg referencing a uniquely assigned variable.
        StructuredExpressionStatement structuredExpressionStatement =
                (StructuredExpressionStatement) initStatement.getStatement();
        Expression expression = structuredExpressionStatement.getExpression();
        List<Expression> args;
        if (expression instanceof SuperFunctionInvokation) {
            args = ((SuperFunctionInvokation) expression).getArgs();
        } else if (expression instanceof MemberFunctionInvokation) {
            args = ((MemberFunctionInvokation) expression).getArgs();
        } else {
            // Shouldn't happen.
            return;
        }
        expanding = SetFactory.newSet();
        Expander expander = new Expander(this, 0);
        for (int x = 0, len = args.size(); x < len; ++x) {
            args.set(x, expander.rewriteLocal(args.get(x), initStatement));
        }
        expanding = null;
    }
}
