package org.benf.cfr.reader.util.output;

import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;

/** Context needed when emitting constructs that Java forbids in a static initializer. */
public final class StaticInitializerContext implements AutoCloseable {
    public static final String EXIT_LABEL = "cfr_static_init_exit";

    private static final ThreadLocal<StaticInitializerContext> CURRENT = new ThreadLocal<StaticInitializerContext>();

    private final StaticInitializerContext previous;
    private final JavaTypeInstance owner;
    private final boolean hasReturn;

    private StaticInitializerContext(JavaTypeInstance owner, boolean hasReturn) {
        this.previous = CURRENT.get();
        this.owner = owner;
        this.hasReturn = hasReturn;
        CURRENT.set(this);
    }

    public static StaticInitializerContext enter(JavaTypeInstance owner, boolean hasReturn) {
        return new StaticInitializerContext(owner, hasReturn);
    }

    public static boolean hasExitLabel() {
        StaticInitializerContext current = CURRENT.get();
        return current != null && current.hasReturn;
    }

    public static boolean owns(JavaTypeInstance owner) {
        StaticInitializerContext current = CURRENT.get();
        return current != null && current.owner.equals(owner);
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
