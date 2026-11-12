package org.bxteam.divinemc.async.rct;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Keeps the chunk system's main-thread tasks on the thread that owns the level while region workers run: a worker
 * that needs a chunk waits in {@link #block}, and the level's thread, itself waiting for the workers in
 * {@link #join}, runs the tasks for it.
 */
@NullMarked
public final class RegionWorkerWait {
    private static final long POLL_NANOS = 100_000L;
    private static final Set<Thread> JOINERS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger BLOCKED = new AtomicInteger();

    private RegionWorkerWait() {
    }

    public static void block(final ServerChunkCache chunkSource, final BooleanSupplier condition) {
        chunkSource.blockedRegionWorkers.incrementAndGet();
        BLOCKED.incrementAndGet();
        try {
            for (final Thread joiner : JOINERS) {
                LockSupport.unpark(joiner);
            }
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(chunkSource, POLL_NANOS);
            }
        } finally {
            BLOCKED.decrementAndGet();
            chunkSource.blockedRegionWorkers.decrementAndGet();
        }
    }

    public static void join(final ServerChunkCache chunkSource, final CompletableFuture<?> future) {
        if (!future.isDone()) {
            final Thread thread = Thread.currentThread();
            JOINERS.add(thread);
            try {
                future.whenComplete((result, throwable) -> LockSupport.unpark(thread));
                while (!future.isDone()) {
                    if (BLOCKED.get() == 0) {
                        LockSupport.park(future);
                    } else if (!runTasks(chunkSource)) {
                        LockSupport.parkNanos(future, POLL_NANOS);
                    }
                }
            } finally {
                JOINERS.remove(thread);
            }
        }
        future.join();
    }

    private static boolean runTasks(final ServerChunkCache chunkSource) {
        if (DivineConfig.AsyncCategory.enableParallelWorldTicking) {
            return chunkSource.blockedRegionWorkers.get() > 0 && chunkSource.pollTask();
        }
        // without parallel world ticking this thread owns every level, and a worker can wait on another one (portals)
        boolean ran = false;
        for (final ServerLevel level : ((ServerLevel) chunkSource.getLevel()).getServer().getAllLevels()) {
            final ServerChunkCache other = level.getChunkSource();
            if (other.blockedRegionWorkers.get() > 0) {
                ran |= other.pollTask();
            }
        }
        return ran;
    }
}
