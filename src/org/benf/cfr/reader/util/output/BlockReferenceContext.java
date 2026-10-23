package org.benf.cfr.reader.util.output;

import org.benf.cfr.reader.bytecode.analysis.parse.utils.BlockIdentifier;

import java.util.Set;

/** Tracks non-local control-flow targets while a method body is emitted. */
public final class BlockReferenceContext implements AutoCloseable {
    private static final ThreadLocal<BlockReferenceContext> CURRENT = new ThreadLocal<BlockReferenceContext>();

    private final BlockReferenceContext previous;
    private final Set<BlockIdentifier> referencedBlocks;
    private final Set<BlockIdentifier> continueTargets;

    private BlockReferenceContext(Set<BlockIdentifier> referencedBlocks, Set<BlockIdentifier> continueTargets) {
        this.previous = CURRENT.get();
        this.referencedBlocks = referencedBlocks;
        this.continueTargets = continueTargets;
        CURRENT.set(this);
    }

    public static BlockReferenceContext enter(Set<BlockIdentifier> referencedBlocks, Set<BlockIdentifier> continueTargets) {
        return new BlockReferenceContext(referencedBlocks, continueTargets);
    }

    public static boolean isReferenced(BlockIdentifier block) {
        BlockReferenceContext current = CURRENT.get();
        return current != null && current.referencedBlocks.contains(block);
    }

    public static boolean isValidContinueTarget(BlockIdentifier block) {
        BlockReferenceContext current = CURRENT.get();
        return current != null && current.continueTargets.contains(block);
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
