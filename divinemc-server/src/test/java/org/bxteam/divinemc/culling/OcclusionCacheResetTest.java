package org.bxteam.divinemc.culling;

import com.logisticscraft.occlusionculling.cache.ArrayOcclusionCache;
import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Normal
@NullMarked
class OcclusionCacheResetTest {
    // a write that does not mark its block survives the reset and leaks a stale visibility into the next pass
    @Test
    void resetMatchesFullClear() {
        for (final int reach : new int[] {5, 8, 21}) {
            final int side = reach * 2;
            final ArrayOcclusionCache cache = new ArrayOcclusionCache(reach);
            final byte[] reference = new byte[(side * side * side) / 4];
            final Random random = new Random(reach);
            for (int pass = 0; pass < 200; ++pass) {
                for (int op = random.nextInt(300); op > 0; --op) {
                    final int x = random.nextInt(side);
                    final int y = random.nextInt(side);
                    final int z = random.nextInt(side);
                    final int key = x + y * side + z * side * side;
                    final int bit = 1 << (key % 4) * 2;
                    switch (random.nextInt(4)) {
                        case 0 -> {
                            cache.setVisible(x, y, z);
                            reference[key / 4] |= (byte) bit;
                        }
                        case 1 -> {
                            cache.setHidden(x, y, z);
                            reference[key / 4] |= (byte) (bit << 1);
                        }
                        case 2 -> {
                            cache.getState(x, y, z);
                            cache.setLastVisible();
                            reference[key / 4] |= (byte) bit;
                        }
                        default -> {
                            cache.getState(x, y, z);
                            cache.setLastHidden();
                            reference[key / 4] |= (byte) (bit << 1);
                        }
                    }
                }
                if (random.nextInt(3) == 0) {
                    cache.resetCache();
                    Arrays.fill(reference, (byte) 0);
                }
                for (int x = 0; x < side; ++x) {
                    for (int y = 0; y < side; ++y) {
                        for (int z = 0; z < side; ++z) {
                            final int key = x + y * side + z * side * side;
                            assertEquals(reference[key / 4] >> (key % 4) * 2 & 3, cache.getState(x, y, z), "reach " + reach + " pass " + pass);
                        }
                    }
                }
            }
        }
    }
}
