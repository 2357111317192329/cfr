package org.benf.cfr.reader.bytecode.analysis.structured.statement;

import org.benf.cfr.reader.bytecode.analysis.loc.BytecodeLoc;
import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.PrimitiveBoxingRewriter;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.matchutil.MatchIterator;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.matchutil.MatchResultCollector;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.rewriteinterface.BoxingProcessor;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriterFlags;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.BlockIdentifier;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.SSAIdentifiers;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.LValueScopeDiscoverer;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.ArrayList;
import java.util.List;

public class StructuredSwitch extends AbstractStructuredBlockStatement implements BoxingProcessor {
    private Expression switchOn;
    private final BlockIdentifier blockIdentifier;
    // Not checked by match.
    private final boolean safeExpression;

    public StructuredSwitch(BytecodeLoc loc, Expression switchOn, Op04StructuredStatement body, BlockIdentifier blockIdentifier, boolean safeExpression) {
        super(loc, body);
        this.switchOn = switchOn;
        this.blockIdentifier = blockIdentifier;
        this.safeExpression = safeExpression;
    }

    public StructuredSwitch(BytecodeLoc loc, Expression switchOn, Op04StructuredStatement body, BlockIdentifier blockIdentifier) {
        this(loc, switchOn, body, blockIdentifier, false);
    }

    @Override
    public BytecodeLoc getCombinedLoc() {
        return BytecodeLoc.combine(this, switchOn);
    }

    public Expression getSwitchOn() {
        return switchOn;
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        switchOn.collectTypeUsages(collector);
        super.collectTypeUsages(collector);
    }

    @Override
    public Dumper dump(Dumper dumper) {
        if (blockIdentifier.hasForeignReferences()) dumper.print(blockIdentifier.getName() + " : ");
        dumper.print("switch (").dump(switchOn).print(") ");
        StructuredStatement body = getBody().getStatement();
        if (body instanceof Block && canPromoteNestedCases((Block)body)) {
            dumpSwitchBody(dumper, (Block)body);
        } else {
            getBody().dump(dumper);
        }
        return dumper;
    }

    private boolean canPromoteNestedCases(Block switchBody) {
        if (!switchBody.isIndenting() || switchBody.hasForeignReferences()) return false;
        for (Op04StructuredStatement statement : switchBody.getBlockStatements()) {
            StructuredStatement child = statement.getStatement();
            if (child instanceof Block && canPromoteCases((Block)child)) return true;
        }
        return false;
    }

    private boolean canPromoteCases(Block block) {
        if (!block.isIndenting() || block.hasForeignReferences()) return false;
        for (Op04StructuredStatement statement : block.getBlockStatements()) {
            if (statement.getStatement() instanceof StructuredCase) return true;
        }
        return false;
    }

    private void dumpSwitchBody(Dumper dumper, Block switchBody) {
        dumper.separator("{").newln();
        dumper.indent(1);
        for (Op04StructuredStatement statement : switchBody.getBlockStatements()) {
            StructuredStatement child = statement.getStatement();
            if (child instanceof Block && canPromoteCases((Block)child)) {
                dumpPromotedCases(dumper, (Block)child);
            } else {
                statement.dump(dumper);
            }
        }
        dumper.indent(-1);
        dumper.separator("}").newln();
    }

    /*
     * A switch case can be left inside a synthetic block when control-flow joins
     * have multiple incoming edges.  Java only permits case labels directly in
     * the switch body, so move the label out and retain the block contents in a
     * case-local scope.
     */
    private void dumpPromotedCases(Dumper dumper, Block block) {
        List<Op04StructuredStatement> prefix = new ArrayList<Op04StructuredStatement>();
        List<Op04StructuredStatement> caseBody = new ArrayList<Op04StructuredStatement>();
        StructuredCase currentCase = null;
        for (Op04StructuredStatement statement : block.getBlockStatements()) {
            StructuredStatement child = statement.getStatement();
            if (child instanceof StructuredCase) {
                if (currentCase != null) {
                    dumpPromotedCase(dumper, currentCase, prefix, caseBody);
                    prefix.clear();
                    caseBody.clear();
                }
                currentCase = (StructuredCase)child;
            } else if (currentCase == null) {
                prefix.add(statement);
            } else {
                caseBody.add(statement);
            }
        }
        if (currentCase != null) {
            dumpPromotedCase(dumper, currentCase, prefix, caseBody);
        } else {
            block.dump(dumper);
        }
    }

