package org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.Block;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredBreak;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredCase;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredComment;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredContinue;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredReturn;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredSwitch;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredThrow;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredWhile;

import java.util.List;

/**
 * Removes unreachable statements that follow a terminator (return/break/continue/throw)
 * or a non-completing switch in the same basic block. Also removes orphan breaks
 * (local breaks not inside any loop or switch), which can be left when obfuscator
 * control-flow patterns cause CFR to dissolve loop wrappers without removing their
 * filler breaks.
 */
public class UnreachableStatementRemover implements StructuredStatementTransformer {

    public void transform(Op04StructuredStatement root) {
        StructuredScope structuredScope = new StructuredScope();
        root.transform(this, structuredScope);
    }

    @Override
    public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
        if (in instanceof Block) {
            Block block = (Block) in;
            List<Op04StructuredStatement> contained = block.getBlockStatements();
            removeUnreachableAfterTerminators(contained);
        }
        // Check for orphan breaks at this level
        if (in instanceof StructuredBreak) {
            StructuredBreak brk = (StructuredBreak) in;
            if (brk.isLocalBreak() && !isInsideBreakable(scope)) {
                // Orphan break - not inside any loop or switch
                return StructuredComment.EMPTY_COMMENT;
            }
        }
        in.transformStructuredChildren(this, scope);
        return in;
    }

    private static boolean isInsideBreakable(StructuredScope scope) {
        List<StructuredStatement> all = scope.getAll();
        for (StructuredStatement stm : all) {
            if (stm.supportsBreak()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTerminator(StructuredStatement stm) {
        return stm instanceof StructuredReturn
                || stm instanceof StructuredBreak
                || stm instanceof StructuredContinue
                || stm instanceof StructuredThrow;
    }

    private static void removeUnreachableAfterTerminators(List<Op04StructuredStatement> statements) {
        boolean terminated = false;
        int i = 0;
        while (i < statements.size()) {
            StructuredStatement stm = statements.get(i).getStatement();
            if (terminated) {
                // After a terminator (or non-completing switch), only dead terminators
                // can follow in the same basic block. Remove them.
                if (isTerminator(stm)) {
                    statements.remove(i);
                    continue;
                }
                // Non-terminator statement: it might be a label target reachable via goto.
                // Stop removing - we can't be sure it's unreachable.
                terminated = false;
            }
            if (isTerminator(stm)) {
                terminated = true;
            } else if (stm instanceof StructuredSwitch && switchMayNotCompleteNormally((StructuredSwitch) stm)) {
                terminated = true;
            }
            i++;
        }
    }

    /**
     * Returns true if a switch may NOT complete normally (all paths terminate).
     * Simplified check: has default case AND last case body ends with terminator
     * or infinite loop without break.
     */
    private static boolean switchMayNotCompleteNormally(StructuredSwitch switchStatement) {
        StructuredStatement body = switchStatement.getBody().getStatement();
        if (!(body instanceof Block)) return false;
        Block switchBody = (Block) body;

        boolean hasDefault = false;
        StructuredCase lastCase = null;

        List<Op04StructuredStatement> stmts = switchBody.getBlockStatements();
        for (Op04StructuredStatement s : stmts) {
            StructuredStatement stm = s.getStatement();
            if (stm instanceof StructuredCase) {
                StructuredCase cs = (StructuredCase) stm;
                if (cs.isDefault()) hasDefault = true;
                lastCase = cs;
            }
        }

        if (!hasDefault || lastCase == null) return false;

        StructuredStatement caseBody = lastCase.getBody().getStatement();
        boolean result = false;
        if (caseBody instanceof Block) {
            Block caseBlock = (Block) caseBody;
            if (caseBlock.endsWithTerminator()) {
                result = true;
            } else if (!caseBlock.getBlockStatements().isEmpty()) {
                StructuredStatement lastStm = caseBlock.getBlockStatements()
                        .get(caseBlock.getBlockStatements().size() - 1).getStatement();
                if (lastStm instanceof StructuredWhile) {
                    StructuredWhile whileStm = (StructuredWhile) lastStm;
                    if (whileStm.getCondition() == null) {
                        result = !blockContainsLocalBreak(whileStm.getBody().getStatement());
                    }
                }
            }
        } else if (isTerminator(caseBody)) {
            result = true;
        } else if (caseBody instanceof StructuredWhile) {
            StructuredWhile whileStm = (StructuredWhile) caseBody;
            if (whileStm.getCondition() == null) {
                result = !blockContainsLocalBreak(whileStm.getBody().getStatement());
            }
        }
        return result;
    }

    /**
     * Returns true if the statement contains a reachable local break.
     * Skips statements after a terminator (they are unreachable).
     */
    private static boolean blockContainsLocalBreak(StructuredStatement stm) {
        if (stm instanceof StructuredBreak) {
            return ((StructuredBreak) stm).isLocalBreak();
        }
        if (stm instanceof Block) {
            boolean terminated = false;
            for (Op04StructuredStatement s : ((Block) stm).getBlockStatements()) {
                StructuredStatement child = s.getStatement();
                if (terminated) {
                    // Skip unreachable statements after terminator
                    if (isTerminator(child)) continue;
                    terminated = false;
                }
                if (blockContainsLocalBreak(child)) return true;
                if (isTerminator(child)) {
                    terminated = true;
                }
            }
        }
        return false;
    }
}
