package org.bxteam.divinemc.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.Normal;
import org.bxteam.divinemc.util.entity.NavigatingMobIndex;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class NavigatingMobIndexTest {
    private static final double[] MOB_OFFSETS = {0.0, 0.25, 0.3, 0.5, 0.7, 0.75, 0.999};

    // the cache must miss exactly where PathNavigation.shouldRecomputePath misses, including on the sphere's surface
    @Test
    void cachedSphereTestMatchesVanilla() {
        int boundary = 0;
        for (final int base : new int[] {0, -1, 29_999_000, -29_999_000}) {
            for (int dx = -3; dx <= 3; ++dx) {
                for (final double offset : MOB_OFFSETS) {
                    for (int node = -2; node <= 2; ++node) {
                        for (int remaining = 1; remaining <= 3; ++remaining) {
                            final BlockPos pos = new BlockPos(base + dx, 64 + dx, base - dx);
                            final double mobX = base + offset;
                            final double mobY = 64.0 + offset;
                            final double mobZ = base - offset;
                            final Vec3 middle = new Vec3((base + node + mobX) / 2.0, (64 + node + mobY) / 2.0, (base - node + mobZ) / 2.0);
                            final boolean vanilla = pos.closerToCenterThan(middle, remaining);
                            final boolean cached = NavigatingMobIndex.inSphere(
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                                NavigatingMobIndex.middle(base + node, mobX),
                                NavigatingMobIndex.middle(64 + node, mobY),
                                NavigatingMobIndex.middle(base - node, mobZ),
                                NavigatingMobIndex.radiusSqr(remaining)
                            );
                            final int r = remaining;
                            assertEquals(vanilla, cached, () -> pos + " " + middle + " r=" + r);
                            if (pos.distToCenterSqr(middle) == (double) remaining * remaining) {
                                boundary++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(boundary > 0, "no case on the sphere's surface");
    }
}