    private void dumpPromotedCase(Dumper dumper, StructuredCase structuredCase, List<Op04StructuredStatement> prefix, List<Op04StructuredStatement> caseBody) {
        structuredCase.dumpHeader(dumper);
        dumper.separator("{").newln();
        dumper.indent(1);
        for (Op04StructuredStatement statement : prefix) statement.dump(dumper);
        structuredCase.getBody().dump(dumper);
        for (Op04StructuredStatement statement : caseBody) statement.dump(dumper);
        dumper.indent(-1);
        dumper.separator("}").newln();
    }

    @Override
    public BlockIdentifier getBreakableBlockOrNull() {
        return blockIdentifier; // even if no foreign references.
    }

    @Override
    public boolean supportsBreak() {
        return true;
    }

    @Override
    public boolean isProperlyStructured() {
        return true;
    }

    @Override
    public boolean isScopeBlock() {
        return true;
    }

    public boolean rewriteBoxing(PrimitiveBoxingRewriter boxingRewriter) {
        switchOn = boxingRewriter.sugarUnboxing(switchOn);
        return true;
    }

    @Override
    public void applyNonArgExpressionRewriter(ExpressionRewriter expressionRewriter, SSAIdentifiers ssaIdentifiers, StatementContainer statementContainer, ExpressionRewriterFlags flags) {
    }

    public BlockIdentifier getBlockIdentifier() {
        return blockIdentifier;
    }

    @Override
    public void linearizeInto(List<StructuredStatement> out) {
        out.add(this);
        getBody().linearizeStatementsInto(out);
    }

    @Override
    public void traceLocalVariableScope(LValueScopeDiscoverer scopeDiscoverer) {
        switchOn.collectUsedLValues(scopeDiscoverer);
        // We have a spurious Block underneath us, but that's ok - anything that is discovered
        // into that scope will be discovered into here.
        scopeDiscoverer.enterBlock(this);
        scopeDiscoverer.processOp04Statement(getBody());
        scopeDiscoverer.leaveBlock(this);
    }

    @Override
    public boolean match(MatchIterator<StructuredStatement> matchIterator, MatchResultCollector matchResultCollector) {
        StructuredStatement o = matchIterator.getCurrent();
        if (!(o instanceof StructuredSwitch)) return false;
        StructuredSwitch other = (StructuredSwitch) o;
        if (!switchOn.equals(other.switchOn)) return false;
        if (!blockIdentifier.equals(other.blockIdentifier)) return false;
        matchIterator.advance();
        return true;
    }

    @Override
    public void rewriteExpressions(ExpressionRewriter expressionRewriter) {
        expressionRewriter.handleStatement(this.getContainer());
        switchOn = expressionRewriter.rewriteExpression(switchOn, null, this.getContainer(), null);
    }

    public boolean isOnlyEmptyDefault() {
        StructuredStatement stm = getBody().getStatement();
        if (!(stm instanceof Block)) return false;
        Pair<Boolean, Op04StructuredStatement> onestm = ((Block) stm).getOneStatementIfPresent();
        if (onestm.getSecond() == null) return false;
        StructuredStatement single = onestm.getSecond().getStatement();
        // should be!
        if (!(single instanceof StructuredCase)) return false;
        StructuredCase cs = (StructuredCase)single;
        if (!cs.isDefault()) return false;
        StructuredStatement caseBody = cs.getBody().getStatement();
        if (!(caseBody instanceof Block)) return false;
        return caseBody.isEffectivelyNOP();
    }

    public boolean isSafeExpression() {
        return safeExpression;
    }
}
