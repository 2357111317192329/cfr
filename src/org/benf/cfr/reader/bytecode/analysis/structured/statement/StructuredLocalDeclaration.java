package org.benf.cfr.reader.bytecode.analysis.structured.statement;

import org.benf.cfr.reader.bytecode.analysis.loc.BytecodeLoc;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.StructuredStatementTransformer;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.LocalVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.rewriters.ExpressionRewriter;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.scope.LValueScopeDiscoverer;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.List;

public class StructuredLocalDeclaration extends AbstractStructuredStatement {
    private final LocalVariable localVariable;

    public StructuredLocalDeclaration(LocalVariable localVariable) {
        super(BytecodeLoc.NONE);
        this.localVariable = localVariable;
    }

    public LocalVariable getLocalVariable() {
        return localVariable;
    }

    @Override
    public BytecodeLoc getCombinedLoc() {
        return getLoc();
    }

    @Override
    public Dumper dump(Dumper dumper) {
        if (localVariable.isFinal()) dumper.print("final ");
        LValue.Creation.dump(dumper, localVariable);
        return dumper.endCodeln();
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        collector.collectFrom(localVariable);
    }

    @Override
    public void transformStructuredChildren(StructuredStatementTransformer transformer, StructuredScope scope) {
    }

    @Override
    public void linearizeInto(List<org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement> out) {
        out.add(this);
    }

    @Override
    public void traceLocalVariableScope(LValueScopeDiscoverer scopeDiscoverer) {
    }

    @Override
    public void rewriteExpressions(ExpressionRewriter expressionRewriter) {
    }
}
