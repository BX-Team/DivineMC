package org.bxteam.divinemc.concurrent;

import org.bxteam.divinemc.async.pwt.ParallelWorldTicking;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;

/**
 * Whether a level may be touched by more than one thread while it ticks: by several levels ticking at once, by
 * region workers, or by the parallel sensor pool. Frozen at startup like the options it is made of; structures that
 * only need locking for these features pick their plain upstream form when it is false.
 */
@NullMarked
public final class LevelThreads {
    public static final boolean SHARED = ParallelWorldTicking.ENABLED
        || DivineConfig.AsyncCategory.enableRegionizedChunkTicking
        || DivineConfig.AsyncCategory.parallelSensorsEnabled;
    private static final Throwable FIRST_READ = new Throwable("first read of the shared level flag");

    private LevelThreads() {
    }

    /**
     * Fails the boot if one of the frozen flags was read before the configuration was loaded.
     */
    public static void verifyFrozenFlags() {
        final boolean expected = DivineConfig.AsyncCategory.enableParallelWorldTicking
            || DivineConfig.AsyncCategory.enableRegionizedChunkTicking
            || DivineConfig.AsyncCategory.parallelSensorsEnabled;
        if (ParallelWorldTicking.ENABLED != DivineConfig.AsyncCategory.enableParallelWorldTicking || SHARED != expected) {
            final IllegalStateException failure = new IllegalStateException("Async feature flags were read before divinemc.yml was loaded");
            failure.addSuppressed(ParallelWorldTicking.FIRST_READ);
            failure.addSuppressed(FIRST_READ);
            throw failure;
        }
    }
}
