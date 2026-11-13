package org.bxteam.divinemc.async.world;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.Optional;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.portal.TeleportTransition;
import org.jspecify.annotations.NullMarked;

/**
 * Vanilla's own operations that cross from one level into another while levels tick in parallel: portal travel, an
 * ender pearl whose owner is in another level, a villager releasing a point of interest in another level. They are
 * queued per source level and run on the server thread once every level has ticked, in level order and then in the
 * order they were queued, so the result does not depend on which level thread got there first.
 */
@NullMarked
public final class CrossWorldTasks {
    private CrossWorldTasks() {
    }

    public static boolean shouldDefer() {
        if (!ParallelWorldTicking.ENABLED) {
            return false;
        }
        final Thread thread = Thread.currentThread();
        return thread instanceof TickThread.ServerLevelTickThread || thread instanceof TickThread.RegionTickThread;
    }

    public static void defer(final ServerLevel source, final Runnable task) {
        final WorldTickScheduler scheduler = MinecraftServer.getServer().worldTickScheduler;
        if (scheduler == null) {
            throw new IllegalStateException("Cross-world task queued without parallel world ticking");
        }
        scheduler.defer(source, task);
    }

    public static boolean deferPortal(final ServerLevel level, final Entity entity) {
        if (!shouldDefer()) {
            return false;
        }
        defer(level, () -> {
            if (entity.isRemoved() || entity.level() != level || entity.portalProcess == null) {
                return;
            }
            final TeleportTransition transition = entity.portalProcess.getPortalDestination(level, entity);
            if (transition != null) {
                entity.teleportToPortalDestination(level, transition);
            }
        });
        return true;
    }

    public static boolean deferPoiRelease(final Villager villager, final ServerLevel poiLevel, final GlobalPos memory, final BiPredicate<Villager, Holder<PoiType>> predicate) {
        if (poiLevel == villager.level() || !shouldDefer()) {
            return false;
        }
        defer((ServerLevel) villager.level(), () -> {
            final PoiManager poiManager = poiLevel.getPoiManager();
            final Optional<Holder<PoiType>> type = poiManager.getType(memory.pos());
            if (type.isPresent() && predicate.test(villager, type.get())) {
                poiManager.release(memory.pos());
                poiLevel.debugSynchronizers().updatePoi(memory.pos());
            }
        });
        return true;
    }

    public static boolean deferJobSiteRelease(final ServerLevel level, final ServerLevel poiLevel, final BlockPos pos) {
        if (poiLevel == level || !shouldDefer()) {
            return false;
        }
        defer(level, () -> {
            final PoiManager manager = poiLevel.getPoiManager();
            if (manager.exists(pos, p -> true)) {
                manager.release(pos);
            }
            level.debugSynchronizers().updatePoi(pos);
        });
        return true;
    }
}
