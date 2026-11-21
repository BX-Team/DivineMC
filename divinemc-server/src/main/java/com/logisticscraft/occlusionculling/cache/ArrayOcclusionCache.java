package com.logisticscraft.occlusionculling.cache;

import java.util.Arrays;

public class ArrayOcclusionCache implements OcclusionCache {

    private static final int BLOCK_SHIFT = 6;

    private final int reachX2;
    private final byte[] cache;
    // a reset clears only the 64-byte blocks written since the last one
    private final long[] dirtyBlocks;
    private int positionKey;
    private int entry;
    private int offset;

    public ArrayOcclusionCache(int reach) {
        this.reachX2 = reach * 2;
        this.cache = new byte[(reachX2 * reachX2 * reachX2) / 4];
        final int blocks = (this.cache.length + (1 << BLOCK_SHIFT) - 1) >>> BLOCK_SHIFT;
        this.dirtyBlocks = new long[(blocks + 63) >>> 6];
    }

    @Override
    public void resetCache() {
        final long[] dirtyBlocks = this.dirtyBlocks;
        for (int word = 0; word < dirtyBlocks.length; ++word) {
            long bits = dirtyBlocks[word];
            if (bits == 0L) {
                continue;
            }
            dirtyBlocks[word] = 0L;
            do {
                final int from = ((word << 6) | Long.numberOfTrailingZeros(bits)) << BLOCK_SHIFT;
                Arrays.fill(cache, from, Math.min(from + (1 << BLOCK_SHIFT), cache.length), (byte) 0);
                bits &= bits - 1;
            } while (bits != 0L);
        }
    }

    private void markDirty(final int entry) {
        final int block = entry >>> BLOCK_SHIFT;
        this.dirtyBlocks[block >>> 6] |= 1L << block;
    }

    @Override
    public void setVisible(int x, int y, int z) {
        positionKey = x + y * reachX2 + z * reachX2 * reachX2;
        entry = positionKey / 4;
        offset = (positionKey % 4) * 2;
        cache[entry] |= 1 << offset;
        markDirty(entry);
    }

    @Override
    public void setHidden(int x, int y, int z) {
        positionKey = x + y * reachX2 + z * reachX2 * reachX2;
        entry = positionKey / 4;
        offset = (positionKey % 4) * 2;
        cache[entry] |= 1 << offset + 1;
        markDirty(entry);
    }

    @Override
    public int getState(int x, int y, int z) {
        positionKey = x + y * reachX2 + z * reachX2 * reachX2;
        entry = positionKey / 4;
        offset = (positionKey % 4) * 2;
        return cache[entry] >> offset & 3;
    }

    @Override
    public void setLastVisible() {
        cache[entry] |= 1 << offset;
        markDirty(entry);
    }

    @Override
    public void setLastHidden() {
        cache[entry] |= 1 << offset + 1;
        markDirty(entry);
    }

}
