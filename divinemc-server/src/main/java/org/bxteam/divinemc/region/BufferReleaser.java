package org.bxteam.divinemc.region;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class BufferReleaser {
    private static final Method CLEANER_METHOD;
    private static final Object UNSAFE;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = theUnsafe.get(null);
            CLEANER_METHOD = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
        } catch (Exception ex) {
            throw new RuntimeException("Unsafe init failed", ex);
        }
    }

    public static boolean clean(@NotNull ByteBuffer buffer) {
        if (!buffer.isDirect()) return false;
        try {
            CLEANER_METHOD.invoke(UNSAFE, buffer);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
