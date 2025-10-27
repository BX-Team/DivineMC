package org.bxteam.divinemc.async.rct;

import ca.spottedleaf.moonrise.common.list.IteratorSafeOrderedReferenceSet;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.ViewDistances;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import io.papermc.paper.entity.activation.ActivationRange;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.slf4j.Logger;


public final class RegionizedChunkTicking extends ServerChunkCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Executor REGION_EXECUTOR = Executors.newFixedThreadPool(DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadCount,
        new NamedAgnosticThreadFactory<>("Region Ticking", TickThread::new, DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadPriority)
    );

    public RegionizedChunkTicking(final ServerLevel level, final LevelStorageSource.LevelStorageAccess levelStorageAccess, final DataFixer fixerUpper, final StructureTemplateManager structureManager, final Executor dispatcher, final ChunkGenerator generator, final int viewDistance, final int simulationDistance, final boolean sync, final ChunkProgressListener progressListener, final ChunkStatusUpdateListener chunkStatusListener, final Supplier<DimensionDataStorage> overworldDataStorage) {
        super(level, levelStorageAccess, fixerUpper, structureManager, dispatcher, generator, viewDistance, simulationDistance, sync, progressListener, chunkStatusListener, overworldDataStorage);
    }

    public void execute(CompletableFuture<Void> spawns, final LevelChunk[] raw) {
        final TickPair tickPair = computePlayerRegionsParallel();
        final RegionData[] regions = tickPair.regions();
        final int regionCount = regions.length;

        ActivationRange.activateEntities(level); // Paper - EAR

        final int randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        ObjectArrayList<CompletableFuture<LongOpenHashSet>> ticked = new ObjectArrayList<>(regionCount);

        for (final RegionData region : regions) {
            if (region == null || region.isEmpty()) {
                continue;
            }

            ticked.add(tick(region, randomTickSpeed));
        }

        CompletableFuture.runAsync(() -> {
            finishTicking(ticked, randomTickSpeed, raw, tickPair);
            spawns.join();
        }, REGION_EXECUTOR).join();
    }

    private CompletableFuture<LongOpenHashSet> tick(RegionData region, int randomTickSpeed) {
        return CompletableFuture.supplyAsync(() -> {
            ObjectArrayList<LevelChunk> regionChunks = new ObjectArrayList<>(region.chunks().size());
            LongOpenHashSet regionChunksIDs = new LongOpenHashSet(region.chunks().size());
            for (long key : region.chunks()) {
                LevelChunk chunk = fullChunks.get(key);
                if (chunk != null) {
                    regionChunks.add(chunk);
                    regionChunksIDs.add(key);
                }
            }

            for (LevelChunk chunk : regionChunks) {
                level.tickChunk(chunk, randomTickSpeed);
            }
            for (Entity entity : region.entities()) {
                tickEntity(entity);
            }

            return regionChunksIDs;
        }, REGION_EXECUTOR);
    }

    private void finishTicking(final ObjectArrayList<CompletableFuture<LongOpenHashSet>> ticked, final int randomTickSpeed, final LevelChunk[] raw, final TickPair tickPair) {
        try {
            CompletableFuture.allOf(ticked.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException ex) {
            LOGGER.error("Error during region chunk ticking", ex.getCause());
        }

        LongOpenHashSet tickedChunkKeys = new LongOpenHashSet(raw.length);

        for (CompletableFuture<LongOpenHashSet> future : ticked) {
            if (!future.isCompletedExceptionally()) {
                try {
                    LongOpenHashSet regionChunks = future.join();
                    tickedChunkKeys.addAll(regionChunks);
                } catch (Exception e) {
                    LOGGER.error("Exception in region ticking future", e);
                }
            }
        }

        for (LevelChunk chunk : raw) {
            if (!tickedChunkKeys.contains(chunk.coordinateKey)) {
                level.tickChunk(chunk, randomTickSpeed);
            }
        }

        for (Entity entity : tickPair.entities()) {
            tickEntity(entity);
        }
    }

    private TickPair computePlayerRegionsParallel() {
        int tickViewDistance = level.moonrise$getViewDistanceHolder().getViewDistances().tickViewDistance();
        List<ServerPlayer> players = new ArrayList<>(level.players());
        int max = maxChunksForViewDistance();

        List<LongOpenHashSet> playerChunkSets = players.parallelStream()
            .map(player -> {
                ChunkPos playerChunk = player.chunkPosition();
                int px = playerChunk.x;
                int pz = playerChunk.z;
                LongOpenHashSet chunkKeys = new LongOpenHashSet(max);
                for (int dx = -tickViewDistance; dx <= tickViewDistance; dx++) {
                    for (int dz = -tickViewDistance; dz <= tickViewDistance; dz++) {
                        long key = CoordinateUtils.getChunkKey(px + dx, pz + dz);
                        chunkKeys.add(key);
                    }
                }
                return chunkKeys;
            }).toList();

        List<LongOpenHashSet> mergedRegions = new ArrayList<>();
        boolean[] merged = new boolean[playerChunkSets.size()];

        for (int i = 0; i < playerChunkSets.size(); i++) {
            if (merged[i]) continue;

            LongOpenHashSet region = new LongOpenHashSet(playerChunkSets.get(i));
            merged[i] = true;

            boolean madeChanges;
            do {
                madeChanges = false;
                for (int j = i + 1; j < playerChunkSets.size(); j++) {
                    if (merged[j]) continue;

                    LongOpenHashSet set = playerChunkSets.get(j);

                    boolean hasIntersection = false;
                    LongIterator iter = set.iterator();
                    while (iter.hasNext()) {
                        if (region.contains(iter.nextLong())) {
                            hasIntersection = true;
                            break;
                        }
                    }

                    if (hasIntersection) {
                        region.addAll(set);
                        merged[j] = true;
                        madeChanges = true;
                    }
                }
            } while (madeChanges);

            mergedRegions.add(region);
        }

        ObjectArrayList<RegionData> regions = new ObjectArrayList<>();
        Long2IntOpenHashMap chunkToRegion = new Long2IntOpenHashMap(max * mergedRegions.size());
        chunkToRegion.defaultReturnValue(-1);
        for (int i = 0; i < mergedRegions.size(); i++) {
            regions.add(new RegionData(mergedRegions.get(i), new ObjectOpenHashSet<>()));
            for (long key : mergedRegions.get(i)) {
                chunkToRegion.put(key, i);
            }
        }

        final Set<Entity> firstTick = new ObjectOpenHashSet<>();

        synchronized (getEntityTickList().entities) {
            final IteratorSafeOrderedReferenceSet.Iterator<Entity> iterator = getEntityTickList().entities.iterator();
            try {
                while (iterator.hasNext()) {
                    Entity entity = iterator.next();
                    long chunkKey = entity.chunkPosition().longKey;
                    int regionIndex = chunkToRegion.get(chunkKey);
                    if (regionIndex != -1) {
                        regions.get(regionIndex).entities().add(entity);
                    } else {
                        firstTick.add(entity);
                    }
                }
            } finally {
                iterator.finishedIterating();
            }
        }

        return new TickPair(regions.toArray(new RegionData[0]), firstTick);
    }

    // Should be max safe estimate of ticking chunks in a region
    private int maxChunksForViewDistance() {
        ViewDistances distances = level.moonrise$getViewDistanceHolder().getViewDistances();
        int diameter = 2 * distances.tickViewDistance() + 1;
        return diameter * diameter;
    }

    private void tickEntity(Entity entity) {
        if (!entity.isRemoved()) {
            if (!level.tickRateManager().isEntityFrozen(entity)) {
                entity.checkDespawn();
                // Paper - rewrite chunk system
                Entity vehicle = entity.getVehicle();
                if (vehicle != null) {
                    if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                        return;
                    }

                    entity.stopRiding();
                }

                level.guardEntityTick(level::tickNonPassenger, entity);
            }
        }
    }
}
