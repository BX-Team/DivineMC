package org.bxteam.divinemc.async.pwt;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.jspecify.annotations.NullMarked;

/**
 * The thread that ticks one level under parallel world ticking. It carries that level's mid-tick chunk task backoff,
 * which the server keeps in a single pair of fields when levels tick serially, and the other levels it has taken over.
 */
@NullMarked
public final class LevelTickThread extends TickThread.ServerLevelTickThread {
    public final long[] midTickBackoff = new long[2];
    public final LevelHandoff.Holder handoff = new LevelHandoff.Holder();

    public LevelTickThread(final Runnable run, final String name) {
        super(run, name);
    }
}
