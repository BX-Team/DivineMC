package org.bxteam.divinemc.concurrent.lock;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.bxteam.divinemc.async.LevelTickCounters;
import org.bxteam.divinemc.async.pwt.LevelHandoff;
import org.bxteam.divinemc.async.pwt.ParallelWorldTicking;
import org.bxteam.divinemc.async.rct.NeighborChainLock;
import org.bxteam.divinemc.async.rct.RegionizedChunkTicking;
import org.bxteam.divinemc.concurrent.LevelThreads;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes writes to server-wide state that levels ticking in parallel reach from their own threads: command
 * execution (scoreboard, command storage, boss bars, functions), scoreboard criteria and loot random sequences.
 * {@link #lock} and {@link #lockLendable} do nothing unless levels are shared: region workers and the parallel sensor
 * pool reach the same state from their tick (scoreboard criteria of players and mobs, loot random sequences).
 * {@link ListenerSerialization} also takes it under regionized chunk ticking.
 * <p>
 * A level thread that waits for the lock in {@link #lockLendable} or {@link #lockAlways} (a command or a plugin
 * listener is about to run) lends its levels to the holder, see {@link LevelHandoff}.
 */
@NullMarked
public final class ServerStateLock {
    private static final ReentrantLock LOCK = new ReentrantLock();

    private ServerStateLock() {
    }

    public static void lock() {
        if (ParallelWorldTicking.ENABLED) {
            acquire(false);
        } else if (LevelThreads.SHARED) {
            lockWaiting();
        }
    }

    public static void lockLendable() {
        if (ParallelWorldTicking.ENABLED) {
            acquire(true);
        } else if (LevelThreads.SHARED) {
            lockWaiting();
        }
    }

    public static void unlock() {
        if (ParallelWorldTicking.ENABLED) {
            release();
        } else if (LevelThreads.SHARED) {
            LOCK.unlock();
        }
    }

    public static void lockAlways() {
        if (ParallelWorldTicking.ENABLED) {
            acquire(true);
        } else {
            lockWaiting();
        }
    }

    private static void lockWaiting() {
        if (LOCK.tryLock()) {
            return;
        }
        final long start = System.nanoTime();
        final int chainHolds = NeighborChainLock.releaseForWait();
        try {
            LOCK.lock();
        } finally {
            NeighborChainLock.reacquireAfterWait(chainHolds);
            countWait(start);
        }
    }

    public static void unlockAlways() {
        if (ParallelWorldTicking.ENABLED) {
            release();
        } else {
            LOCK.unlock();
        }
    }

    /**
     * Fully releases the lock if the current thread holds it, before that thread waits for another level or for
     * another thread to run chunk tasks; whoever it waits for may need the lock to get there. Levels borrowed for the
     * lock stay borrowed until the outermost unlock.
     *
     * @return the hold count to pass to {@link #reacquireAfterWait}
     */
    public static int releaseForWait() {
        if (!LOCK.isHeldByCurrentThread()) {
            return 0;
        }
        final int holds = LOCK.getHoldCount();
        if (ParallelWorldTicking.ENABLED) {
            LevelHandoff.stateLockSuspending();
        }
        for (int i = 0; i < holds; i++) {
            LOCK.unlock();
        }
        return holds;
    }

    public static void reacquireAfterWait(final int holds) {
        if (holds == 0) {
            return;
        }
        if (ParallelWorldTicking.ENABLED) {
            acquire(true);
        } else {
            lockWaiting();
        }
        for (int i = 1; i < holds; i++) {
            LOCK.lock();
        }
    }

    public static boolean isHeldByCurrentThread() {
        return LOCK.isHeldByCurrentThread();
    }

    private static void acquire(final boolean lendable) {
        final Thread current = Thread.currentThread();
        if (!LOCK.tryLock()) {
            waitForLock(current, lendable);
        } else if (LOCK.getHoldCount() == 1 && LevelHandoff.hasLentLevels(current)) {
            LOCK.unlock();
            waitForLock(current, lendable);
        }
        if (LOCK.getHoldCount() == 1) {
            LevelHandoff.stateLockAcquired(current);
        }
    }

    private static void waitForLock(final Thread current, final boolean lendable) {
        final long start = System.nanoTime();
        try {
            waitForLockTimed(current, lendable);
        } finally {
            countWait(start);
        }
    }

    private static void countWait(final long start) {
        final @Nullable LevelTickCounters counters = LevelTickCounters.ofCurrentThread();
        if (counters != null) {
            counters.addServerStateLockWait(System.nanoTime() - start);
        }
    }

    private static void waitForLockTimed(final Thread current, final boolean lendable) {
        final boolean registered = LevelHandoff.waitForStateLock(lendable);
        final boolean worker = registered && current instanceof TickThread.RegionTickThread;
        if (worker) {
            RegionizedChunkTicking.beginBlocking();
        }
        boolean finished = !registered;
        final int chainHolds = NeighborChainLock.releaseForWait();
        try {
            while (true) {
                if (registered) {
                    LevelHandoff.awaitLentReturned();
                }
                LOCK.lock();
                // a level lent to a holder that let the lock go mid-operation is still in use there
                if (finished || LevelHandoff.finishStateLockWait()) {
                    finished = true;
                    return;
                }
                LOCK.unlock();
            }
        } finally {
            NeighborChainLock.reacquireAfterWait(chainHolds);
            if (worker) {
                RegionizedChunkTicking.endBlocking();
            }
            if (!finished) {
                LevelHandoff.stopWaitingForStateLock();
            }
        }
    }

    private static void release() {
        if (LOCK.getHoldCount() == 1) {
            LevelHandoff.stateLockReleasing(Thread.currentThread());
        }
        LOCK.unlock();
    }
}
