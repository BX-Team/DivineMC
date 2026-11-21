package org.bxteam.divinemc.concurrent.state;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.jspecify.annotations.NullMarked;

/**
 * The "the move event doesn't have to be fired again" state of the hopper transfer a thread is in the middle of.
 * Paper keeps it in statics, which worlds and regions ticking in parallel overwrite for each other.
 */
@NullMarked
public final class HopperEventFlags {
    private static final HopperEventFlags SHARED = new HopperEventFlags();

    public boolean skipPull;
    public boolean skipPush;

    public static HopperEventFlags current() {
        return Thread.currentThread() instanceof TickThread thread ? thread.hopperEventFlags : SHARED;
    }
}
