package org.bxteam.divinemc.async.rct;

import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import ca.spottedleaf.moonrise.common.util.ThreadUnsafeRandom;
import ca.spottedleaf.moonrise.common.util.TickThread;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.world.level.Level;
import org.bxteam.divinemc.concurrent.LevelThreads;
import org.bxteam.divinemc.concurrent.state.ThreadState;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The randoms one region unit draws from while a worker ticks it, in place of the level's and the shared entity
 * random, which are plain unsynchronized seeds: two workers advancing one of them at once draw the same value. The
 * level's thread reseeds every unit from one draw of the level random before a phase, so the same partition draws
 * the same values whatever worker runs which unit.
 */
@NullMarked
public final class UnitRandoms {
    private final Level level;
    final ThreadUnsafeRandom levelRandom = new ThreadUnsafeRandom(0L);
    final ThreadUnsafeRandom entityRandom = new ThreadUnsafeRandom(0L);
    private final SimpleThreadUnsafeRandom simpleRandom = new SimpleThreadUnsafeRandom(0L);
    private int randValue;

    UnitRandoms(final Level level) {
        this.level = level;
    }

    void reseed(final long phaseSeed, final long unitKey) {
        final long seed = HashCommon.mix(phaseSeed ^ HashCommon.mix(unitKey));
        this.levelRandom.setSeed(seed);
        this.entityRandom.setSeed(HashCommon.mix(seed + 1L));
        this.simpleRandom.setSeed(HashCommon.mix(seed + 2L));
        this.randValue = (int) HashCommon.mix(seed + 3L);
    }

    /**
     * Same LCG as {@code Level.getBlockRandomPos}.
     */
    public int nextBlockRandom() {
        this.randValue = this.randValue * 3 + 1013904223;
        return this.randValue >> 2;
    }

    /**
     * @return the unit the calling region worker is ticking in {@code level}, or null on any other thread
     */
    public static @Nullable UnitRandoms current(final Level level) {
        return Thread.currentThread() instanceof TickThread.RegionTickThread worker
            && worker.threadState.unitRandoms instanceof UnitRandoms unit
            && unit.level == level ? unit : null;
    }

    public static SimpleThreadUnsafeRandom simpleRandom(final Level level, final SimpleThreadUnsafeRandom levelOwn) {
        if (!LevelThreads.SHARED) {
            return levelOwn;
        }
        final UnitRandoms unit = current(level);
        return unit != null ? unit.simpleRandom : levelOwn;
    }

    /**
     * The random that replaces {@code Entity.SHARED_RANDOM} on the calling thread, or null to use it: tick threads run
     * entities of different levels or units at once, other threads (world generation) keep upstream's shared random.
     */
    public static @Nullable ThreadUnsafeRandom entityRandom() {
        if (!LevelThreads.SHARED || !(Thread.currentThread() instanceof TickThread thread)) {
            return null;
        }
        final ThreadState state = thread.threadState;
        final UnitRandoms unit = state.unitRandoms;
        return unit != null ? unit.entityRandom : state.entityRandom();
    }
}
