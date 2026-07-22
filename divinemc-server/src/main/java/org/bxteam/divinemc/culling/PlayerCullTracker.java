package org.bxteam.divinemc.culling;

import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.cache.ArrayOcclusionCache;
import com.logisticscraft.occlusionculling.util.Vec3d;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bxteam.divinemc.config.DivineConfig;

import java.util.concurrent.ScheduledFuture;

/**
 * Per-player occlusion culling state. A pass runs periodically on the shared culling pool
 * (never concurrently with itself - scheduled with fixed delay), raytraces every candidate
 * entity around the player and publishes the set of hidden entity ids. The entity tracker
 * reads that set from {@code ChunkMap.TrackedEntity#wantsToSee} and untracks hidden entities.
 *
 * <p>Failure direction is always "visible": tracing errors, unloaded chunks and disabled
 * states publish nothing, so the worst case is vanilla behavior.
 */
public final class PlayerCullTracker implements Runnable {
    private static final IntOpenHashSet EMPTY = new IntOpenHashSet();
    private static final long FULL_RESET_INTERVAL_MS = 2500L;

    private final ServerPlayer player;
    private final OcclusionCullingInstance culling;
    private final CullingDataProvider provider;

    // Worker-thread-only state
    private final Vec3d camera = new Vec3d(0, 0, 0);
    private final Vec3d aabbMin = new Vec3d(0, 0, 0);
    private final Vec3d aabbMax = new Vec3d(0, 0, 0);
    /** entity id -> epoch millis until which the entity is pinned visible (flicker hysteresis). */
    private final Int2LongOpenHashMap visibleUntil = new Int2LongOpenHashMap();
    private long lastCacheReset;

    // Published state, read from tick/scan/region threads
    private volatile IntOpenHashSet culled = EMPTY;
    private volatile Vec3 lastCameraPos = Vec3.ZERO;
    private volatile ServerLevel lastLevel;
    private volatile boolean cacheDirty = true;

    volatile ScheduledFuture<?> future;

    PlayerCullTracker(ServerPlayer player) {
        this.player = player;
        this.provider = new CullingDataProvider();
        int reach = DivineConfig.NetworkCategory.raytraceCullingMaxTraceDistance + 8;
        this.culling = new OcclusionCullingInstance(
            reach,
            this.provider,
            new ArrayOcclusionCache(reach),
            DivineConfig.NetworkCategory.raytraceCullingAabbExpansion
        );
    }

    public boolean isCulled(int entityId) {
        return this.culled.contains(entityId);
    }

    void notifyBlockChange(net.minecraft.world.level.Level level, BlockPos pos, int traceDistance) {
        if (this.cacheDirty || this.lastLevel != level) {
            return;
        }
        final Vec3 cam = this.lastCameraPos;
        if (Math.abs(pos.getX() - cam.x) <= traceDistance
            && Math.abs(pos.getY() - cam.y) <= traceDistance
            && Math.abs(pos.getZ() - cam.z) <= traceDistance) {
            this.cacheDirty = true;
        }
    }

    @Override
    public void run() {
        try {
            if (!DivineConfig.NetworkCategory.raytraceCullingEnabled
                || this.player.hasDisconnected() || this.player.isRemoved()) {
                EntityCullingManager.unregister(this.player, this);
                this.culled = EMPTY;
                return;
            }
            if (this.player.isSpectator()) {
                if (this.culled != EMPTY) {
                    this.culled = EMPTY;
                }
                this.visibleUntil.clear();
                return;
            }
            this.runPass();
        } catch (Throwable throwable) {
            this.culled = EMPTY;
            EntityCullingManager.LOGGER.warn("Entity culling pass failed for {}", this.player.getScoreboardName(), throwable);
        }
    }

