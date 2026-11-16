package org.bxteam.divinemc.async.rct;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.bxteam.divinemc.async.pwt.LevelHandoff;
import org.bxteam.divinemc.async.pwt.ParallelWorldTicking;
import org.bxteam.divinemc.concurrent.lock.ServerStateLock;
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
        // the chunk tasks fire events on the level's thread, whose listeners take this lock
        final int stateLockHolds = ServerStateLock.releaseForWait();
        try {
            chunkSource.blockedRegionWorkers.incrementAndGet();
            BLOCKED.incrementAndGet();
            // the level's thread may itself be waiting for another level, whose tick needs a pool thread
            if (ParallelWorldTicking.ENABLED) {
                RegionizedChunkTicking.beginBlocking();
            }
            try {
                for (final Thread joiner : JOINERS) {
                    LockSupport.unpark(joiner);
                }
                while (!condition.getAsBoolean()) {
                    LockSupport.parkNanos(chunkSource, POLL_NANOS);
                }
            } finally {
                if (ParallelWorldTicking.ENABLED) {
                    RegionizedChunkTicking.endBlocking();
                }
                BLOCKED.decrementAndGet();
                chunkSource.blockedRegionWorkers.decrementAndGet();
            }
        } finally {
            ServerStateLock.reacquireAfterWait(stateLockHolds);
        }
    }

    public static void join(final ServerChunkCache chunkSource, final CompletableFuture<?> future) {
        if (!future.isDone()) {
            final Thread thread = Thread.currentThread();
            JOINERS.add(thread);
            if (ParallelWorldTicking.ENABLED) {
                LevelHandoff.beginJoin((ServerLevel) chunkSource.getLevel());
            }
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
                if (ParallelWorldTicking.ENABLED) {
                    LevelHandoff.endJoin();
                }
                JOINERS.remove(thread);
            }
        }
        future.join();
    }

    private static boolean runTasks(final ServerChunkCache chunkSource) {
        if (ParallelWorldTicking.ENABLED) {
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
