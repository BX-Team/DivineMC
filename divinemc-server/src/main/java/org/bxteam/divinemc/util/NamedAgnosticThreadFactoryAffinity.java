package org.bxteam.divinemc.util;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.openhft.affinity.AffinityLock;
import org.jspecify.annotations.NonNull;

import static org.bxteam.divinemc.config.DivineConfig.AsyncCategory.guruMode;

public class NamedAgnosticThreadFactoryAffinity<T extends Thread> extends NamedAgnosticThreadFactory<T> {
    private final IntArrayList affinityList;
    private final AtomicInteger threadNumber = new AtomicInteger();
    private final boolean enabled;

    public NamedAgnosticThreadFactoryAffinity(
        final String name,
        final ThreadBuilderFunction<T> typeOfThread,
        final int priority,
        final int[] affinityList
    ) {
        super(name, typeOfThread, priority);
        this.enabled = guruMode && !(affinityList == null || affinityList.length == 0);
        this.affinityList = new IntArrayList(affinityList);
    }

    @Override
    public Thread newThread(@NonNull Runnable runnable) {
        if (!enabled) return super.newThread(runnable);
        final int cpuId = affinityList.getInt(Math.floorMod(threadNumber.getAndIncrement(), affinityList.size()));
        return super.newThread(() -> {
            if (cpuId == 0) {
                AffinityLock lock = null;
                try {
                    Method tryAcquireLock = AffinityLock.class.getDeclaredMethod("tryAcquireLock", boolean.class, int.class);
                    tryAcquireLock.setAccessible(true);
                    lock = (AffinityLock) tryAcquireLock.invoke(null, true, cpuId);
                    runnable.run();
                } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                } finally {
                    if (lock != null) {
                        lock.release();
                    }
                }

                return;
            }
            try (AffinityLock ignored = AffinityLock.acquireLock(cpuId)) {
                runnable.run();
            }
        });
    }
}

