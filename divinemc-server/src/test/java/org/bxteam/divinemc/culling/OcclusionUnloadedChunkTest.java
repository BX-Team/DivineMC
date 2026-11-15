package org.bxteam.divinemc.culling;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.util.Vec3d;
import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class OcclusionUnloadedChunkTest {
    private static final Vec3d VIEWER = new Vec3d(0.5, 64.5, 0.5);
    private static final Vec3d TARGET_MIN = new Vec3d(50, 64, 0);
    private static final Vec3d TARGET_MAX = new Vec3d(51, 65, 1);

    // a ray through a chunk that is not loaded has no information, so the target must stay visible
    @Test
    void unloadedChunkOnTheRayIsNotAnObstacle() {
        final OcclusionCullingInstance culling = new OcclusionCullingInstance(128, new Provider(1, Integer.MIN_VALUE));
        assertTrue(culling.isAABBVisible(TARGET_MIN, TARGET_MAX, VIEWER));
    }

    @Test
    void wallInLoadedChunksStillCulls() {
        final OcclusionCullingInstance culling = new OcclusionCullingInstance(128, new Provider(Integer.MIN_VALUE, 20));
        assertFalse(culling.isAABBVisible(TARGET_MIN, TARGET_MAX, VIEWER));
    }

    private record Provider(int unloadedChunkX, int wallX) implements DataProvider {
        @Override
        public boolean prepareChunk(final int chunkX, final int chunkZ) {
            return chunkX != this.unloadedChunkX;
        }

        @Override
        public boolean isOpaqueFullCube(final int x, final int y, final int z) {
            return x == this.wallX;
        }
    }
}
