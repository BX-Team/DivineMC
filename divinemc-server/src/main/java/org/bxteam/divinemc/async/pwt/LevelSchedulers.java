package org.bxteam.divinemc.async.pwt;

import io.papermc.paper.threadedregions.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.FoliaGlobalRegionScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.plugin.Plugin;
import org.bxteam.divinemc.concurrent.FoliaSupport;
import org.jspecify.annotations.NullMarked;

/**
 * Runs the region and entity tasks of plugins that declare {@code folia-supported: true} on the thread of the level
 * they belong to, at the start of its tick, instead of serially on the server thread. Such plugins already expect
 * their tasks to run on several threads at once and to own only their region, so parallel world ticking gives them
 * one region per level. Tasks of other plugins keep Paper's serial behaviour.
 * <p>
 * Region tasks wait in the level's own {@link FoliaGlobalRegionScheduler}. Entity tasks wait in a second scheduler of
 * the entity, so they follow it to another level. An entity task runs once per server tick: on the thread of the
 * level the entity is in when that level's tick starts, or, if the entity changed levels so that no level thread ran
 * it, on the server thread after all levels have ticked.
 */
@NullMarked
public final class LevelSchedulers {
    public static final EntityScheduler.EntitySchedulerTickList ENTITY_SCHEDULERS = new EntityScheduler.EntitySchedulerTickList();

    private LevelSchedulers() {
    }

    public static boolean runsOnLevelThread(final Plugin plugin) {
        return ParallelWorldTicking.ENABLED && FoliaSupport.declares(plugin);
    }

    public static EntityScheduler entityScheduler(final CraftEntity entity, final Plugin plugin) {
        final EntityScheduler levelScheduler = entity.levelTaskScheduler;
        return levelScheduler != null && runsOnLevelThread(plugin) ? levelScheduler : entity.taskScheduler;
    }

    public static void register(final CraftEntity entity) {
        final EntityScheduler scheduler = entity.levelTaskScheduler;
        if (scheduler != null) {
            scheduler.registerTo(ENTITY_SCHEDULERS);
        }
    }

    public static void retire(final CraftEntity entity) {
        final EntityScheduler scheduler = entity.levelTaskScheduler;
        if (scheduler != null) {
            scheduler.retire();
        }
    }

    /**
     * Called by the level's thread when its tick starts.
     */
    public static void tickLevel(final ServerLevel level, final int tick) {
        level.pluginRegionTasks.tick();
        for (final EntityScheduler scheduler : ENTITY_SCHEDULERS.getAllSchedulers()) {
            final CraftEntity entity = scheduler.entity;
            // an entity only changes levels on a thread that owns both, so a foreign one never reads as this level
            if (entity.getHandleRaw().level() == level && entity.levelTaskSchedulerTick != tick) {
                tickEntity(scheduler, entity, tick);
            }
        }
    }

    /**
     * Called by the server thread after every level has ticked.
     */
    public static void tickMissed(final int tick) {
        for (final EntityScheduler scheduler : ENTITY_SCHEDULERS.getAllSchedulers()) {
            if (scheduler.entity.levelTaskSchedulerTick != tick) {
                tickEntity(scheduler, scheduler.entity, tick);
            }
        }
    }

    private static void tickEntity(final EntityScheduler scheduler, final CraftEntity entity, final int tick) {
        entity.levelTaskSchedulerTick = tick;
        if (!scheduler.isRetired()) {
            scheduler.executeTick();
        }
    }

    public static void cancelTasks(final Plugin plugin) {
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            level.pluginRegionTasks.cancelTasks(plugin);
        }
    }
}
