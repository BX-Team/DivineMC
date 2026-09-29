package org.bxteam.divinemc.math;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.generator.EndIslandFunction;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;
import org.bukkit.support.environment.VanillaFeature;
import org.bxteam.divinemc.config.DivineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@VanillaFeature
class NativeMathParityTest {
    private static final DensityVolume[] VOLUMES = {
        new DensityVolume(16, 48, 16, -32, -64, 48),
        new DensityVolume(5, 49, 5, 1024, -64, -2048, 4, 8, 4),
        new DensityVolume(3, 3, 3, 30_000_000 - 8, 0, -30_000_000, 1, 1, 1),
    };
    private static final double[][] SCALES = {{1.0, 1.0}, {0.25, 0.125}, {684.412 / 128.0, 684.412 * 4 / 128.0}, {0.0078125, 0.015625}};

    private boolean previous;

    @BeforeEach
    void enable() {
        this.previous = DivineConfig.PerformanceCategory.nativeAccelerationEnabled;
        assumeTrue(NativeLoader.currentMachineTarget != null, "native math library not available on this platform");
        DivineConfig.PerformanceCategory.nativeAccelerationEnabled = true;
    }

    @AfterEach
    void restore() {
        DivineConfig.PerformanceCategory.nativeAccelerationEnabled = this.previous;
    }

    private static void assertParity(final Noise noise) {
        for (DensityVolume volume : VOLUMES) {
            for (double[] scale : SCALES) {
                DensityBuffer expected = DensityBuffer.createUnpooled(volume.size());
                DensityBuffer actual = DensityBuffer.createUnpooled(volume.size());
                DivineConfig.PerformanceCategory.nativeAccelerationEnabled = false;
                noise.addToVolume(expected, volume, scale[0], scale[1], 0.75F);
                DivineConfig.PerformanceCategory.nativeAccelerationEnabled = true;
                noise.addToVolume(actual, volume, scale[0], scale[1], 0.75F);
                for (int i = 0; i < volume.size(); i++) {
                    assertEquals(expected.get(i), actual.get(i), 1.0E-5F, "index " + i + " of " + volume + " scale " + scale[0] + "/" + scale[1]);
                }
            }
        }
    }

    @Test
    void perlin() {
        for (long seed = 0; seed < 8; seed++) {
            assertParity(new PerlinNoise(new XoroshiroRandomSource(seed)));
        }
    }

    @Test
    void smearedPerlin() {
        for (long seed = 0; seed < 8; seed++) {
            assertParity(new SmearedPerlinNoise(new XoroshiroRandomSource(seed), 1.0 + seed * 0.5));
        }
    }

    @Test
    void biomeZoom() {
        final int[] expected = new int[3];
        final int[] actual = new int[3];
        for (long seed : new long[]{0L, 1L, -4791817952625876078L, Long.MAX_VALUE}) {
            BiomeManager javaManager = new BiomeManager((x, y, z) -> {
                expected[0] = x;
                expected[1] = y;
                expected[2] = z;
                return null;
            }, seed);
            BiomeManager nativeManager = new BiomeManager((x, y, z) -> {
                actual[0] = x;
                actual[1] = y;
                actual[2] = z;
                return null;
            }, seed);
            for (int x = -40; x < 40; x += 3) {
                for (int y = -64; y < 64; y += 7) {
                    for (int z = -40; z < 40; z += 5) {
                        final int[] vanilla = vanillaZoom(seed, x, y, z);
                        DivineConfig.PerformanceCategory.nativeAccelerationEnabled = false;
                        javaManager.getBiome(x, y, z);
                        DivineConfig.PerformanceCategory.nativeAccelerationEnabled = true;
                        nativeManager.getBiome(x, y, z);
                        assertArrayEquals(vanilla, expected, "java path vs vanilla, seed " + seed + " at " + x + "," + y + "," + z);
                        assertArrayEquals(vanilla, actual, "native path vs vanilla, seed " + seed + " at " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    // verbatim port of vanilla 26.3 BiomeManager#getBiome
    private static int[] vanillaZoom(final long seed, final int x, final int y, final int z) {
        int absX = x - 2;
        int absY = y - 2;
        int absZ = z - 2;
        int parentX = absX >> 2;
        int parentY = absY >> 2;
        int parentZ = absZ >> 2;
        double fractX = (absX & 3) / 4.0;
        double fractY = (absY & 3) / 4.0;
        double fractZ = (absZ & 3) / 4.0;
        int minI = 0;
        double minFiddledDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            boolean xEven = (i & 4) == 0;
            boolean yEven = (i & 2) == 0;
            boolean zEven = (i & 1) == 0;
            int cornerX = xEven ? parentX : parentX + 1;
            int cornerY = yEven ? parentY : parentY + 1;
            int cornerZ = zEven ? parentZ : parentZ + 1;
            double distanceX = xEven ? fractX : fractX - 1.0;
            double distanceY = yEven ? fractY : fractY - 1.0;
            double distanceZ = zEven ? fractZ : fractZ - 1.0;
            long rval = seed;
            rval = LinearCongruentialGenerator.next(rval, cornerX);
            rval = LinearCongruentialGenerator.next(rval, cornerY);
            rval = LinearCongruentialGenerator.next(rval, cornerZ);
            rval = LinearCongruentialGenerator.next(rval, cornerX);
            rval = LinearCongruentialGenerator.next(rval, cornerY);
            rval = LinearCongruentialGenerator.next(rval, cornerZ);
            double fiddleX = fiddle(rval);
            rval = LinearCongruentialGenerator.next(rval, seed);
            double fiddleY = fiddle(rval);
            rval = LinearCongruentialGenerator.next(rval, seed);
            double fiddleZ = fiddle(rval);
            double next = Mth.square(distanceZ + fiddleZ) + Mth.square(distanceY + fiddleY) + Mth.square(distanceX + fiddleX);
            if (minFiddledDistance > next) {
                minI = i;
                minFiddledDistance = next;
            }
        }
        return new int[]{(minI & 4) == 0 ? parentX : parentX + 1, (minI & 2) == 0 ? parentY : parentY + 1, (minI & 1) == 0 ? parentZ : parentZ + 1};
    }

    private static double fiddle(final long rval) {
        return (Math.floorMod(rval >> 24, 1024) / 1024.0 - 0.5) * 0.9;
    }

    @Test
    void endIslands() throws ReflectiveOperationException {
        Method heightValue = EndIslandFunction.class.getDeclaredMethod("getHeightValue", SimplexNoise.class, int.class, int.class);
        heightValue.setAccessible(true);
        SimplexNoise noise = new SimplexNoise(new XoroshiroRandomSource(42L), true);
        MemorySegment perms = Arena.ofAuto().allocate(noise.divinemc$packedPerms.byteSize(), 64);
        MemorySegment.copy(noise.divinemc$packedPerms, 0L, perms, 0L, noise.divinemc$packedPerms.byteSize());
        for (int x = -300; x < 300; x += 17) {
            for (int z = -300; z < 300; z += 13) {
                float expected = (float) heightValue.invoke(null, noise, x, z);
                assertEquals(expected, Bindings.c2me_natives_end_islands_sample(perms, x, z), 1.0E-4F, "at " + x + "," + z);
            }
        }
    }
}
