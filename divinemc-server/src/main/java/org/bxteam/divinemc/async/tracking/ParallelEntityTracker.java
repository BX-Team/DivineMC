package org.bxteam.divinemc.async.tracking;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup;
import ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ParallelEntityTracker {
    private static final String THREAD_PREFIX = "Parallel Entity Tracker";
    private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);
    private static final int MIN_BATCH_SIZE = 64;

    public static final ThreadPoolExecutor TRACKER_EXECUTOR = DivineConfig.AsyncCategory.parallelTrackerEnabled ? newPool() : null;

    private static ThreadPoolExecutor newPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            DivineConfig.AsyncCategory.parallelTrackerThreads, DivineConfig.AsyncCategory.parallelTrackerThreads,
            DivineConfig.AsyncCategory.parallelTrackerKeepalive, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedAgnosticThreadFactory<>(THREAD_PREFIX, TickThread::new, Thread.NORM_PRIORITY - 2),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public record ScanResult(ChunkMap.TrackedEntity tracker, @Nullable List<ServerPlayer> toAdd, @Nullable List<ServerPlayer> toRemove, boolean clearAll) { }

    private record ScanBatch(@Nullable List<ScanResult> deltas, @Nullable List<Entity> tickThreadSends) { }

    public static void tick(final ServerLevel level) {
        final NearbyPlayers nearbyPlayers = level.moonrise$getNearbyPlayers();
        final ServerEntityLookup entityLookup = (ServerEntityLookup) level.moonrise$getEntityLookup();
        final ReferenceList<Entity> trackerEntities = entityLookup.trackerEntities;
        final Entity[] scanEntitiesRaw = trackerEntities.getRawDataUnchecked();
        final int scanLen = Math.min(scanEntitiesRaw.length, trackerEntities.size());
        if (scanLen == 0) {
            return;
        }

        final List<ScanResult> deltas = new ArrayList<>();
        final List<Entity> tickThreadSends = new ArrayList<>();
        runPhase(scanLen, (from, to) -> {
            List<ScanResult> batchDeltas = null;
            List<Entity> batchTickThreadSends = null;
            for (int i = from; i < to; ++i) {
                final Entity entity = scanEntitiesRaw[i];
                if (entity == null) continue;
                if (mustSendOnTickThread(entity)) {
                    if (batchTickThreadSends == null) batchTickThreadSends = new ArrayList<>();
                    batchTickThreadSends.add(entity);
                }
                final ChunkMap.TrackedEntity tracker = ((EntityTrackerEntity) entity).moonrise$getTrackedEntity();
                if (tracker == null) continue;
                try {
                    final ScanResult delta = tracker.scanPlayers(nearbyPlayers.getChunk(entity.chunkPosition()));
                    if (delta != null) {
                        if (batchDeltas == null) batchDeltas = new ArrayList<>();
                        batchDeltas.add(delta);
                    }
                } catch (Throwable throwable) {
                    LOGGER.error("Error scanning tracked players of entity {}", entity, throwable);
                }
            }
            return batchDeltas == null && batchTickThreadSends == null ? null : new ScanBatch(batchDeltas, batchTickThreadSends);
        }, batch -> {
            if (batch.deltas() != null) deltas.addAll(batch.deltas());
            if (batch.tickThreadSends() != null) tickThreadSends.addAll(batch.tickThreadSends());
        });

        for (int i = 0, size = deltas.size(); i < size; ++i) {
            final ScanResult delta = deltas.get(i);
            try {
                delta.tracker().applyScan(delta);
            } catch (Throwable throwable) {
                LOGGER.error("Error applying tracker changes", throwable);
            }
        }

        final Entity[] sendEntitiesRaw = trackerEntities.getRawDataUnchecked();
        final int sendLen = Math.min(sendEntitiesRaw.length, trackerEntities.size());
        runPhase(sendLen, (from, to) -> {
            for (int i = from; i < to; ++i) {
                final Entity entity = sendEntitiesRaw[i];
                if (entity == null || mustSendOnTickThread(entity)) continue;
                sendChanges(entity);
            }
            return null;
        }, null);

        for (int i = 0, size = tickThreadSends.size(); i < size; ++i) {
            sendChanges(tickThreadSends.get(i));
        }
    }

    private static boolean mustSendOnTickThread(final Entity entity) {
        return entity instanceof ServerPlayer || entity instanceof ItemFrame;
    }

    private static void sendChanges(final Entity entity) {
        final ChunkMap.TrackedEntity tracker = ((EntityTrackerEntity) entity).moonrise$getTrackedEntity();
        if (tracker == null) {
            return;
        }
        if (!tracker.moonrise$hasPlayers() && !((ChunkSystemEntity) entity).moonrise$getChunkStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
            return;
        }
        try {
            tracker.serverEntity.sendChanges();
        } catch (Throwable throwable) {
            LOGGER.error("Error sending tracker changes of entity {}", entity, throwable);
        }
    }

    private interface BatchTask<T> {
        @Nullable T run(int from, int to);
    }

    private static <T> void runPhase(final int len, final BatchTask<T> task, final @Nullable Consumer<T> resultConsumer) {
        final int batches = Math.min(TRACKER_EXECUTOR.getMaximumPoolSize() + 1, Math.max(1, len / MIN_BATCH_SIZE));
        if (batches <= 1) {
            final T result = task.run(0, len);
            if (result != null && resultConsumer != null) {
                resultConsumer.accept(result);
            }
            return;
        }

        final int step = (len + batches - 1) / batches;
        final List<CompletableFuture<T>> futures = new ArrayList<>(batches - 1);
        int from = 0;
        for (; from + step < len; from += step) {
            final int batchFrom = from;
            final int batchTo = from + step;
            futures.add(CompletableFuture.supplyAsync(() -> task.run(batchFrom, batchTo), TRACKER_EXECUTOR));
        }

        T inlineResult = null;
        try {
            inlineResult = task.run(from, len);
        } catch (Throwable throwable) {
            LOGGER.error("Error executing tracker batch", throwable);
        }

        for (int i = 0, size = futures.size(); i < size; ++i) {
            try {
                final T result = futures.get(i).join();
                if (result != null && resultConsumer != null) {
                    resultConsumer.accept(result);
                }
            } catch (Throwable throwable) {
                LOGGER.error("Error executing tracker batch", throwable);
            }
        }
        if (inlineResult != null && resultConsumer != null) {
            resultConsumer.accept(inlineResult);
        }
    }
}
