package net.caffeinemc.mods.lithium.common.ai.non_poi_block_search;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.caffeinemc.mods.lithium.common.util.collections.FixedChunkAccessSectionBitBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Block searches over blocks that are not POIs. The sections of the search box are checked first with
 * {@link LevelChunkSection#maybeHas}: a search where no section can hold the block returns at once, and the
 * {@code MoveToBlockGoal} search only reads blocks in sections that can. The result is the position vanilla's order
 * would find first.
 *
 * @author jcw780
 */
@NullMarked
public final class NonPoiBlockSearch {
    private NonPoiBlockSearch() {
    }

    /**
     * Pre-check of {@code findBlocksInBoxByManhattanDistance(origin, horizontalRange, verticalRange)} without loading
     * chunks: an unloaded chunk counts as a possible match, so the caller then runs the vanilla search as before.
     *
     * @return true if no block in the box can match
     */
    public static boolean hasNoMatch(final LevelReader level, final BlockPos origin, final int horizontalRange, final int verticalRange, final Predicate<BlockState> predicate) {
        // vanilla reports air outside the build height
        if (predicate.test(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())) {
            return false;
        }
        final FixedChunkAccessSectionBitBuffer sections = new FixedChunkAccessSectionBitBuffer(origin, horizontalRange, verticalRange);
        for (int x = sections.xMin, xEnd = sections.xMin + sections.xLength; x < xEnd; ++x) {
            for (int z = sections.zMin, zEnd = sections.zMin + sections.zLength; z < zEnd; ++z) {
                final ChunkAccess chunk = level.getChunkIfLoadedImmediately(x, z);
                if (chunk == null) {
                    return false;
                }
                if (markSections(sections, chunk, x, z, level.getMinSectionY(), predicate)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The search of {@code MoveToBlockGoal#findNearestBlock} for goals whose target test never loads chunks.
     *
     * @param center        the mob's block position one block down
     * @param requiredBlock the block-state part of the target test, used with {@code maybeHas}
     * @param isValidTarget the full target test; it gets its own position object and may move it
     * @return the position vanilla's order finds first, or null
     */
    public static @Nullable BlockPos findNearestBlock(final PathfinderMob mob, final BlockPos center, final int searchRange, final int verticalSearchStart,
                                                      final int verticalSearchRange, final Predicate<BlockState> requiredBlock,
                                                      final BiPredicate<ChunkAccess, BlockPos.MutableBlockPos> isValidTarget) {
        final LevelReader level = mob.level();
        final int ringMaxStart = searchRange - 1;
        final int minSectionY = level.getMinSectionY();
        final FixedChunkAccessSectionBitBuffer sections = new FixedChunkAccessSectionBitBuffer(center, ringMaxStart, verticalSearchRange);
        final LongArrayList chunksMaybeWithBlock = new LongArrayList(sections.numChunks);
        for (int x = sections.xMin, xEnd = sections.xMin + sections.xLength; x < xEnd; ++x) {
            for (int z = sections.zMin, zEnd = sections.zMin + sections.zLength; z < zEnd; ++z) {
                // vanilla's target test treats an unloaded chunk as no target, and loads nothing
                final ChunkAccess chunk = level.getChunkIfLoadedImmediately(x, z);
                if (chunk != null && markSections(sections, chunk, x, z, minSectionY, requiredBlock)) {
                    sections.setChunkAccess(x, z, chunk);
                    chunksMaybeWithBlock.add(ChunkPos.pack(x, z));
                }
            }
        }
        if (chunksMaybeWithBlock.isEmpty()) {
            return null;
        }
        // chunks by the earliest position they hold in vanilla's order: once one is found, later chunks cannot be closer
        chunksMaybeWithBlock.unstableSort((a, b) -> Integer.compare(minimumSortOrderOfChunk(center, ChunkPos.getX(a), ChunkPos.getZ(a)), minimumSortOrderOfChunk(center, ChunkPos.getX(b), ChunkPos.getZ(b))));

        final int minY = level.getMinY();
        final int maxY = level.getMaxY();
        final BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();
        final int centerX = center.getX();
        final int centerZ = center.getZ();
        for (int layer = verticalSearchStart; layer <= verticalSearchRange; layer = layer > 0 ? -layer : 1 - layer) {
            final int y = center.getY() + layer;
            if (y < minY || y > maxY) {
                continue;
            }
            final int chunkY = SectionPos.blockToSectionCoord(y);
            int closestFound = Integer.MAX_VALUE;
            int foundX = 0;
            int foundZ = 0;
            int ringMax = ringMaxStart;
            for (int i = 0, size = chunksMaybeWithBlock.size(); i < size; ++i) {
                final long chunkPos = chunksMaybeWithBlock.getLong(i);
                final int chunkX = ChunkPos.getX(chunkPos);
                final int chunkZ = ChunkPos.getZ(chunkPos);
                if (closestFound < minimumSortOrderOfChunk(center, chunkX, chunkZ)) {
                    break;
                }
                if (!sections.getChunkSectionBit(chunkX, chunkY, chunkZ)) {
                    continue;
                }
                final ChunkAccess chunk = sections.getChunkAccess(chunkX, chunkZ);
                final LevelChunkSection section = chunk.getSections()[chunkY - minSectionY];
                final int chunkBlockX = SectionPos.sectionToBlockCoord(chunkX);
                final int chunkBlockZ = SectionPos.sectionToBlockCoord(chunkZ);
                int xMin = Math.max(centerX - ringMax, chunkBlockX);
                int xMax = Math.min(centerX + ringMax, chunkBlockX + 15);
                final int zMin = Math.max(centerZ - ringMax, chunkBlockZ);
                int zMax = Math.min(centerZ + ringMax, chunkBlockZ + 15);
                for (int z = zMin; z <= zMax; ++z) {
                    for (int x = xMin; x <= xMax; ++x) {
                        final int dX = x - centerX;
                        final int dZ = z - centerZ;
                        final int ring = Math.max(Math.abs(dX), Math.abs(dZ));
                        final int order = sortOrder(ring, dX, dZ);
                        if (order < closestFound
                            && mob.isWithinHome(currentPos.set(x, y, z))
                            && requiredBlock.test(section.getBlockState(x & 15, y & 15, z & 15))
                            && isValidTarget.test(chunk, targetPos.set(x, y, z))) {
                            // nothing in a farther ring can come first any more
                            ringMax = ring;
                            xMin = Math.max(centerX - ringMax, chunkBlockX);
                            xMax = Math.min(centerX + ringMax, chunkBlockX + 15);
                            zMax = Math.min(centerZ + ringMax, chunkBlockZ + 15);
                            foundX = x;
                            foundZ = z;
                            closestFound = order;
                        }
                    }
                }
            }
            if (closestFound < Integer.MAX_VALUE) {
                return new BlockPos(foundX, y, foundZ);
            }
        }
        return null;
    }

    /**
     * The rest of {@code RemoveBlockGoal#isValidTarget} once the block itself matched: the two blocks above are air.
     */
    public static boolean hasAirAboveTwice(final ChunkAccess chunk, final BlockPos.MutableBlockPos pos) {
        return chunk.getBlockState(pos.move(0, 1, 0)).isAir() && chunk.getBlockState(pos.move(0, 1, 0)).isAir();
    }

    private static final ClassValue<Boolean> OVERRIDES_TARGET_TEST = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            for (Class<?> current = type; current != net.minecraft.world.entity.ai.goal.RemoveBlockGoal.class && current != null; current = current.getSuperclass()) {
                for (final java.lang.reflect.Method method : current.getDeclaredMethods()) {
                    if ((method.getName().equals("isValidTarget") && method.getParameterCount() == 2) || (method.getName().equals("findNearestBlock") && method.getParameterCount() == 0)) {
                        return true;
                    }
                }
            }
            return false;
        }
    };

    /**
     * @return true if a subclass (a plugin's goal) has its own target test or search, which only the vanilla path calls
     */
    public static boolean overridesTargetTest(final Class<?> goalClass) {
        return OVERRIDES_TARGET_TEST.get(goalClass);
    }

    private static boolean markSections(final FixedChunkAccessSectionBitBuffer sections, final ChunkAccess chunk, final int chunkX, final int chunkZ,
                                        final int minSectionY, final Predicate<BlockState> predicate) {
        final LevelChunkSection[] chunkSections = chunk.getSections();
        boolean any = false;
        for (int y = sections.yMin, yEnd = sections.yMin + sections.yLength; y < yEnd; ++y) {
            final int index = y - minSectionY;
            if (index >= 0 && index < chunkSections.length && chunkSections[index].maybeHas(predicate)) {
                sections.setChunkSectionBit(chunkX, y, chunkZ);
                any = true;
            }
        }
        return any;
    }

    public static int minimumSortOrderOfChunk(final BlockPos center, final int chunkX, final int chunkZ) {
        final int dX = closestBlockCoordInSection(center.getX(), chunkX) - center.getX();
        final int dZ = closestBlockCoordInSection(center.getZ(), chunkZ) - center.getZ();
        return sortOrder(Math.max(Math.abs(dX), Math.abs(dZ)), dX, dZ);
    }

    private static int closestBlockCoordInSection(final int blockCoord, final int sectionCoord) {
        final int minBlockInSection = SectionPos.sectionToBlockCoord(sectionCoord);
        return Math.min(Math.max(blockCoord, minBlockInSection), minBlockInSection + 15);
    }

    /**
     * Position of (dX, dZ) in vanilla's order within one layer: ring, then |dX| with + before -, then |dZ| with + before -.
     * The packing holds for horizontal ranges up to 128.
     */
    public static int sortOrder(final int ring, final int dX, final int dZ) {
        return (ring << 16 | Math.abs(dX) << 9 | Math.abs(dZ) << 1) - ((dX > 0 ? 1 : 0) << 8 | (dZ > 0 ? 1 : 0));
    }
}
