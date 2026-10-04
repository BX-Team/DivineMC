package org.bxteam.divinemc.async.rct;

import ca.spottedleaf.moonrise.common.list.IteratorSafeOrderedReferenceSet;
import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.datafixers.DataFixer;
import io.papermc.paper.entity.activation.ActivationRange;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class RegionizedChunkTicking extends ServerChunkCache {
    public static final Executor REGION_EXECUTOR = Executors.newFixedThreadPool(DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadCount,
        new NamedAgnosticThreadFactory<>("Region Ticking", TickThread.RegionTickThread::new, DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadPriority));
    public static final boolean CHECK_OWNERSHIP = Boolean.getBoolean("divinemc.rct.checkOwnership");
    private static final int TILE_SHIFT = 3;
    private static final int COLORS = 4;
    private static final int SERIAL_HOLD_TICKS = 100;
    private static final double DOMINANT_UNIT_SHARE = 0.9;
    private static final long MIN_PARALLEL_WORK_NANOS = 1_000_000L;
    private static final int TILE_EVICT_TICKS = 200;
    private static final int MIN_PROBE_GAP_TICKS = 300;
    private static final int PROBE_WARMUP_TICKS = 10;
    private static final int PROBE_TICKS = 100;
    private static final double SWITCH_MARGIN = 0.95;
    private static final int PROBE_ABORT_TICKS = 20;
    private static final double PROBE_ABORT_MARGIN = 1.1;
    private static final int VERIFY_TICKS = 40;
    private static final double REVERT_MARGIN = 1.05;
    private static final double DRIFT_RATIO = 1.3;
    private static final int PLAYERS_SETTLE_TICKS = 100;
    private static final ThreadLocal<Tile> CURRENT_TILE = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Boolean> REPORTED_VIOLATIONS = new ConcurrentHashMap<>();

    private final AvgTimeLogger avgTimeLogger;
    public final RollingLongBuffer avgTime = new RollingLongBuffer(100);
    private final Long2ObjectOpenHashMap<Tile> tiles = new Long2ObjectOpenHashMap<>();
    @SuppressWarnings("unchecked")
    private final ObjectArrayList<Tile>[] tilesByColor = new ObjectArrayList[COLORS];
    private final ObjectArrayList<Tile> regionUnits = new ObjectArrayList<>();
    @SuppressWarnings("unchecked")
    private final ObjectArrayList<Tile>[] regionPhases = new ObjectArrayList[] {this.regionUnits};
    private final Long2IntOpenHashMap tileOwner = new Long2IntOpenHashMap();
    private final ObjectArrayList<Tile> mainThreadTiles = new ObjectArrayList<>();
    private final ObjectArrayList<Tile> phaseUnits = new ObjectArrayList<>();
    private final ObjectArrayList<Entity> mainThreadEntities = new ObjectArrayList<>();
    private final RollingLongBuffer tileTimes = new RollingLongBuffer(PROBE_TICKS);
    private final RollingLongBuffer regionTimes = new RollingLongBuffer(PROBE_TICKS);
    private ObjectArrayList<Tile>[] phases;
    private int tickCount;
    private int serialTicksLeft;
    private boolean pendingEntityTick;
    private long blockPhaseNanos;
    private boolean regionMode;
    private boolean preferRegions = true;
    private boolean probing;
    private int probeTick;
    private int parallelTicksSinceProbe;
    private int verifyTicksLeft;
    private double verifyBaselineNanos;
    private double decisionNanos;
    private boolean degenerateFallback;
    private int lastPlayerCount;
    private int playersSettleTick = -1;

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
        for (int color = 0; color < COLORS; color++) {
            this.tilesByColor[color] = new ObjectArrayList<>();
        }
        this.phases = this.tilesByColor;
        this.tileOwner.defaultReturnValue(-1);
    }

    @Override
    protected void iterateTickingChunksFaster(final @NotNull CompletableFuture<Void> spawns) {
        this.tickCount++;
        if (this.serialTicksLeft > 0) {
            this.serialTicksLeft--;
            super.iterateTickingChunksFaster(spawns);
            return;
        }

        final long start = System.nanoTime();
        this.assignTiles();
        this.regionMode = this.chooseRegionMode();
        if (this.regionMode) {
            this.groupTilesIntoRegions();
            if (!this.isWorthParallel()) {
                this.abandonRegionMode();
            }
        }
        if (!this.regionMode) {
            this.phases = this.tilesByColor;
            this.mainThreadTiles.clear();
        }

        if (!this.isWorthParallel()) {
            this.serialTicksLeft = SERIAL_HOLD_TICKS;
            for (final Tile tile : this.tiles.values()) {
                tile.lastCost = 0;
            }
            this.logUnits("Ticking serially for " + SERIAL_HOLD_TICKS + " ticks\n");
            super.iterateTickingChunksFaster(spawns);
            return;
        }

        ActivationRange.activateEntities(this.level); // Paper - EAR

        final int randomTickSpeed = this.level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        for (final ObjectArrayList<Tile> phase : this.phases) {
            this.runPhase(phase, false, randomTickSpeed);
        }
        for (final Tile tile : this.mainThreadTiles) {
            this.tickTileBlocks(tile, randomTickSpeed);
        }

        spawns.join();

        this.pendingEntityTick = true;
        this.blockPhaseNanos = System.nanoTime() - start;
    }

    public boolean hasPendingEntityTick() {
        return this.pendingEntityTick;
    }

    public void tickEntitiesParallel() {
        if (!this.pendingEntityTick) {
            return;
        }
        this.pendingEntityTick = false;

        final long start = System.nanoTime();
        org.bxteam.divinemc.async.sensing.ParallelSensorTicker.preTickSensors(this.level);

        this.assignEntities();
        for (final ObjectArrayList<Tile> phase : this.phases) {
            this.runPhase(phase, true, 0);
        }
        for (final Tile tile : this.mainThreadTiles) {
            this.tickTileEntities(tile);
        }
        for (final Entity entity : this.mainThreadEntities) {
            this.tickEntity(entity);
        }
        this.mainThreadEntities.clear();

        this.finishUnits();
        final long total = this.blockPhaseNanos + (System.nanoTime() - start);
        this.avgTime.add(total);
        final boolean warmingUp = this.probing ? this.probeTick <= PROBE_WARMUP_TICKS : this.verifyTicksLeft > VERIFY_TICKS;
        if (!warmingUp) {
            (this.regionMode ? this.regionTimes : this.tileTimes).add(total);
        }
    }

    private boolean chooseRegionMode() {
        if (this.probing) {
            if (++this.probeTick <= PROBE_WARMUP_TICKS + PROBE_TICKS && !this.isProbeClearlyLosing()) {
                return !this.preferRegions;
            }
            this.finishProbe();
            return this.preferRegions;
        }
        if (this.verifyTicksLeft > 0) {
            if (--this.verifyTicksLeft == 0) {
                this.finishVerify();
            }
            return this.preferRegions;
        }

        this.parallelTicksSinceProbe++;
        final boolean playersSettled = this.havePlayersSettled();
        if (playersSettled && this.degenerateFallback && this.lastPlayerCount >= 2) {
            this.playersSettleTick = -1;
            this.degenerateFallback = false;
            this.switchLayout(true, this.tileTimes.average().orElse(Double.MAX_VALUE), Double.NaN);
            return this.preferRegions;
        }
        if (this.parallelTicksSinceProbe < MIN_PROBE_GAP_TICKS) {
            return this.preferRegions;
        }
        if (playersSettled || this.hasLoadDrifted()) {
            this.playersSettleTick = -1;
            this.startProbe();
        }
        return this.probing != this.preferRegions;
    }

    private RollingLongBuffer preferredTimes() {
        return this.preferRegions ? this.regionTimes : this.tileTimes;
    }

    private RollingLongBuffer probedTimes() {
        return this.preferRegions ? this.tileTimes : this.regionTimes;
    }

    private boolean havePlayersSettled() {
        final int players = this.level.players().size();
        if (players != this.lastPlayerCount) {
            this.lastPlayerCount = players;
            this.playersSettleTick = this.tickCount + PLAYERS_SETTLE_TICKS;
            return false;
        }
        return this.playersSettleTick != -1 && this.tickCount >= this.playersSettleTick;
    }

    private boolean hasLoadDrifted() {
        final RollingLongBuffer preferred = this.preferredTimes();
        if (preferred.size() < PROBE_TICKS) {
            return false;
        }
        final double current = preferred.average().orElse(0);
        if (this.decisionNanos <= 0) {
            this.decisionNanos = current;
            return false;
        }
        return current > this.decisionNanos * DRIFT_RATIO || current * DRIFT_RATIO < this.decisionNanos;
    }

    private void startProbe() {
        this.probing = true;
        this.probeTick = 1;
        this.probedTimes().clear();
    }

    private boolean isProbeClearlyLosing() {
        final RollingLongBuffer probed = this.probedTimes();
        if (probed.size() < PROBE_ABORT_TICKS) {
            return false;
        }
        final double preferredNanos = this.preferredTimes().average().orElse(Double.MAX_VALUE);
        return probed.average().orElse(0) > preferredNanos * PROBE_ABORT_MARGIN;
    }

    private void finishProbe() {
        this.probing = false;
        this.degenerateFallback = false;
        this.parallelTicksSinceProbe = 0;
        final double tileNanos = this.tileTimes.average().orElse(Double.MAX_VALUE);
        final double regionNanos = this.regionTimes.average().orElse(Double.MAX_VALUE);
        final boolean switchToRegions = !this.preferRegions && regionNanos < tileNanos * SWITCH_MARGIN;
        final boolean switchToTiles = this.preferRegions && tileNanos < regionNanos * SWITCH_MARGIN;
        if (switchToRegions || switchToTiles) {
            this.switchLayout(switchToRegions, tileNanos, regionNanos);
            return;
        }
        this.decisionNanos = this.preferredTimes().average().orElse(0);
    }

    private void switchLayout(final boolean regions, final double tileNanos, final double regionNanos) {
        this.verifyBaselineNanos = regions ? tileNanos : regionNanos;
        this.preferRegions = regions;
        this.preferredTimes().clear();
        this.verifyTicksLeft = PROBE_WARMUP_TICKS + VERIFY_TICKS;
        this.parallelTicksSinceProbe = 0;
        this.logLayout("Switched to", tileNanos, regionNanos);
    }

    private void finishVerify() {
        final double current = this.preferredTimes().average().orElse(Double.MAX_VALUE);
        if (current <= this.verifyBaselineNanos * REVERT_MARGIN) {
            this.decisionNanos = current;
            return;
        }
        this.preferRegions = !this.preferRegions;
        this.decisionNanos = this.verifyBaselineNanos;
        this.parallelTicksSinceProbe = 0;
        this.logLayout("Reverted to", this.tileTimes.average().orElse(0), this.regionTimes.average().orElse(0));
    }

    private void abandonRegionMode() {
        this.regionMode = false;
        if (this.probing) {
            this.probing = false;
            this.parallelTicksSinceProbe = 0;
        } else if (this.preferRegions) {
            this.preferRegions = false;
            this.verifyTicksLeft = 0;
            this.degenerateFallback = true;
            this.decisionNanos = 0;
            this.logLayout("Switched to", this.tileTimes.average().orElse(Double.MAX_VALUE), Double.MAX_VALUE);
        }
    }

    private void logLayout(final String action, final double tileNanos, final double regionNanos) {
        final String text = action + " " + (this.preferRegions ? "player regions" : "tiles")
            + " (tiles " + formatNanos(tileNanos, "unmeasured") + ", player regions " + formatNanos(regionNanos, "degenerate") + ")\n";
        REGION_EXECUTOR.execute(() -> this.avgTimeLogger.logTickTime(text));
    }

    private static String formatNanos(final double nanos, final String maxLabel) {
        if (Double.isNaN(nanos)) {
            return "unmeasured";
        }
        return nanos == Double.MAX_VALUE ? maxLabel : Math.round(nanos / 1000.0) + " us";
    }

    private void assignTiles() {
        for (int color = 0; color < COLORS; color++) {
            this.tilesByColor[color].clear();
        }

        final ReferenceList<LevelChunk> tickingChunks = this.level.moonrise$getEntityTickingChunks();
        final LevelChunk[] raw = tickingChunks.getRawDataUnchecked();
        final int size = tickingChunks.size();
        for (int i = 0; i < size; i++) {
            final LevelChunk chunk = raw[i];
            final int tileX = chunk.getPos().x() >> TILE_SHIFT;
            final int tileZ = chunk.getPos().z() >> TILE_SHIFT;
            final long key = CoordinateUtils.getChunkKey(tileX, tileZ);
            Tile tile = this.tiles.get(key);
            if (tile == null) {
                tile = new Tile(this.level, key, color(tileX, tileZ));
                this.tiles.put(key, tile);
            }
            if (tile.lastAssigned != this.tickCount) {
                tile.lastAssigned = this.tickCount;
                tile.chunks.clear();
                tile.entities.clear();
                tile.players.clear();
                this.tilesByColor[tile.color].add(tile);
            }
            tile.chunks.add(chunk);
        }

        if (this.tickCount % TILE_EVICT_TICKS == 0) {
            for (final ObjectIterator<Tile> iterator = this.tiles.values().iterator(); iterator.hasNext(); ) {
                if (this.tickCount - iterator.next().lastAssigned > TILE_EVICT_TICKS) {
                    iterator.remove();
                }
            }
        }
    }

    private void groupTilesIntoRegions() {
        this.phases = this.regionPhases;
        this.regionUnits.clear();
        this.mainThreadTiles.clear();
        this.tileOwner.clear();

        final List<ServerPlayer> players = this.level.players();
        final int count = players.size();
        final int defaultDistance = this.level.moonrise$getViewDistanceHolder().getViewDistances().tickViewDistance();
        final UnionFind groups = new UnionFind(count);
        for (int i = 0; i < count; i++) {
            final ServerPlayer player = players.get(i);
            final int playerDistance = player.moonrise$getViewDistanceHolder().getViewDistances().tickViewDistance();
            final int distance = playerDistance == -1 ? defaultDistance : playerDistance;
            final int chunkX = player.chunkPosition().x();
            final int chunkZ = player.chunkPosition().z();
            for (int tileX = (chunkX - distance) >> TILE_SHIFT, maxX = (chunkX + distance) >> TILE_SHIFT; tileX <= maxX; tileX++) {
                for (int tileZ = (chunkZ - distance) >> TILE_SHIFT, maxZ = (chunkZ + distance) >> TILE_SHIFT; tileZ <= maxZ; tileZ++) {
                    final long key = CoordinateUtils.getChunkKey(tileX, tileZ);
                    final Tile tile = this.tiles.get(key);
                    if (tile == null || tile.lastAssigned != this.tickCount) {
                        continue;
                    }
                    final int owner = this.tileOwner.putIfAbsent(key, i);
                    if (owner != -1) {
                        groups.union(owner, i);
                    }
                }
            }
        }

        // Regions on touching tiles would tick side by side with nothing between their border entities, so merge
        // them. Every pair of regions is then at least one unowned tile apart, which runs on the main thread.
        for (final ObjectIterator<Long2IntMap.Entry> iterator = this.tileOwner.long2IntEntrySet().fastIterator(); iterator.hasNext(); ) {
            final Long2IntMap.Entry entry = iterator.next();
            final int tileX = CoordinateUtils.getChunkX(entry.getLongKey());
            final int tileZ = CoordinateUtils.getChunkZ(entry.getLongKey());
            this.unionNeighbour(groups, entry.getIntValue(), tileX + 1, tileZ - 1);
            this.unionNeighbour(groups, entry.getIntValue(), tileX + 1, tileZ);
            this.unionNeighbour(groups, entry.getIntValue(), tileX + 1, tileZ + 1);
            this.unionNeighbour(groups, entry.getIntValue(), tileX, tileZ + 1);
        }

        final int[] unitByRoot = new int[count];
        Arrays.fill(unitByRoot, -1);
        for (final ObjectIterator<Long2IntMap.Entry> iterator = this.tileOwner.long2IntEntrySet().fastIterator(); iterator.hasNext(); ) {
            final Long2IntMap.Entry entry = iterator.next();
            final int root = groups.find(entry.getIntValue());
            if (unitByRoot[root] == -1) {
                unitByRoot[root] = this.regionUnits.size();
                this.regionUnits.add(Tile.region(this.level, this.regionUnits.size()));
            }
            this.regionUnits.get(unitByRoot[root]).members.add(this.tiles.get(entry.getLongKey()));
        }

        for (final ObjectArrayList<Tile> colorTiles : this.tilesByColor) {
            for (final Tile tile : colorTiles) {
                if (!this.tileOwner.containsKey(tile.key)) {
                    this.mainThreadTiles.add(tile);
                }
            }
        }
    }

    private void unionNeighbour(final UnionFind groups, final int owner, final int tileX, final int tileZ) {
        final int neighbour = this.tileOwner.get(CoordinateUtils.getChunkKey(tileX, tileZ));
        if (neighbour != -1) {
            groups.union(owner, neighbour);
        }
    }

    private boolean isWorthParallel() {
        int nonEmpty = 0;
        long totalChunks = 0;
        long largestChunks = 0;
        long lastWork = 0;
        for (final ObjectArrayList<Tile> phase : this.phases) {
            for (final Tile unit : phase) {
                final int chunks = unit.chunkCount();
                if (chunks == 0) {
                    continue;
                }
                nonEmpty++;
                totalChunks += chunks;
                largestChunks = Math.max(largestChunks, chunks);
                lastWork += unit.lastCost;
            }
        }
        if (nonEmpty < 2 || largestChunks >= totalChunks * DOMINANT_UNIT_SHARE) {
            return false;
        }
        return lastWork == 0 || lastWork >= MIN_PARALLEL_WORK_NANOS;
    }

    private void assignEntities() {
        final IteratorSafeOrderedReferenceSet<Entity> entities = this.getEntityTickList().entities;
        synchronized (entities) {
            entities.createRawIterator();
            try {
                final Entity[] raw = entities.getListRaw();
                final int limit = entities.getListSize();
                for (int i = 0; i < limit; i++) {
                    final Entity entity = raw[i];
                    if (entity == null) {
                        continue;
                    }
                    final Tile tile = this.tiles.get(CoordinateUtils.getChunkKey(entity.chunkPosition().x() >> TILE_SHIFT, entity.chunkPosition().z() >> TILE_SHIFT));
                    if (tile == null || tile.lastAssigned != this.tickCount || mustTickOnMainThread(entity)) {
                        this.mainThreadEntities.add(entity);
                        continue;
                    }
                    tile.entities.add(entity);
                    if (entity instanceof ServerPlayer player) {
                        tile.players.add(player);
                    }
                }
            } finally {
                entities.finishRawIterator();
            }
        }
    }

    private void runPhase(final ObjectArrayList<Tile> units, final boolean entities, final int randomTickSpeed) {
        final ObjectArrayList<Tile> work = this.phaseUnits;
        work.clear();
        for (final Tile unit : units) {
            if (entities ? unit.entityCount() > 0 : unit.chunkCount() > 0) {
                work.add(unit);
            }
        }
        final int count = work.size();
        if (count == 0) {
            return;
        }
        work.unstableSort((a, b) -> Long.compare(b.lastCost, a.lastCost));

        final Tile[] ordered = work.toArray(new Tile[0]);
        final boolean checkOwnership = CHECK_OWNERSHIP && !this.regionMode;
        final AtomicInteger next = new AtomicInteger();
        final int workers = Math.min(count, DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadCount);
        final CompletableFuture<?>[] futures = new CompletableFuture<?>[workers];
        for (int worker = 0; worker < workers; worker++) {
            futures[worker] = CompletableFuture.runAsync(() -> runInLevel(this.level, () -> {
                int index;
                while ((index = next.getAndIncrement()) < ordered.length) {
                    final Tile unit = ordered[index];
                    if (checkOwnership) {
                        CURRENT_TILE.set(unit);
                    }
                    try {
                        if (entities) {
                            this.tickTileEntities(unit);
                        } else {
                            this.tickTileBlocks(unit, randomTickSpeed);
                        }
                    } catch (final Throwable throwable) {
                        LOGGER.error("Exception while ticking region unit {}", unit.describe(), throwable);
                    } finally {
                        if (checkOwnership) {
                            CURRENT_TILE.remove();
                        }
                    }
                }
            }), REGION_EXECUTOR);
        }
        for (final CompletableFuture<?> future : futures) {
            future.join();
        }
    }

    private void tickTileBlocks(final Tile unit, final int randomTickSpeed) {
        final long start = System.nanoTime();
        if (unit.members == null) {
            for (final LevelChunk chunk : unit.chunks) {
                this.level.tickChunk(chunk, randomTickSpeed);
            }
        } else {
            for (final Tile tile : unit.members) {
                for (final LevelChunk chunk : tile.chunks) {
                    this.level.tickChunk(chunk, randomTickSpeed);
                }
            }
        }
        unit.blockNanos = System.nanoTime() - start;
    }

    private void tickTileEntities(final Tile unit) {
        final long start = System.nanoTime();
        if (unit.members == null) {
            for (final Entity entity : unit.entities) {
                this.tickEntity(entity);
            }
        } else {
            for (final Tile tile : unit.members) {
                for (final Entity entity : tile.entities) {
                    this.tickEntity(entity);
                }
            }
        }
        unit.entityNanos = System.nanoTime() - start;
    }

    private void finishUnits() {
        for (final ObjectArrayList<Tile> phase : this.phases) {
            for (final Tile unit : phase) {
                final int chunks = unit.chunkCount();
                final int entities = unit.entityCount();
                final long cost = unit.blockNanos + (entities == 0 ? 0 : unit.entityNanos);
                unit.lastCost = cost;
                final int hash = unit.members == null ? Long.hashCode(unit.key) : ~Long.hashCode(unit.key);
                if (unit.members == null) {
                    this.recordPlayers(unit, unit, hash, chunks, entities, cost);
                } else {
                    for (final Tile tile : unit.members) {
                        this.recordPlayers(tile, unit, hash, chunks, entities, cost);
                    }
                }
                unit.entityNanos = 0;
            }
        }
        if (this.tickCount % 100 == 0) {
            this.logUnits("");
        }
    }

    private void recordPlayers(final Tile tile, final Tile unit, final int hash, final int chunks, final int entities, final long cost) {
        for (final ServerPlayer player : tile.players) {
            player.regionBlockTickNanos = unit.blockNanos;
            player.lastRegionChunkSize = chunks;
            player.lastRegionEntityAmount = entities;
            player.regionHash = hash;
            player.avgTickTimeNanos.add(cost);
        }
    }

    private void logUnits(final String suffix) {
        final StringBuilder sb = new StringBuilder();
        for (final ObjectArrayList<Tile> phase : this.phases) {
            for (final Tile unit : phase) {
                sb.append("Region with ").append(unit.chunkCount()).append(" chunks and ").append(unit.entityCount()).append(" entities ticked for Players:\n");
                if (unit.members == null) {
                    this.appendPlayers(sb, unit);
                } else {
                    for (final Tile tile : unit.members) {
                        this.appendPlayers(sb, tile);
                    }
                }
            }
        }
        if (sb.isEmpty()) {
            return;
        }
        sb.append(suffix);
        final String text = sb.toString();
        REGION_EXECUTOR.execute(() -> this.avgTimeLogger.logTickTime(text));
    }

    private void appendPlayers(final StringBuilder sb, final Tile tile) {
        for (final ServerPlayer player : tile.players) {
            final long avgNanos = Math.round(player.avgTickTimeNanos.average().orElse(0));
            sb.append("- ").append(player.displayName).append(" avg region tick time: ")
                .append(avgNanos / 1_000_000).append(" ms ")
                .append((avgNanos % 1_000_000) / 1_000).append(" us ")
                .append(avgNanos % 1_000).append(" ns\n");
        }
    }

    /**
     * Runs {@code task} with the current region worker marked as ticking {@code level}, so that thread checks done
     * under parallel world ticking accept this level and reject every other one.
     */
    public static void runInLevel(final ServerLevel level, final Runnable task) {
        if (!(Thread.currentThread() instanceof TickThread.RegionTickThread thread)) {
            task.run();
            return;
        }
        final ServerLevel previous = thread.currentlyTickingServerLevel;
        thread.currentlyTickingServerLevel = level;
        try {
            task.run();
        } finally {
            thread.currentlyTickingServerLevel = previous;
        }
    }

    public static void checkOwnership(final Level level, final int chunkX, final int chunkZ) {
        final Tile own = CURRENT_TILE.get();
        if (own == null || own.level != level) {
            return;
        }
        final int tileX = chunkX >> TILE_SHIFT;
        final int tileZ = chunkZ >> TILE_SHIFT;
        if (CoordinateUtils.getChunkKey(tileX, tileZ) == own.key || color(tileX, tileZ) != own.color) {
            return;
        }
        final Throwable trace = new Throwable("write from tile " + own.describe()
            + " into tile " + tileX + "," + tileZ + " of the same colour phase in " + level);
        final StackTraceElement[] stack = trace.getStackTrace();
        final String site = stack.length > 2 ? stack[1] + " <- " + stack[2] : String.valueOf(stack.length);
        if (REPORTED_VIOLATIONS.putIfAbsent(site, Boolean.TRUE) == null) {
            LOGGER.warn("Regionized chunk ticking ownership violation", trace);
        }
    }

    private static int color(final int tileX, final int tileZ) {
        return (tileX & 1) | ((tileZ & 1) << 1);
    }

    private static boolean mustTickOnMainThread(Entity entity) {
        return entity instanceof net.minecraft.world.entity.item.PrimedTnt
            // runs arbitrary commands, which can reach anything in any world
            || entity instanceof net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock;
    }

    private void tickEntity(Entity entity) {
        entity.activatedPriorityReset = false; // DivineMC - Dynamic Activation of Brain - matches the non-RCT entity tick path
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

    private static final class Tile {
        private final ServerLevel level;
        private final long key;
        private final int color;
        private final ObjectArrayList<LevelChunk> chunks = new ObjectArrayList<>();
        private final ObjectArrayList<Entity> entities = new ObjectArrayList<>();
        private final ObjectArrayList<ServerPlayer> players = new ObjectArrayList<>();
        private final ObjectArrayList<Tile> members;
        private int lastAssigned = -1;
        private long blockNanos;
        private long entityNanos;
        private long lastCost;

        private Tile(final ServerLevel level, final long key, final int color) {
            this(level, key, color, null);
        }

        private Tile(final ServerLevel level, final long key, final int color, final ObjectArrayList<Tile> members) {
            this.level = level;
            this.key = key;
            this.color = color;
            this.members = members;
        }

        private static Tile region(final ServerLevel level, final int index) {
            return new Tile(level, index, -1, new ObjectArrayList<>());
        }

        private int chunkCount() {
            if (this.members == null) {
                return this.chunks.size();
            }
            int count = 0;
            for (final Tile tile : this.members) {
                count += tile.chunks.size();
            }
            return count;
        }

        private int entityCount() {
            if (this.members == null) {
                return this.entities.size();
            }
            int count = 0;
            for (final Tile tile : this.members) {
                count += tile.entities.size();
            }
            return count;
        }

        private String describe() {
            return this.members == null
                ? CoordinateUtils.getChunkX(this.key) + "," + CoordinateUtils.getChunkZ(this.key)
                : "player region " + this.key + " (" + this.members.size() + " tiles)";
        }
    }
}
