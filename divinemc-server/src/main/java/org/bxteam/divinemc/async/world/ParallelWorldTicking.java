package org.bxteam.divinemc.async.world;

import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;

/**
 * Boot-time state of parallel world ticking. The option only takes effect on startup, so the value is frozen when
 * this class initializes and every guard on it folds away when it is off.
 */
@NullMarked
public final class ParallelWorldTicking {
    public static final boolean ENABLED = DivineConfig.AsyncCategory.enableParallelWorldTicking;
    public static final Throwable FIRST_READ = new Throwable("first read of the parallel world ticking flag");

    private ParallelWorldTicking() {
    }
}
