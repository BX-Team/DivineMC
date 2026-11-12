package org.bxteam.divinemc.util;

import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NullMarked;

/**
 * A per-thread boolean that skips the thread-local map lookup while no thread has it set.
 */
@NullMarked
public final class ThreadLocalFlag extends ThreadLocal<Boolean> {
    private final AtomicInteger setThreads = new AtomicInteger();

    @Override
    protected Boolean initialValue() {
        return Boolean.FALSE;
    }

    @Override
    public Boolean get() {
        // a thread that set the flag counted itself first, so a zero count means this thread's value is false
        if (this.setThreads.get() == 0) {
            return Boolean.FALSE;
        }
        return super.get();
    }

    @Override
    public void set(final Boolean value) {
        final boolean newValue = value;
        if (super.get() == newValue) {
            return;
        }
        if (newValue) {
            this.setThreads.incrementAndGet();
            super.set(Boolean.TRUE);
        } else {
            super.set(Boolean.FALSE);
            this.setThreads.decrementAndGet();
        }
    }

    @Override
    public void remove() {
        if (super.get()) {
            this.setThreads.decrementAndGet();
        }
        super.remove();
    }
}
