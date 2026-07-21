package org.bxteam.divinemc.async.sensing;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.sensing.Sensor;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs due AI sensors (read-only world scans) on a worker pool right before entity ticking, one
 * task per mob. The tick thread blocks on the join barrier so the world can't mutate; a pre-ticked
 * sensor resets its timer, so the mob's own {@link Brain} tick later skips it.
 */
public final class ParallelSensorTicker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final @org.jetbrains.annotations.Nullable ExecutorService POOL = initializePool();

    /**
     * Whitelist of sensors audited as safe to run off the tick thread while it blocks on the join
     * barrier (world frozen: entity/block reads are safe, writes must stay within the mob's own
     * {@link Brain}, and no plugin-facing events may fire)
     */
    private static final java.util.Set<Class<?>> AUDITED_SENSORS = java.util.Set.of(
        net.minecraft.world.entity.ai.sensing.AdultSensor.class,
        net.minecraft.world.entity.ai.sensing.AdultSensorAnyType.class,
        net.minecraft.world.entity.ai.sensing.AxolotlAttackablesSensor.class,
        net.minecraft.world.entity.ai.sensing.BreezeAttackEntitySensor.class,
        net.minecraft.world.entity.ai.sensing.FrogAttackablesSensor.class,
        net.minecraft.world.entity.ai.sensing.GolemSensor.class,
        net.minecraft.world.entity.ai.sensing.HoglinSpecificSensor.class,
        net.minecraft.world.entity.ai.sensing.HurtBySensor.class,
        net.minecraft.world.entity.ai.sensing.IsInWaterSensor.class,
        net.minecraft.world.entity.ai.sensing.MobSensor.class,
        net.minecraft.world.entity.ai.sensing.NearestItemSensor.class,
        net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor.class,
        net.minecraft.world.entity.ai.sensing.PiglinBruteSpecificSensor.class,
        net.minecraft.world.entity.ai.sensing.PiglinSpecificSensor.class,
        net.minecraft.world.entity.ai.sensing.PlayerSensor.class,
        net.minecraft.world.entity.ai.sensing.SecondaryPoiSensor.class,
        net.minecraft.world.entity.ai.sensing.VillagerBabiesSensor.class,
        net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor.class,
        net.minecraft.world.entity.ai.sensing.WardenEntitySensor.class
    );

    private static boolean canRunAsync(final Sensor<?> sensor) {
        return AUDITED_SENSORS.contains(sensor.getClass());
    }

    private ParallelSensorTicker() { }

    private static @org.jetbrains.annotations.Nullable ExecutorService initializePool() {
        if (!DivineConfig.AsyncCategory.parallelSensorsEnabled) return null;

        return new ThreadPoolExecutor(
            DivineConfig.AsyncCategory.parallelSensorsMaxThreads, DivineConfig.AsyncCategory.parallelSensorsMaxThreads,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedAgnosticThreadFactory<>("Parallel Sensors", TickThread::new, Thread.NORM_PRIORITY)
        );
    }

    /**
     * Called on the world's tick thread before the entity ticking phase. Returns only after
     * every submitted sensor task has completed, so entity ticking never overlaps with sensing.
     */
    public static void preTickSensors(final ServerLevel level) {
        if (!DivineConfig.AsyncCategory.parallelSensorsEnabled || POOL == null) return;

        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        final net.minecraft.world.TickRateManager tickRateManager = level.tickRateManager();

        level.entityTickList.forEach(entity -> collectMob(level, entity, tickRateManager, futures));

        for (int i = 0, len = futures.size(); i < len; ++i) {
            try {
                futures.get(i).join();
            } catch (Throwable throwable) {
                LOGGER.error("Exception while running parallel sensor phase in world '{}'", ca.spottedleaf.moonrise.common.util.WorldUtil.getWorldName(level), throwable);
            }
        }
    }

    private static void collectMob(final ServerLevel level, final Entity entity, final net.minecraft.world.TickRateManager tickRateManager, final List<CompletableFuture<Void>> futures) {
        if (!(entity instanceof Mob mob) || entity.isRemoved()) return;
        if (entity.activatedTick < MinecraftServer.currentTick) return; // EAR-inactive mobs do not tick their brain
        if (tickRateManager.isEntityFrozen(entity)) return;

        final Brain<?> brain = mob.getBrain();
        if (brain.sensors.isEmpty()) return;

        List<Sensor<?>> due = null;
        for (final Sensor<?> sensor : brain.sensors.values()) {
            if (sensor.divinemc$isDue() && canRunAsync(sensor)) {
                if (due == null) due = new ArrayList<>(2);
                due.add(sensor);
            }
        }
        if (due == null) return;

        final List<Sensor<?>> dueSensors = due;
        futures.add(CompletableFuture.runAsync(() -> {
            for (int i = 0, len = dueSensors.size(); i < len; ++i) {
                @SuppressWarnings("unchecked") final Sensor<? super Mob> sensor = (Sensor<? super Mob>) dueSensors.get(i);
                sensor.divinemc$tickAsync(level, mob);
            }
        }, POOL));
    }
}