    private void runPass() {
        final ServerLevel level = this.player.level();
        final Vec3 eye = this.player.getEyePosition();
        final long now = System.currentTimeMillis();

        final boolean levelChanged = level != this.lastLevel;
        if (levelChanged) {
            this.provider.setLevel(level);
            this.lastLevel = level;
        }
        if (levelChanged || this.cacheDirty || !eye.equals(this.lastCameraPos) || now - this.lastCacheReset >= FULL_RESET_INTERVAL_MS) {
            this.cacheDirty = false;
            this.culling.resetCache();
            this.provider.resetChunkCache();
            this.lastCacheReset = now;
        }
        this.lastCameraPos = eye;
        this.camera.set(eye.x, eye.y, eye.z);

        final int traceDistance = DivineConfig.NetworkCategory.raytraceCullingMaxTraceDistance;
        final double traceDistanceSqr = (double) traceDistance * traceDistance;
        final double forceVisibleRadius = DivineConfig.NetworkCategory.raytraceCullingForceVisibleRadius;
        final double forceVisibleRadiusSqr = forceVisibleRadius * forceVisibleRadius;
        final int boundingBoxLimit = DivineConfig.NetworkCategory.raytraceCullingBoundingBoxLimit;
        final long visibilityTimeoutMs = DivineConfig.NetworkCategory.raytraceCullingVisibilityTimeoutMs;

        IntOpenHashSet newCulled = null;
        int traceFailures = 0;
        Throwable lastTraceFailure = null;
        for (final Entity entity : level.moonrise$getEntityLookup().getAllMapped()) {
            if (entity == null || entity == this.player || this.isAlwaysVisible(entity)) {
                continue;
            }

            final double distanceSqr = entity.distanceToSqr(eye.x, eye.y, eye.z);
            if (distanceSqr < forceVisibleRadiusSqr || distanceSqr > traceDistanceSqr) {
                continue;
            }

            final AABB box = entity.getBoundingBox();
            if (box.getXsize() > boundingBoxLimit || box.getYsize() > boundingBoxLimit || box.getZsize() > boundingBoxLimit) {
                continue;
            }

            final int id = entity.getId();
            if (this.visibleUntil.get(id) > now) {
                continue; // recently proven visible - pinned to avoid track/untrack flicker
            }

            try {
                this.aabbMin.set(box.minX, box.minY, box.minZ);
                this.aabbMax.set(box.maxX, box.maxY, box.maxZ);
                if (this.culling.isAABBVisible(this.aabbMin, this.aabbMax, this.camera)) {
                    this.visibleUntil.put(id, now + visibilityTimeoutMs);
                } else {
                    if (newCulled == null) {
                        newCulled = new IntOpenHashSet();
                    }
                    newCulled.add(id);
                }
            } catch (Throwable throwable) {
                ++traceFailures; // entity stays visible
                lastTraceFailure = throwable;
            }
        }

        if (traceFailures > 0) {
            EntityCullingManager.LOGGER.debug("{} culling trace(s) failed for {} this pass (entities kept visible)", traceFailures, this.player.getScoreboardName(), lastTraceFailure);
        }

        this.culled = newCulled == null ? EMPTY : newCulled;

        for (final var iterator = this.visibleUntil.int2LongEntrySet().fastIterator(); iterator.hasNext(); ) {
            if (iterator.next().getLongValue() <= now) {
                iterator.remove();
            }
        }
    }

    private boolean isAlwaysVisible(Entity entity) {
        if (entity.isRemoved() || entity.getType().raytraceCullingSkip) {
            return true;
        }
        if (entity instanceof Player) {
            // Spectators are already invisible to survival players
            if (!DivineConfig.NetworkCategory.raytraceCullingCullPlayers || entity.isSpectator()) {
                return true;
            }
        }
        if (entity instanceof Display || entity instanceof FishingHook) {
            return true; // holograms and rod lines misbehave when untracked
        }
        if (entity.isCurrentlyGlowing() || entity.isCustomNameVisible()) {
            return true; // rendered through walls on the client
        }
        if (DivineConfig.NetworkCategory.raytraceCullingSkipMarkerArmorStands && entity instanceof ArmorStand && entity.isInvisible()) {
            return true;
        }

        // Untracking one half of a vehicle/passenger pair desyncs the other half
        return entity.isPassenger() || entity.isVehicle();
    }
}
