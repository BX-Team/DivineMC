package org.bxteam.divinemc.math.isa;

import org.bxteam.divinemc.math.ISATarget;

public enum ISA_x86_64 implements ISATarget {
    SSE2("_sse2", true), // 0
    SSE4_1("_sse2", false), // 1, not implemented
    SSE4_2("_sse4_2", true), // 2
    AVX("_avx", true), // 3
    AVX11("_avx", false), // 4, not implemented
    AVX2("_avx2", true), // 5
    AVX2VNNI("_avx2vnni", true), // 6
    KNL_AVX512("_avx2", false), // 7, not implemented
    SKX_AVX512("_avx512skx", true), // 8
    ICL_AVX512("_avx512icl", true), // 9
    SPR_AVX512("_avx512spr", true), // 10
    GNR_AVX512("_avx512spr", false), // 11, not implemented
    DMR_AVX10_2("_avx10_2dmr", true), // 12
    NVL_AVX10_2("_avx10_2nvl", true), // 13
    ;

    private final String suffix;
    private final boolean nativelySupported;

    ISA_x86_64(String suffix, boolean nativelySupported) {
        this.suffix = suffix;
        this.nativelySupported = nativelySupported;
    }

    @Override
    public String getSuffix() {
        return this.suffix;
    }

    @Override
    public boolean isNativelySupported() {
        return this.nativelySupported;
    }
}
