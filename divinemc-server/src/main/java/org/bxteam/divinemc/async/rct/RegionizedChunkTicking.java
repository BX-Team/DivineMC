package org.bxteam.divinemc.async.rct;

import ca.spottedleaf.moonrise.common.list.IteratorSafeOrderedReferenceSet;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.datafixers.DataFixer;
import io.papermc.paper.entity.activation.ActivationRange;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class RegionizedChunkTicking extends ServerChunkCache {
    public static final Executor REGION_EXECUTOR = Executors.newFixedThreadPool(DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadCount,
        new NamedAgnosticThreadFactory<>("Region Ticking", TickThread::new, DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadPriority));
    private final AvgTimeLogger avgTimeLogger;
    public final RollingLongBuffer avgTime = new RollingLongBuffer(100);
    private final LongOpenHashSet tickedChunkKeys = new LongOpenHashSet(8192);
    private int i = 0;

    public RegionizedChunkTicking(
        ServerLevel level,
        LevelStorageSource.LevelStorageAccess levelStorageAccess,
        DataFixer fixerUpper,
        StructureTemplateManager structureTemplateManager,
        Executor executor,
        ChunkGenerator generator,
        int viewDistance,
        int simulationDistance,
        boolean sync,
        ChunkStatusUpdateListener chunkStatusListener,
        Supplier<SavedDataStorage> overworldDataStorage,
        final SavedDataStorage savedDataStorage
    ) {
        super(level, levelStorageAccess, fixerUpper, structureTemplateManager, executor, generator, viewDistance, simulationDistance, sync, chunkStatusListener, overworldDataStorage, savedDataStorage);
        this.avgTimeLogger = new AvgTimeLogger(level.serverLevelData.getLevelName());
    }

    @Override
    protected void iterateTickingChunksFaster(final @NotNull CompletableFuture<Void> spawns) {
        final long start = System.nanoTime();
        final ServerLevel world = this.level;
        final int randomTickSpeed = world.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        final LevelChunk[] raw = world.moonrise$getEntityTickingChunks().toArray(new LevelChunk[0]);
        final TickPair tickPair = computePlayerRegions();
        final RegionData[] regions = tickPair.regions();

        ActivationRange.activateEntities(level); // Paper - EAR

        ObjectArrayList<CompletableFuture<LongOpenHashSet>> blockFutures = new ObjectArrayList<>(regions.length);
        for (final RegionData region : regions) {
            if (region == null || region.isEmpty()) {
                continue;
            }
            blockFutures.add(tickBlocks(region, randomTickSpeed));
        }
        finishBlockTicking(blockFutures, randomTickSpeed, raw, tickPair);

        spawns.join();

        ObjectArrayList<CompletableFuture<Void>> entityFutures = new ObjectArrayList<>(regions.length);
        for (final RegionData region : regions) {
            if (region == null || region.entities().isEmpty()) {
                continue;
            }
            entityFutures.add(tickEntities(region));
        }
        finishEntityTicking(entityFutures, tickPair);

        final long end = System.nanoTime();
        avgTime.add(end - start);
    }

    private CompletableFuture<LongOpenHashSet> tickBlocks(RegionData region, int randomTickSpeed) {
        return CompletableFuture.supplyAsync(() -> {
            final long start = System.nanoTime();
            final LongOpenHashSet regionChunksIDs = new LongOpenHashSet(region.chunks().size());
            for (final long key : region.chunks()) {
                final LevelChunk chunk = fullChunks.get(key);
                if (chunk != null) {
                    level.tickChunk(chunk, randomTickSpeed);
                    regionChunksIDs.add(key);
                }
            }

            final long time = System.nanoTime() - start;
            final int regionHash = region.hashCode();
            final int chunks = regionChunksIDs.size();
            for (ServerPlayer player : region.players()) {
                player.avgTickTimeNanos.add(time);
                player.lastRegionChunkSize = chunks;
                player.regionHash = regionHash;
            }
            return regionChunksIDs;
        }, REGION_EXECUTOR);
    }

    private CompletableFuture<Void> tickEntities(RegionData region) {
        return CompletableFuture.runAsync(() -> {
            final long start = System.nanoTime();
            for (Entity entity : region.entities()) {
                tickEntity(entity);
            }

            final long time = System.nanoTime() - start;
            final int entities = region.entities().size();
            for (ServerPlayer player : region.players()) {
                player.avgTickTimeNanos.add(time);
                player.lastRegionEntityAmount = entities;
            }
        }, REGION_EXECUTOR);
    }

    private void finishBlockTicking(final ObjectArrayList<CompletableFuture<LongOpenHashSet>> ticked, final int randomTickSpeed, final LevelChunk[] raw, final TickPair tickPair) {
        tickedChunkKeys.clear();
        for (CompletableFuture<LongOpenHashSet> future : ticked) {
            try {
                LongOpenHashSet result = future.join();
                if (result != null) {
                    tickedChunkKeys.addAll(result);
                }
            } catch (Exception e) {
                LOGGER.error("Exception retrieving region ticking result", e);
            }
        }

        if (i++ % 100 == 0 && tickPair.regions().length > 0) {
            REGION_EXECUTOR.execute(() -> {
                StringBuilder sb = new StringBuilder();
                for (RegionData regionData : tickPair.regions()) {
                    sb.append("Region with ").append(regionData.chunks().size()).append(" chunks and ").append(regionData.entities().size()).append(" entities ticked for Players:\n");
                    for (ServerPlayer player : regionData.players()) {
                        long avgNanos = Math.round(player.avgTickTimeNanos.average().orElse(0));
                        long ms = avgNanos / 1_000_000;
                        long us = (avgNanos % 1_000_000) / 1_000;
                        long ns = avgNanos % 1_000;
                        sb.append("- ").append(player.displayName).append(" avg region tick time: ").append(ms).append(" ms ").append(us).append(" us ").append(ns).append(" ns").append("\n");
                    }
                }
                avgTimeLogger.logTickTime(sb.toString());
            });
        }

        for (LevelChunk chunk : raw) {
            if (!tickedChunkKeys.contains(chunk.coordinateKey)) {
                level.tickChunk(chunk, randomTickSpeed);
            }
        }
    }

    private void finishEntityTicking(final ObjectArrayList<CompletableFuture<Void>> ticked, final TickPair tickPair) {
        for (CompletableFuture<Void> future : ticked) {
            try {
                future.join();
            } catch (Exception e) {
                LOGGER.error("Exception during region entity ticking", e);
            }
        }

        for (Entity entity : tickPair.entities()) {
            tickEntity(entity);
        }
    }

    private TickPair computePlayerRegions() {
        List<ServerPlayer> players = new ArrayList<>(level.players());
        final int defaultTickDist = level.moonrise$getViewDistanceHolder().getViewDistances().tickViewDistance();
        final int defaultAmountOfChunks = (2 * defaultTickDist + 1) * (2 * defaultTickDist + 1);
        final int playerCount = players.size();

        Rectangle[] boundaries = new Rectangle[playerCount];
        int[] playerTickDistances = new int[playerCount];

        for (int i = 0; i < playerCount; i++) {
            ServerPlayer player = players.get(i);
            ChunkPos pos = player.chunkPosition();
            int tickDist = player.moonrise$getViewDistanceHolder().getViewDistances().tickViewDistance();
            if (tickDist == -1) tickDist = defaultTickDist;

            playerTickDistances[i] = tickDist;

            boundaries[i] = new Rectangle(
                pos.x() - tickDist, pos.z() - tickDist,
                pos.x() + tickDist, pos.z() + tickDist
            );
        }

        UnionFind uf = new UnionFind(playerCount);
        for (int i = 0; i < playerCount; i++) {
            for (int j = i + 1; j < playerCount; j++) {
                if (boundaries[i].intersects(boundaries[j])) {
                    uf.union(i, j);
                }
            }
        }

        Int2IntOpenHashMap rootToGroup = new Int2IntOpenHashMap(playerCount);
        rootToGroup.defaultReturnValue(-1);
        ObjectArrayList<IntArrayList> groups = new ObjectArrayList<>();

        for (int i = 0; i < playerCount; i++) {
            int root = uf.find(i);
            int groupIdx = rootToGroup.get(root);
            if (groupIdx == -1) {
                groupIdx = groups.size();
                rootToGroup.put(root, groupIdx);
                groups.add(new IntArrayList(1));
            }
            groups.get(groupIdx).add(i);
        }

        ObjectArrayList<RegionData> regions = new ObjectArrayList<>(groups.size());

        int totalEstimatedChunks = 0;

        for (IntArrayList group : groups) {
            if (group.isEmpty()) continue;

            LongOpenHashSet groupChunks = new LongOpenHashSet(defaultAmountOfChunks);

            for (int i = 0; i < group.size(); i++) {
                int playerIdx = group.getInt(i);
                ServerPlayer player = players.get(playerIdx);
                ChunkPos center = player.chunkPosition();
                int dist = playerTickDistances[playerIdx];

                for (int dx = -dist; dx <= dist; dx++) {
                    for (int dz = -dist; dz <= dist; dz++) {
                        groupChunks.add(CoordinateUtils.getChunkKey(center.x() + dx, center.z() + dz));
                    }
                }
            }

            regions.add(new RegionData(groupChunks, ConcurrentHashMap.newKeySet(100), ConcurrentHashMap.newKeySet(4)));
            totalEstimatedChunks += groupChunks.size();
        }

        Long2IntOpenHashMap chunkToRegion = new Long2IntOpenHashMap(totalEstimatedChunks);
        chunkToRegion.defaultReturnValue(-1);

        for (int idx = 0; idx < regions.size(); idx++) {
            for (long key : regions.get(idx).chunks()) {
                chunkToRegion.put(key, idx);
            }
        }

        final Set<Entity> firstTick = ConcurrentHashMap.newKeySet();

        IteratorSafeOrderedReferenceSet<Entity> entities;
        synchronized (entities = getEntityTickList().entities) {
            entities.createRawIterator();

            try {
                final Entity[] rawList = entities.getListRaw();
                final int limit = entities.getListSize();
                Arrays.stream(rawList, 0, limit)
                    .parallel()
                    .filter(Objects::nonNull)
                    .forEach(entity -> {
                        long chunkKey = entity.chunkPosition().pack();
                        int regionIndex = chunkToRegion.get(chunkKey);
                        if (regionIndex != -1 && !mustTickOnMainThread(entity)) {
                            RegionData targetRegion = regions.get(regionIndex);
                            targetRegion.entities().add(entity);
                            if (entity instanceof ServerPlayer player) {
                                targetRegion.players().add(player);
                            }
                        } else {
                            firstTick.add(entity);
                        }
                    });
            } finally {
                entities.finishRawIterator();
            }
        }

        regions.sort(Comparator.<RegionData>comparingDouble(r -> r.players().stream().map(p -> p.avgTickTimeNanos.average().orElse(-1)).max(Comparator.naturalOrder()).orElse(-1d)).reversed());
        return new TickPair(regions.toArray(new RegionData[0]), firstTick);
    }

    private static boolean mustTickOnMainThread(Entity entity) {
        return entity instanceof net.minecraft.world.entity.item.PrimedTnt;
    }

    private void tickEntity(Entity entity) {
        if (!entity.isRemoved() && !level.tickRateManager().isEntityFrozen(entity)) {
            if (entity.moonrise$isUpdatingSectionStatus()) {
                LOGGER.info("Skipping tick for entity {} as it is in the process of updating section status", entity);
                return;
            }
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

    @Override
    public void close() throws IOException {
        avgTimeLogger.close();
        super.close();
    }

    record RegionData(LongOpenHashSet chunks, Set<Entity> entities, Set<ServerPlayer> players) {
        public boolean isEmpty() {
            return chunks.isEmpty();
        }
    }

    record Rectangle(int minX, int minZ, int maxX, int maxZ) {
        boolean intersects(Rectangle other) {
            return !(this.maxX < other.minX ||
                this.minX > other.maxX ||
                this.maxZ < other.minZ ||
                this.minZ > other.maxZ);
        }
    }

    record TickPair(RegionData[] regions, Set<Entity> entities) {
    }
}
