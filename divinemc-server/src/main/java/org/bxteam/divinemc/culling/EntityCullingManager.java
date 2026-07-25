package org.bxteam.divinemc.culling;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Raytrace entity culling (server-side port of tr7zw's EntityCulling): untracks entities a
 * player provably cannot see, cutting tracker bandwidth and blinding entity-ESP cheats.
 *
 * <p>One {@link PlayerCullTracker} per real player, all running on a single bounded scheduled
 * pool with a fixed delay, so passes for one player never overlap and thread count does not
 * scale with player count.
 */
public final class EntityCullingManager {
    static final Logger LOGGER = LogManager.getLogger("EntityCulling");

    private static final Set<PlayerCullTracker> TRACKERS = ConcurrentHashMap.newKeySet();
    private static volatile ScheduledThreadPoolExecutor pool;

    private EntityCullingManager() { }

    public static boolean isCulled(ServerPlayer player, Entity entity) {
        if (!DivineConfig.NetworkCategory.raytraceCullingEnabled) {
            return false;
        }
        final PlayerCullTracker tracker = player.cullTracker;
        return tracker != null && tracker.isCulled(entity.getId());
    }

    public static void tickPlayer(ServerPlayer player) {
        if (!DivineConfig.NetworkCategory.raytraceCullingEnabled || !player.isRealPlayer) {
            return;
        }
        final PlayerCullTracker tracker = player.cullTracker;
        if (tracker == null) {
            register(player);
        } else if (tracker.pollCulledChanged()) {
            refreshWaypoints(player);
        }
    }

    private static void refreshWaypoints(ServerPlayer player) {
        if (player.connection == null || !player.isReceivingWaypoints()) {
            return;
        }
        final net.minecraft.server.waypoints.ServerWaypointManager waypoints = player.level().getWaypointManager();
        if (waypoints.locatorBarEnabled) {
            waypoints.updatePlayer(player);
        }
    }

    private static synchronized void register(ServerPlayer player) {
        if (player.cullTracker != null) {
            return;
        }
        final PlayerCullTracker tracker = new PlayerCullTracker(player);
        player.cullTracker = tracker;
        TRACKERS.add(tracker);
        final long interval = Math.max(10L, DivineConfig.NetworkCategory.raytraceCullingCheckIntervalMs);
        tracker.future = pool().scheduleWithFixedDelay(tracker, interval, interval, TimeUnit.MILLISECONDS);
    }

    static void unregister(ServerPlayer player, PlayerCullTracker tracker) {
        TRACKERS.remove(tracker);
        if (player.cullTracker == tracker) {
            player.cullTracker = null;
        }
        final ScheduledFuture<?> future = tracker.future;
        if (future != null) {
            future.cancel(false);
        }
    }

    public static void onBlockChange(Level level, BlockPos pos) {
        if (!DivineConfig.NetworkCategory.raytraceCullingEnabled || TRACKERS.isEmpty()) {
            return;
        }
        final int traceDistance = DivineConfig.NetworkCategory.raytraceCullingMaxTraceDistance;
        for (final PlayerCullTracker tracker : TRACKERS) {
            tracker.notifyBlockChange(level, pos, traceDistance);
        }
    }

    private static ScheduledThreadPoolExecutor pool() {
        ScheduledThreadPoolExecutor pool = EntityCullingManager.pool;
        if (pool == null) {
            synchronized (EntityCullingManager.class) {
                pool = EntityCullingManager.pool;
                if (pool == null) {
                    final int configured = DivineConfig.NetworkCategory.raytraceCullingThreads;
                    final int threads = configured > 0
                        ? configured
                        : Math.clamp(Runtime.getRuntime().availableProcessors() / 8, 1, 4);
                    pool = new ScheduledThreadPoolExecutor(
                        threads,
                        new NamedAgnosticThreadFactory<>("Entity Culling", Thread::new, Thread.NORM_PRIORITY - 2)
                    );
                    pool.setRemoveOnCancelPolicy(true);
                    EntityCullingManager.pool = pool;
                }
            }
        }
        return pool;
    }

    public static void shutdown() {
        final ScheduledThreadPoolExecutor pool = EntityCullingManager.pool;
        if (pool != null) {
            pool.shutdown();
            try {
                pool.awaitTermination(3L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }
        }
    }
}
