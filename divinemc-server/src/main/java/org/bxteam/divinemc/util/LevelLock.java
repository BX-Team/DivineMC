package org.bxteam.divinemc.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.bxteam.divinemc.async.LevelThreads;
import org.jspecify.annotations.NullMarked;

/**
 * A lock for per-level state that only needs one while {@link LevelThreads#SHARED} is set; otherwise it does nothing.
 */
@NullMarked
public final class LevelLock {
    private static final Lock NONE = new Lock() {
        @Override
        public void lock() {
        }

        @Override
        public void lockInterruptibly() {
        }

        @Override
        public boolean tryLock() {
            return true;
        }

        @Override
        public boolean tryLock(final long time, final TimeUnit unit) {
            return true;
        }

        @Override
        public void unlock() {
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    };

    private LevelLock() {
    }

    public static Lock create() {
        return LevelThreads.SHARED ? new ReentrantLock() : NONE;
    }
}
