package org.benf.cfr.reader.entities.classfilehelpers;

import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ConstructorInvokationAnonymousInner;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.ConstructorInvokationSimple;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.StaticFunctionInvokation;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.StaticVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.bytecode.analysis.types.ClassSignature;
import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.entities.*;
import org.benf.cfr.reader.state.DCCommonState;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.util.CannotLoadClassException;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.List;

/**
 * This isn't static - we populate it from the decoded enum information.
 */
public class ClassFileDumperEnum extends AbstractClassFileDumper {
    private static final AccessFlag[] dumpableAccessFlagsEnum = new AccessFlag[]{
            AccessFlag.ACC_PUBLIC, AccessFlag.ACC_PRIVATE, AccessFlag.ACC_PROTECTED, AccessFlag.ACC_STRICT, AccessFlag.ACC_STATIC
    };

    private final List<Pair<StaticVariable, Expression>> entries;
    private final DCCommonState dcCommonState;

    public ClassFileDumperEnum(DCCommonState dcCommonState, List<Pair<StaticVariable, Expression>> entries) {
        super(dcCommonState);
        this.dcCommonState = dcCommonState;
        this.entries = entries;
    }

    private static void dumpHeader(ClassFile c, InnerClassDumpType innerClassDumpType, Dumper d) {
        d.print(getAccessFlagsString(c.getAccessFlags(), dumpableAccessFlagsEnum));

        d.print("enum ").dump(c.getThisClassConstpoolEntry().getTypeInstance()).print(" ");

        ClassSignature signature = c.getClassSignature();
        List<JavaTypeInstance> interfaces = signature.getInterfaces();
        if (!interfaces.isEmpty()) {
            d.print("implements ");
            int size = interfaces.size();
            for (int x = 0; x < size; ++x) {
                JavaTypeInstance iface = interfaces.get(x);
                d.dump(iface).print((x < (size - 1) ? "," : "")).newln();
            }
        }
    }

    private void dumpEntry(Dumper d, Pair<StaticVariable, Expression> entry, boolean last, ClassFile enumClass) {
        JavaTypeInstance classType = enumClass.getClassType();
        StaticVariable staticVariable = entry.getFirst();
        Expression constructorInvokation = entry.getSecond();
        d.fieldName(staticVariable.getFieldName(), classType, false, true, true);

        if (constructorInvokation instanceof StaticFunctionInvokation && dumpConstantSpecificClass(d, (StaticFunctionInvokation)constructorInvokation, enumClass)) {
            // The bridge method returns the enum constant's concrete subclass.
        } else if (constructorInvokation instanceof ConstructorInvokationSimple) {
            List<Expression> args = ((ConstructorInvokationSimple)constructorInvokation).getArgs();
            ConstructorInvokationSimple simple = (ConstructorInvokationSimple) constructorInvokation;
            if (!dumpConstantSpecificClass(d, simple.getConstructionType(), args, enumClass) && args.size() > 2) {
                d.separator("(");
                for (int x = 2, len = args.size(); x < len; ++x) {
                    if (x > 2) d.print(", ");
                    d.dump(args.get(x));
                }
                d.separator(")");
            }
        } else if (constructorInvokation instanceof ConstructorInvokationAnonymousInner) {
            ((ConstructorInvokationAnonymousInner) constructorInvokation).dumpForEnum(d);
        }
        if (last) {
            d.endCodeln();
        } else {
            d.print(",").newln();
        }
    }

    private boolean dumpConstantSpecificClass(Dumper d, StaticFunctionInvokation bridge, ClassFile enumClass) {
        List<Expression> args = bridge.getArgs();
        return dumpConstantSpecificClass(d, bridge.getMethodPrototype().getReturnType(), args, enumClass);
    }

    private boolean dumpConstantSpecificClass(Dumper d, JavaTypeInstance concreteType, List<Expression> args, ClassFile enumClass) {
        JavaTypeInstance enumType = enumClass.getClassType();
        if (enumType.equals(concreteType)) return false;
        ClassFile concreteClass;
        try {
            concreteClass = dcCommonState.getClassFile(concreteType.getDeGenerifiedType());
        } catch (CannotLoadClassException e) {
            return false;
        }
        if (!concreteClass.getBindingSupers().containsBase(enumType)) return false;
        List<Expression> constructorArgs = args.size() >= 2 ? args.subList(2, args.size()) : args;
        new ClassFileDumperAnonymousInner().dumpWithArgs(concreteClass, null, constructorArgs, true, enumClass, d);
        return true;
    }

    @Override
    public Dumper dump(ClassFile classFile, InnerClassDumpType innerClass, Dumper d) {

        if (!innerClass.isInnerClass()) {
            dumpTopHeader(classFile, d, true);
            dumpImports(d, classFile);
        }

        dumpComments(classFile, d);
        dumpAnnotations(classFile, d);
        dumpHeader(classFile, innerClass, d);
        d.separator("{").newln();
        d.indent(1);

        JavaTypeInstance classType = classFile.getClassType();

        for (int x = 0, len = entries.size(); x < len; ++x) {
            dumpEntry(d, entries.get(x), (x == len - 1), classFile);
        }
        if (entries.isEmpty()) {
            boolean hasVisibleMembers = false;
            for (ClassFileField field : classFile.getFields()) {
                if (!field.shouldNotDisplay()) {
                    hasVisibleMembers = true;
                    break;
                }
            }
            if (!hasVisibleMembers) {
                for (Method method : classFile.getMethods()) {
                    if (method.hiddenState() == Method.Visibility.Visible) {
                        hasVisibleMembers = true;
                        break;
                    }
                }
            }
            if (hasVisibleMembers) d.endCodeln();
        }

        d.newln();

        List<ClassFileField> fields = classFile.getFields();
        for (ClassFileField field : fields) {
            if (field.shouldNotDisplay()) continue;
            field.dump(d, classFile);
        }
        List<Method> methods = classFile.getMethods();
        if (!methods.isEmpty()) {
            for (Method method : methods) {
                if (method.hiddenState() != Method.Visibility.Visible) continue;
                d.newln();
                method.dump(d, true);
            }
        }
        classFile.dumpNamedInnerClasses(d);
        d.indent(-1);
        d.print("}").newln();

        return d;
    }

    @Override
    public void collectTypeUsages(TypeUsageCollector collector) {
        for (Pair<StaticVariable, Expression> entry : entries) {
            collector.collectFrom(entry.getFirst());
            collector.collectFrom(entry.getSecond());
        }
    }
}
