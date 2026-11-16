package org.bxteam.divinemc.util;

import net.caffeinemc.mods.lithium.common.ai.non_poi_block_search.NonPoiBlockSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class NonPoiBlockSearchOrderTest {
    // the search takes the smallest sort key as the block vanilla meets first, and skips chunks by their smallest key
    @Test
    void sortOrderFollowsVanillaLoop() {
        final int searchRange = 24;
        int previous = Integer.MIN_VALUE;
        int count = 0;
        for (int r = 0; r < searchRange; r++) {
            for (int x = 0; x <= r; x = x > 0 ? -x : 1 - x) {
                for (int z = x < r && x > -r ? r : 0; z <= r; z = z > 0 ? -z : 1 - z) {
                    final int order = NonPoiBlockSearch.sortOrder(Math.max(Math.abs(x), Math.abs(z)), x, z);
                    assertTrue(order > previous, "x=" + x + " z=" + z);
                    previous = order;
                    ++count;
                }
            }
        }
        assertEquals((2 * searchRange - 1) * (2 * searchRange - 1), count);

        for (final BlockPos center : new BlockPos[] {new BlockPos(0, 64, 0), new BlockPos(7, 64, -9), new BlockPos(-16, 64, 15), new BlockPos(31, 64, -1)}) {
            for (int chunkX = -3; chunkX <= 3; chunkX++) {
                for (int chunkZ = -3; chunkZ <= 3; chunkZ++) {
                    int min = Integer.MAX_VALUE;
                    for (int x = SectionPos.sectionToBlockCoord(chunkX); x < SectionPos.sectionToBlockCoord(chunkX) + 16; x++) {
                        for (int z = SectionPos.sectionToBlockCoord(chunkZ); z < SectionPos.sectionToBlockCoord(chunkZ) + 16; z++) {
                            final int dX = x - center.getX();
                            final int dZ = z - center.getZ();
                            min = Math.min(min, NonPoiBlockSearch.sortOrder(Math.max(Math.abs(dX), Math.abs(dZ)), dX, dZ));
                        }
                    }
                    assertEquals(min, NonPoiBlockSearch.minimumSortOrderOfChunk(center, chunkX, chunkZ), center + " chunk " + chunkX + "," + chunkZ);
                }
            }
        }
    }
}
