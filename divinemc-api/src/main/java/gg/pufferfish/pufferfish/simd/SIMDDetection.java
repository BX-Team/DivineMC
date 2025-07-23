package gg.pufferfish.pufferfish.simd;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import org.slf4j.Logger;

public class SIMDDetection {
    public static boolean isEnabled = false;
    public static boolean versionLimited = false;
    public static boolean testRun = false;

    public static final int MAX_JAVA_VERSION = 25;
    public static final int MIN_JAVA_VERSION = 21;

    public static boolean canEnable(Logger logger) {
        try {
            SIMDChecker checker = new SIMDChecker(IntVector.SPECIES_PREFERRED, FloatVector.SPECIES_PREFERRED);
            return checker.canEnable(logger);
        } catch (NoClassDefFoundError | Exception ignored) {
            return false;
        }
    }

    public static int getJavaVersion() {
        // https://stackoverflow.com/a/2591122
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf(".");
            if(dot != -1) { version = version.substring(0, dot); }
        }
        version = version.split("-")[0];
        return Integer.parseInt(version);
    }
}
