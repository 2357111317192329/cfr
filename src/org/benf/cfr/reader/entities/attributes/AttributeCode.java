package org.benf.cfr.reader.entities.attributes;

import org.benf.cfr.reader.bytecode.CodeAnalyser;
import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.NumericTernaryLifter;
import org.benf.cfr.reader.bytecode.analysis.opgraph.op4rewriters.transformers.StructuredStatementTransformer;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredScope;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredReturn;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.Block;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredBreak;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.StructuredContinue;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.UnstructuredContinue;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.BlockIdentifier;
import org.benf.cfr.reader.entities.Method;
import org.benf.cfr.reader.entities.constantpool.ConstantPool;
import org.benf.cfr.reader.entities.exceptions.ExceptionTableEntry;
import org.benf.cfr.reader.entityfactories.AttributeFactory;
import org.benf.cfr.reader.entityfactories.ContiguousEntityFactory;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.ClassFileVersion;
import org.benf.cfr.reader.util.ConfusedCFRException;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.output.Dumper;
import org.benf.cfr.reader.util.output.StaticInitializerContext;
import org.benf.cfr.reader.util.output.BlockReferenceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class AttributeCode extends Attribute {
    public static final String ATTRIBUTE_NAME = "Code";

    private static final long OFFSET_OF_MAX_STACK = 0;

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final int maxStack;
    private final int maxLocals;
    private final int codeLength;
    private final List<ExceptionTableEntry> exceptionTableEntries;
    private final AttributeMap attributes;
    private final ConstantPool cp;
    private final ByteData rawData;

    private final CodeAnalyser codeAnalyser;
    private Method method;

    public AttributeCode(ByteData raw, final ConstantPool cp, ClassFileVersion classFileVersion) {
        this.cp = cp;

        long OFFSET_OF_MAX_LOCALS = 2;
        long OFFSET_OF_CODE_LENGTH = 4;
        long OFFSET_OF_CODE = 8;

        int maxStack;
        int maxLocals;
        int codeLength;
        if (classFileVersion.before(ClassFileVersion.JAVA_1_0)) {
            OFFSET_OF_MAX_LOCALS = 1;
            OFFSET_OF_CODE_LENGTH = 2;
            OFFSET_OF_CODE = 4;

            maxStack = raw.getU1At(OFFSET_OF_MAX_STACK);
            maxLocals = raw.getU1At(OFFSET_OF_MAX_LOCALS);
            codeLength = raw.getU2At(OFFSET_OF_CODE_LENGTH);

        } else {
            maxStack = raw.getU2At(OFFSET_OF_MAX_STACK);
            maxLocals = raw.getU2At(OFFSET_OF_MAX_LOCALS);
            long codeLengthL = raw.getU4At(OFFSET_OF_CODE_LENGTH);
            // JVM spec actually says this value must be < 65536 (despite it being of type u4), see https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html#jvms-4.7.3
            if (codeLengthL > Integer.MAX_VALUE) {
                throw new ConfusedCFRException("Unsupported code length: " + codeLengthL);
            }
            codeLength = (int) codeLengthL;
        }
        this.maxStack = maxStack;
        this.maxLocals = maxLocals;
        this.codeLength = codeLength;

        final long OFFSET_OF_EXCEPTION_TABLE_LENGTH = OFFSET_OF_CODE + codeLength;
        final long OFFSET_OF_EXCEPTION_TABLE = OFFSET_OF_EXCEPTION_TABLE_LENGTH + 2;

        ArrayList<ExceptionTableEntry> etis = new ArrayList<ExceptionTableEntry>();
        final int numExceptions = raw.getU2At(OFFSET_OF_EXCEPTION_TABLE_LENGTH);
        etis.ensureCapacity(numExceptions);
        final long numBytesExceptionInfo =
                ContiguousEntityFactory.buildSized(raw.getOffsetData(OFFSET_OF_EXCEPTION_TABLE), numExceptions, 8, etis,
                        ExceptionTableEntry.getBuilder());
        this.exceptionTableEntries = etis;

        final long OFFSET_OF_ATTRIBUTES_COUNT = OFFSET_OF_EXCEPTION_TABLE + numBytesExceptionInfo;
        final long OFFSET_OF_ATTRIBUTES = OFFSET_OF_ATTRIBUTES_COUNT + 2;
        final int numAttributes = raw.getU2At(OFFSET_OF_ATTRIBUTES_COUNT);
        List<Attribute> tmpAttributes = AttributeFactory.readAttributes(raw.getOffsetData(OFFSET_OF_ATTRIBUTES), cp, classFileVersion, numAttributes);
        this.attributes = new AttributeMap(tmpAttributes);

        this.rawData = raw.getOffsetData(OFFSET_OF_CODE);
        this.codeAnalyser = new CodeAnalyser(this);
    }

    public void setMethod(Method method) {
        this.method = method;
        codeAnalyser.setMethod(method);
    }

    public Op04StructuredStatement analyse() {
        return codeAnalyser.getAnalysis(getConstantPool().getDCCommonState());
    }

    public ConstantPool getConstantPool() {
        return cp;
    }

    public AttributeLocalVariableTable getLocalVariableTable() {
        return attributes.getByName(AttributeLocalVariableTable.ATTRIBUTE_NAME);
    }

    public AttributeLineNumberTable getLineNumberTable() {
        return attributes.getByName(AttributeLineNumberTable.ATTRIBUTE_NAME);
    }

    public AttributeStackMapTable getStackMapTable() {
        return attributes.getByName(AttributeStackMapTable.ATTRIBUTE_NAME);
    }

    public AttributeRuntimeVisibleTypeAnnotations getRuntimeVisibleTypeAnnotations() {
        return attributes.getByName(AttributeRuntimeVisibleTypeAnnotations.ATTRIBUTE_NAME);
    }

    public AttributeRuntimeInvisibleTypeAnnotations getRuntimeInvisibleTypeAnnotations() {
        return attributes.getByName(AttributeRuntimeInvisibleTypeAnnotations.ATTRIBUTE_NAME);
    }

    public ByteData getRawData() {
        return rawData;
    }

    public List<ExceptionTableEntry> getExceptionTableEntries() {
        return exceptionTableEntries;
    }

    public int getMaxLocals() {
        return maxLocals;
    }

    public int getCodeLength() {
        return codeLength;
    }

    @Override
    public Dumper dump(Dumper d) {
        Op04StructuredStatement analysis = codeAnalyser.getAnalysis(getConstantPool().getDCCommonState());
        new NumericTernaryLifter().transform(analysis);
        boolean staticInitializer = method != null && method.getConstructorFlag() == Method.MethodConstructor.STATIC_CONSTRUCTOR;
        if (!staticInitializer) return analysis.dump(d);

        final List<StructuredStatement> statements = new ArrayList<StructuredStatement>();
        analysis.transform(new StructuredStatementTransformer() {
            @Override
            public StructuredStatement transform(StructuredStatement in, StructuredScope scope) {
                statements.add(in);
                in.transformStructuredChildren(this, scope);
                return in;
            }
        }, new StructuredScope());
        boolean hasVoidReturn = false;
        Set<BlockIdentifier> referencedBlocks = new HashSet<BlockIdentifier>();
        Set<BlockIdentifier> continueTargets = new HashSet<BlockIdentifier>();
        for (StructuredStatement statement : statements) {
            if (statement instanceof StructuredReturn && ((StructuredReturn) statement).getValue() == null) {
                hasVoidReturn = true;
            } else if (statement instanceof StructuredBreak && !((StructuredBreak) statement).isLocalBreak()) {
                referencedBlocks.add(((StructuredBreak) statement).getBreakBlock());
            } else if (statement instanceof StructuredContinue && !((StructuredContinue) statement).isLocalContinue()) {
                referencedBlocks.add(((StructuredContinue) statement).getContinueTgt());
            } else if (statement instanceof UnstructuredContinue) {
                referencedBlocks.add(((UnstructuredContinue) statement).getContinueTgt());
            } else if (statement instanceof Block) {
                BlockIdentifier block = ((Block) statement).getBlockIdentifier();
                if (block != null && block.getBlockType().isLoop()) continueTargets.add(block);
            }
        }

        try (BlockReferenceContext labels = BlockReferenceContext.enter(referencedBlocks, continueTargets)) {
            if (!staticInitializer) return analysis.dump(d);
            try (StaticInitializerContext ignored = StaticInitializerContext.enter(method.getClassFile().getClassType(), hasVoidReturn)) {
                if (hasVoidReturn) {
                    d.separator("{").newln().indent(1);
                    d.print(StaticInitializerContext.EXIT_LABEL).print(": ");
                    analysis.dump(d);
                    d.indent(-1);
                    d.separator("}").newln();
                    return d;
                }
                return analysis.dump(d);
            }
        }
    }

    @Override
    public String getRawName() {
        return ATTRIBUTE_NAME;
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        attributes.collectTypeUsages(collector);
    }

    public void releaseCode() {
        this.codeAnalyser.releaseCode();
    }

    public AttributeMap getAttributes() {
        return attributes;
    }
}
