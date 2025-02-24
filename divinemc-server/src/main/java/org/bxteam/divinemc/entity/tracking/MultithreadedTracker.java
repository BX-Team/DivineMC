package org.bxteam.divinemc.entity.tracking;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup;
import ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.DivineConfig;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MultithreadedTracker {
    private static final String THREAD_PREFIX = "DivineMC Async Tracker";
    private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);

    private static long lastWarnMillis = System.currentTimeMillis();
    private static final ThreadPoolExecutor trackerExecutor = new ThreadPoolExecutor(
        getCorePoolSize(),
        getMaxPoolSize(),
        getKeepAliveTime(), TimeUnit.SECONDS,
        getQueueImpl(),
        getThreadFactory(),
        getRejectedPolicy()
    );

    public static Executor getTrackerExecutor() {
        return trackerExecutor;
    }

    public static void tick(ChunkSystemServerLevel level) {
        try {
            if (!DivineConfig.multithreadedCompatModeEnabled) {
                tickAsync(level);
            } else {
                tickAsyncWithCompatMode(level);
            }
        } catch (Exception e) {
            LOGGER.error("Error occurred while executing async task.", e);
        }
    }

    private static void tickAsync(ChunkSystemServerLevel level) {
        final NearbyPlayers nearbyPlayers = level.moonrise$getNearbyPlayers();
        final ServerEntityLookup entityLookup = (ServerEntityLookup) level.moonrise$getEntityLookup();

        final ReferenceList<Entity> trackerEntities = entityLookup.trackerEntities;
        final Entity[] trackerEntitiesRaw = trackerEntities.getRawDataUnchecked();

        // Move tracking to off-main
        trackerExecutor.execute(() -> {
            for (final Entity entity : trackerEntitiesRaw) {
                if (entity == null) continue;

                final ChunkMap.TrackedEntity tracker = ((EntityTrackerEntity) entity).moonrise$getTrackedEntity();

                if (tracker == null) continue;

                tracker.moonrise$tick(nearbyPlayers.getChunk(entity.chunkPosition()));
                tracker.serverEntity.sendChanges();
            }
        });
    }

    private static void tickAsyncWithCompatMode(ChunkSystemServerLevel level) {
        final NearbyPlayers nearbyPlayers = level.moonrise$getNearbyPlayers();
        final ServerEntityLookup entityLookup = (ServerEntityLookup) level.moonrise$getEntityLookup();

        final ReferenceList<Entity> trackerEntities = entityLookup.trackerEntities;
        final Entity[] trackerEntitiesRaw = trackerEntities.getRawDataUnchecked();
        final Runnable[] sendChangesTasks = new Runnable[trackerEntitiesRaw.length];
        int index = 0;

        for (final Entity entity : trackerEntitiesRaw) {
            if (entity == null) continue;

            final ChunkMap.TrackedEntity tracker = ((EntityTrackerEntity) entity).moonrise$getTrackedEntity();

            if (tracker == null) continue;

            tracker.moonrise$tick(nearbyPlayers.getChunk(entity.chunkPosition()));
            sendChangesTasks[index++] = () -> tracker.serverEntity.sendChanges(); // Collect send changes to task array
        }

        // batch submit tasks
        trackerExecutor.execute(() -> {
            for (final Runnable sendChanges : sendChangesTasks) {
                if (sendChanges == null) continue;

                sendChanges.run();
            }
        });
    }

    // Original ChunkMap#newTrackerTick of Paper
    // Just for diff usage for future update
    @SuppressWarnings("DuplicatedCode")
    private static void tickOriginal(ServerLevel level) {
        final ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup entityLookup = (ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup) ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup();

        final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.world.entity.Entity> trackerEntities = entityLookup.trackerEntities;
        final Entity[] trackerEntitiesRaw = trackerEntities.getRawDataUnchecked();
        for (int i = 0, len = trackerEntities.size(); i < len; ++i) {
            final Entity entity = trackerEntitiesRaw[i];
            final ChunkMap.TrackedEntity tracker = ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity) entity).moonrise$getTrackedEntity();
            if (tracker == null) {
                continue;
            }
            ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerTrackedEntity) tracker).moonrise$tick(((ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity) entity).moonrise$getChunkData().nearbyPlayers);
            if (((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerTrackedEntity) tracker).moonrise$hasPlayers()
                || ((ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity) entity).moonrise$getChunkStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
                tracker.serverEntity.sendChanges();
            }
        }
    }

    private static int getCorePoolSize() {
        return 1;
    }

    private static int getMaxPoolSize() {
        return DivineConfig.asyncEntityTrackerMaxThreads;
    }

    private static long getKeepAliveTime() {
        return DivineConfig.asyncEntityTrackerKeepalive;
    }

    private static BlockingQueue<Runnable> getQueueImpl() {
        final int queueCapacity = DivineConfig.asyncEntityTrackerQueueSize;

        return new LinkedBlockingQueue<>(queueCapacity);
    }

    private static @NotNull ThreadFactory getThreadFactory() {
        return new ThreadFactoryBuilder()
            .setThreadFactory(MultithreadedTrackerThread::new)
            .setNameFormat(THREAD_PREFIX + " Thread - %d")
            .setPriority(Thread.NORM_PRIORITY - 2)
            .build();
    }

    private static @NotNull RejectedExecutionHandler getRejectedPolicy() {
        return (rejectedTask, executor) -> {
            BlockingQueue<Runnable> workQueue = executor.getQueue();

            if (!executor.isShutdown()) {
                if (!workQueue.isEmpty()) {
                    List<Runnable> pendingTasks = new ArrayList<>(workQueue.size());

                    workQueue.drainTo(pendingTasks);

                    for (Runnable pendingTask : pendingTasks) {
                        pendingTask.run();
                    }
                }

                rejectedTask.run();
            }

            if (System.currentTimeMillis() - lastWarnMillis > 30000L) {
                LOGGER.warn("Async entity tracker is busy! Tracking tasks will be done in the server thread. Increasing max-threads in DivineMC config may help.");
                lastWarnMillis = System.currentTimeMillis();
            }
        };
    }

    public static class MultithreadedTrackerThread extends Thread {
        public MultithreadedTrackerThread(Runnable runnable) {
            super(runnable);
        }
    }
}
