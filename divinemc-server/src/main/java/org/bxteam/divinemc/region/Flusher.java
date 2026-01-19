package org.bxteam.divinemc.region;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class Flusher<T> implements Runnable {
    public static final Logger logger = LogUtils.getLogger();
    public final ScheduledExecutorService scheduler;
    public final ExecutorService executor;
    public final long flushOfWriteTimeoutMs;

    public Flusher(int maxThreadCount, long checkIntervalMs, long flushOfWriteTimeoutMs) {
        this.flushOfWriteTimeoutMs = flushOfWriteTimeoutMs;
        scheduler = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat("region-flush-scheduler")
                .build()
        );

        scheduler.scheduleAtFixedRate(this, 0L, checkIntervalMs, TimeUnit.MILLISECONDS);

        ThreadFactoryBuilder factory = new ThreadFactoryBuilder()
            .setNameFormat("region-flusher-%d");

        executor = Executors.newFixedThreadPool(
            maxThreadCount,
            factory.build()
        );
    }

    public abstract void shutdown();

    public abstract void addFile(T t);

    public abstract void removeFile(T t);

    public final void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
