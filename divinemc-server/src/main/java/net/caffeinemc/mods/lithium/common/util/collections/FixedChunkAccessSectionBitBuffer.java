package net.caffeinemc.mods.lithium.common.util.collections;

import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The chunks and sections of a box: one cached chunk per column and one flag per section.
 *
 * @author jcw780
 */
@NullMarked
public final class FixedChunkAccessSectionBitBuffer {
    public final int xMin, yMin, zMin;
    public final int xLength, yLength, zLength, numChunks;
    private final BitSet chunkSectionBits;
    private final @Nullable ChunkAccess[] chunkAccesses;

    public FixedChunkAccessSectionBitBuffer(final BlockPos center, final int horizontalRangeInclusive, final int verticalRangeInclusive) {
        this.xMin = SectionPos.blockToSectionCoord(center.getX() - horizontalRangeInclusive);
        this.yMin = SectionPos.blockToSectionCoord(center.getY() - verticalRangeInclusive);
        this.zMin = SectionPos.blockToSectionCoord(center.getZ() - horizontalRangeInclusive);
        this.xLength = SectionPos.blockToSectionCoord(center.getX() + horizontalRangeInclusive) - this.xMin + 1;
        this.yLength = SectionPos.blockToSectionCoord(center.getY() + verticalRangeInclusive) - this.yMin + 1;
        this.zLength = SectionPos.blockToSectionCoord(center.getZ() + horizontalRangeInclusive) - this.zMin + 1;
        this.numChunks = this.xLength * this.zLength;
        this.chunkSectionBits = new BitSet(this.numChunks * this.yLength);
        this.chunkAccesses = new ChunkAccess[this.numChunks];
    }

    private int getSectionIndex(final int x, final int y, final int z) {
        return ((x - this.xMin) * this.zLength + (z - this.zMin)) * this.yLength + (y - this.yMin);
    }

    private int getChunkIndex(final int x, final int z) {
        return (x - this.xMin) * this.zLength + (z - this.zMin);
    }

    public boolean getChunkSectionBit(final int chunkX, final int chunkY, final int chunkZ) {
        return this.chunkSectionBits.get(this.getSectionIndex(chunkX, chunkY, chunkZ));
    }

    public void setChunkSectionBit(final int chunkX, final int chunkY, final int chunkZ) {
        this.chunkSectionBits.set(this.getSectionIndex(chunkX, chunkY, chunkZ));
    }

    public @Nullable ChunkAccess getChunkAccess(final int chunkX, final int chunkZ) {
        return this.chunkAccesses[this.getChunkIndex(chunkX, chunkZ)];
    }

    public void setChunkAccess(final int chunkX, final int chunkZ, final ChunkAccess chunkAccess) {
        this.chunkAccesses[this.getChunkIndex(chunkX, chunkZ)] = chunkAccess;
    }

    public boolean hasNoTrueChunkSections() {
        return this.chunkSectionBits.isEmpty();
    }

    public static long chunkPos(final int chunkX, final int chunkZ) {
        return ChunkPos.pack(chunkX, chunkZ);
    }
}
