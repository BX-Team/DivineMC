package gg.pufferfish.pufferfish.simd;

import jdk.incubator.vector.VectorSpecies;
import org.slf4j.Logger;

public class SIMDChecker {
    private final VectorSpecies<Integer> ISPEC;
    private final VectorSpecies<Float> FSPEC;

    public SIMDChecker(VectorSpecies<Integer> ISPEC, VectorSpecies<Float> FSPEC) {
        this.ISPEC = ISPEC;
        this.FSPEC = FSPEC;
    }

    public boolean canEnable(Logger logger) {
        try {
            if ((SIMDDetection.getJavaVersion() < SIMDDetection.MIN_JAVA_VERSION || SIMDDetection.getJavaVersion() > SIMDDetection.MAX_JAVA_VERSION)) {
                return false;
            } else {
                SIMDDetection.testRun = true;

                logger.info("Max SIMD vector size on this system is {} bits (int)", ISPEC.vectorBitSize());
                logger.info("Max SIMD vector size on this system is {} bits (float)", FSPEC.vectorBitSize());

                if (ISPEC.elementSize() < 2 || FSPEC.elementSize() < 2) {
                    logger.warn("SIMD is not properly supported on this system!");
                    return false;
                }

                return true;
            }
        } catch (NoClassDefFoundError | Exception ignored) {
            // Basically, we don't do anything. This lets us detect if it's not functional and disable it.
        }

        return false;
    }
}
