package org.bxteam.divinemc.async;

import org.jspecify.annotations.NullMarked;

import java.util.concurrent.atomic.AtomicInteger;

// Paper keeps these hopper flags in static fields; with PWT and RCT several threads move hopper items at once
@NullMarked
public final class HopperEventFlags {
    private static final ThreadLocal<HopperEventFlags> CURRENT = ThreadLocal.withInitial(HopperEventFlags::new);
    private static final AtomicInteger THREADS_IGNORING_UPDATES = new AtomicInteger();

    public boolean skipPullModeEventFire;
    public boolean skipPushModeEventFire;
    private boolean ignoreBlockEntityUpdates;

    private HopperEventFlags() {
    }

    public static HopperEventFlags get() {
        return CURRENT.get();
    }

    public static void ignoreBlockEntityUpdates(boolean ignore) {
        HopperEventFlags flags = CURRENT.get();
        if (flags.ignoreBlockEntityUpdates == ignore) {
            return;
        }
        flags.ignoreBlockEntityUpdates = ignore;
        if (ignore) {
            THREADS_IGNORING_UPDATES.incrementAndGet();
        } else {
            THREADS_IGNORING_UPDATES.decrementAndGet();
        }
    }

    public static boolean ignoreBlockEntityUpdates() {
        // setChanged() is hot: only look at the thread local while some thread is inside a hopper slot write
        return THREADS_IGNORING_UPDATES.get() != 0 && CURRENT.get().ignoreBlockEntityUpdates;
    }
}
