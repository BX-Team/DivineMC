package org.bxteam.divinemc.math;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public class BindingsTemplate {
    // c2me_natives_noise_perlin_sample_legacy_area(const uint32_t *permutations,
    //     double originX, double originY, double originZ, double yScale, float *output,
    //     int32_t sizeX, int32_t sizeY, int32_t sizeZ, int32_t minBlockX, int32_t minBlockY, int32_t minBlockZ,
    //     int32_t stepBlockX, int32_t stepBlockY, int32_t stepBlockZ,
    //     const double *shiftX, const double *shiftY, const double *shiftZ,
    //     double scaleXz, double scaleY, float outputScale)
    public static final MethodHandle c2me_natives_noise_perlin_sample_legacy_area = NativeLoader.linker.downcallHandle(
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_FLOAT
        ),
        Linker.Option.critical(true)
    );

    // same as legacy_area, without yScale
    public static final MethodHandle c2me_natives_noise_perlin_sample_base_area = NativeLoader.linker.downcallHandle(
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_FLOAT
        ),
        Linker.Option.critical(true)
    );

    // float c2me_natives_end_islands_sample(const int32_t *simplex_permutations, int32_t x, int32_t z)
    public static final MethodHandle c2me_natives_end_islands_sample = NativeLoader.linker.downcallHandle(
        FunctionDescriptor.of(
            ValueLayout.JAVA_FLOAT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT
        ),
        Linker.Option.critical(false)
    );

    // uint32_t c2me_natives_biome_access_sample(int64_t theSeed, int32_t x, int32_t y, int32_t z)
    public static final MethodHandle c2me_natives_biome_access_sample = NativeLoader.linker.downcallHandle(
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT
        ),
        Linker.Option.critical(false)
    );
}
