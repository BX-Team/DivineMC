package org.bxteam.divinemc.culling;

import com.logisticscraft.occlusionculling.DataProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Chunk data source for the occlusion raytracer. Runs on a culling worker thread: chunk lookups
 * go through {@code getChunkAtIfLoadedImmediately} (safe off the tick threads) and block reads
 * are plain palette reads - a torn read during a concurrent write is tolerated, the raytracer
 * treats any failure as "visible".
 */
public final class CullingDataProvider implements DataProvider {
    private ServerLevel level;
    private int minY;
    private int maxY;
    private LevelChunk cachedChunk;
    private int cachedChunkX = Integer.MIN_VALUE;
    private int cachedChunkZ = Integer.MIN_VALUE;

    public void setLevel(ServerLevel level) {
        this.level = level;
        this.minY = level.getMinY();
        this.maxY = level.getMaxY();
        this.resetChunkCache();
    }

    public void resetChunkCache() {
        this.cachedChunk = null;
        this.cachedChunkX = Integer.MIN_VALUE;
        this.cachedChunkZ = Integer.MIN_VALUE;
    }

    @Override
    public boolean prepareChunk(int chunkX, int chunkZ) {
        if (chunkX == this.cachedChunkX && chunkZ == this.cachedChunkZ) {
            return this.cachedChunk != null;
        }

        this.cachedChunkX = chunkX;
        this.cachedChunkZ = chunkZ;
        this.cachedChunk = this.level.chunkSource.getChunkAtIfLoadedImmediately(chunkX, chunkZ);
        return this.cachedChunk != null;
    }

    @Override
    public boolean isOpaqueFullCube(int x, int y, int z) {
        if (y < this.minY || y > this.maxY) {
            return false;
        }
        final LevelChunk chunk = this.cachedChunk;
        return chunk != null && chunk.getBlockState(x, y, z).isSolidRender();
    }
}
