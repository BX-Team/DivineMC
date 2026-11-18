package org.bxteam.divinemc.async.pwt;

import io.papermc.paper.threadedregions.scheduler.FallbackRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

import java.util.function.Consumer;

/**
 * The region scheduler under parallel world ticking: tasks of Folia-supported plugins run on the thread of their
 * level (see {@link LevelSchedulers}), the others on the global scheduler as in Paper.
 */
@NullMarked
public final class LevelRegionScheduler implements RegionScheduler {
    private final FallbackRegionScheduler fallback = new FallbackRegionScheduler();

    @Override
    public void execute(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Runnable run) {
        if (LevelSchedulers.runsOnLevelThread(plugin)) {
            ((CraftWorld) world).getHandle().pluginRegionTasks.execute(plugin, run);
        } else {
            this.fallback.execute(plugin, world, chunkX, chunkZ, run);
        }
    }

    @Override
    public ScheduledTask run(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Consumer<ScheduledTask> task) {
        if (LevelSchedulers.runsOnLevelThread(plugin)) {
            return ((CraftWorld) world).getHandle().pluginRegionTasks.run(plugin, task);
        }
        return this.fallback.run(plugin, world, chunkX, chunkZ, task);
    }

    @Override
    public ScheduledTask runDelayed(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Consumer<ScheduledTask> task, final long delayTicks) {
        if (LevelSchedulers.runsOnLevelThread(plugin)) {
            return ((CraftWorld) world).getHandle().pluginRegionTasks.runDelayed(plugin, task, delayTicks);
        }
        return this.fallback.runDelayed(plugin, world, chunkX, chunkZ, task, delayTicks);
    }

    @Override
    public ScheduledTask runAtFixedRate(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Consumer<ScheduledTask> task, final long initialDelayTicks, final long periodTicks) {
        if (LevelSchedulers.runsOnLevelThread(plugin)) {
            return ((CraftWorld) world).getHandle().pluginRegionTasks.runAtFixedRate(plugin, task, initialDelayTicks, periodTicks);
        }
        return this.fallback.runAtFixedRate(plugin, world, chunkX, chunkZ, task, initialDelayTicks, periodTicks);
    }
}
