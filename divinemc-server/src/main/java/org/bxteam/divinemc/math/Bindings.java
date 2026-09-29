package org.bxteam.divinemc.math;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

public class Bindings {
    private static MethodHandle bind(MethodHandle template, String prefix) {
        return template.bindTo(NativeLoader.lookup.find(prefix + NativeLoader.currentMachineTarget.getSuffix()).get());
    }

    private static final MethodHandle MH_c2me_natives_noise_perlin_sample_legacy_area = bind(BindingsTemplate.c2me_natives_noise_perlin_sample_legacy_area, "c2me_natives_noise_perlin_sample_legacy_area");
    private static final MethodHandle MH_c2me_natives_noise_perlin_sample_base_area = bind(BindingsTemplate.c2me_natives_noise_perlin_sample_base_area, "c2me_natives_noise_perlin_sample_base_area");
    private static final MethodHandle MH_c2me_natives_end_islands_sample = bind(BindingsTemplate.c2me_natives_end_islands_sample, "c2me_natives_end_islands_sample");
    private static final MethodHandle MH_c2me_natives_biome_access_sample = bind(BindingsTemplate.c2me_natives_biome_access_sample, "c2me_natives_biome_access_sample");

    public static void c2me_natives_noise_perlin_sample_legacy_area(final MemorySegment permutations,
                                                                    final double originX, final double originY, final double originZ,
                                                                    final double yScale, final MemorySegment output,
                                                                    final int sizeX, final int sizeY, final int sizeZ,
                                                                    final int minBlockX, final int minBlockY, final int minBlockZ,
                                                                    final int stepBlockX, final int stepBlockY, final int stepBlockZ,
                                                                    final double scaleXz, final double scaleY, final float outputScale) {
        try {
            MH_c2me_natives_noise_perlin_sample_legacy_area.invokeExact(permutations, originX, originY, originZ, yScale, output,
                sizeX, sizeY, sizeZ, minBlockX, minBlockY, minBlockZ, stepBlockX, stepBlockY, stepBlockZ,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, scaleXz, scaleY, outputScale);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void c2me_natives_noise_perlin_sample_base_area(final MemorySegment permutations,
                                                                  final double originX, final double originY, final double originZ,
                                                                  final MemorySegment output,
                                                                  final int sizeX, final int sizeY, final int sizeZ,
                                                                  final int minBlockX, final int minBlockY, final int minBlockZ,
                                                                  final int stepBlockX, final int stepBlockY, final int stepBlockZ,
                                                                  final double scaleXz, final double scaleY, final float outputScale) {
        try {
            MH_c2me_natives_noise_perlin_sample_base_area.invokeExact(permutations, originX, originY, originZ, output,
                sizeX, sizeY, sizeZ, minBlockX, minBlockY, minBlockZ, stepBlockX, stepBlockY, stepBlockZ,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, scaleXz, scaleY, outputScale);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static float c2me_natives_end_islands_sample(final MemorySegment simplexPermutations, final int x, final int z) {
        try {
            return (float) MH_c2me_natives_end_islands_sample.invokeExact(simplexPermutations, x, z);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int c2me_natives_biome_access_sample(final long seed, final int x, final int y, final int z) {
        try {
            return (int) MH_c2me_natives_biome_access_sample.invokeExact(seed, x, y, z);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int[] packByte2int(final byte[] data) {
        final int[] ints = new int[Math.ceilDiv(data.length, 4)];
        for (int i = 0; i < data.length; i++) {
            ints[i >> 2] |= (data[i] & 0xff) << ((i & 3) << 3);
        }
        return ints;
    }
}
