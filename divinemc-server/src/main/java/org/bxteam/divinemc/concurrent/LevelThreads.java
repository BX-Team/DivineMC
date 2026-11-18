package org.bxteam.divinemc.concurrent;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bxteam.divinemc.async.pwt.LevelHandoff;
import org.bxteam.divinemc.async.pwt.ParallelWorldTicking;
import org.bxteam.divinemc.async.rct.RegionizedChunkTicking;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

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
     * The level whose tick the current thread runs: a level's own tick thread, a region worker inside its level, or
     * the server thread while it ticks a level serially. Levels taken over from other levels are not counted.
     */
    public static @Nullable ServerLevel current() {
        final Thread thread = Thread.currentThread();
        if (thread instanceof TickThread.ServerLevelTickThread levelThread) {
            return levelThread.currentlyTickingServerLevel;
        }
        if (thread instanceof TickThread.RegionTickThread worker) {
            return worker.currentlyTickingServerLevel;
        }
        final MinecraftServer server = MinecraftServer.getServer();
        return server != null && thread == server.getRunningThread() ? server.serialTickingLevel : null;
    }

    /**
     * Whether the current thread may change the given chunks of {@code level}, as the Folia region API asks it. Same
     * as {@link TickThread#isTickThreadFor(net.minecraft.world.level.Level)}, except on a region worker: there only
     * the chunks of the unit it runs belong to it, and another level only once it has taken that level over.
     */
    public static boolean ownsChunks(final ServerLevel level, final int minChunkX, final int minChunkZ, final int maxChunkX, final int maxChunkZ) {
        if (Thread.currentThread() instanceof TickThread.RegionTickThread worker) {
            if (worker.currentlyTickingServerLevel == level) {
                return RegionizedChunkTicking.currentUnitContains(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
            }
            return ParallelWorldTicking.ENABLED && LevelHandoff.holds(worker, level);
        }
        return TickThread.isTickThreadFor(level);
    }

    /**
     * Whether the current thread may change anything in {@code level}; a region worker never owns its own level as a
     * whole, since other workers tick the rest of it.
     */
    public static boolean ownsLevel(final ServerLevel level) {
        if (Thread.currentThread() instanceof TickThread.RegionTickThread worker) {
            return worker.currentlyTickingServerLevel != level && ParallelWorldTicking.ENABLED && LevelHandoff.holds(worker, level);
        }
        return TickThread.isTickThreadFor(level);
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
