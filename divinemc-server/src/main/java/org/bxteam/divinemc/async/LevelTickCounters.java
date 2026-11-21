package org.bxteam.divinemc.async;

import net.minecraft.server.level.ServerLevel;
import org.bxteam.divinemc.concurrent.LevelThreads;
import org.bxteam.divinemc.world.WorldTickStatistics;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters behind {@link WorldTickStatistics}. They are only touched where a thread waits or a level changes hands,
 * and once per tick by regionized chunk ticking, never per entity or per block.
 */
@NullMarked
public final class LevelTickCounters {
    private final AtomicLong crossWorldWaitNanos = new AtomicLong();
    private final AtomicLong tickStartDelayNanos = new AtomicLong();
    private final AtomicLong takeOverCount = new AtomicLong();
    private final AtomicLong serverStateLockWaitNanos = new AtomicLong();
    private volatile int regionizedUnitCount;
    private volatile long regionizedTickNanos;

    public static @Nullable LevelTickCounters ofCurrentThread() {
        final ServerLevel level = LevelThreads.current();
        return level == null ? null : level.tickCounters;
    }

    public void addCrossWorldWait(final long nanos) {
        this.crossWorldWaitNanos.addAndGet(nanos);
    }

    public void addTickStartDelay(final long nanos) {
        this.tickStartDelayNanos.addAndGet(nanos);
    }

    public void countTakeOver() {
        this.takeOverCount.incrementAndGet();
    }

    public void addServerStateLockWait(final long nanos) {
        this.serverStateLockWaitNanos.addAndGet(nanos);
    }

    public void setRegionizedTick(final int units, final long nanos) {
        this.regionizedUnitCount = units;
        this.regionizedTickNanos = nanos;
    }

    public WorldTickStatistics snapshot() {
        return new WorldTickStatistics(
            this.crossWorldWaitNanos.get(),
            this.tickStartDelayNanos.get(),
            this.takeOverCount.get(),
            this.serverStateLockWaitNanos.get(),
            this.regionizedUnitCount,
            this.regionizedTickNanos
        );
    }
}
