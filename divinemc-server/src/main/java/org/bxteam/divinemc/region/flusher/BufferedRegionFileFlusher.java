package org.bxteam.divinemc.region.flusher;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.apache.commons.lang3.Validate;
import org.bxteam.divinemc.region.type.BufferedRegionFile;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

public class BufferedRegionFileFlusher implements Runnable {
    private static final Logger logger = LogUtils.getLogger();

    private final Set<BufferedRegionFile> inManagement = new ObjectArraySet<>();
    private final ScheduledFuture<?> flusherChecker;
    private final Executor ioWorkerPool;
    private final long flushOfWriteTimeoutMs;

    public BufferedRegionFileFlusher(int nIoThreads, long checkIntervalMs, long flushOfWriteTimeoutMs) {
        Validate.isTrue(nIoThreads > 0, "Number of I/O threads must > 0!");
        Validate.isTrue(checkIntervalMs > 0, "Check interval must > 0");
        Validate.isTrue(flushOfWriteTimeoutMs > 0, "Flush of write timeout must > 0");

        this.ioWorkerPool = Executors.newFixedThreadPool(nIoThreads, new NamedAgnosticThreadFactory<>(
                "BufferedRegionFile I/O Worker",
                (group, runnable, name) -> {
                    Thread thread = new Thread(group, runnable, name);
                    thread.setDaemon(true);
                    return thread;
                },
                Thread.NORM_PRIORITY
            )
        );
        this.flusherChecker = Executors.newSingleThreadScheduledExecutor(new NamedAgnosticThreadFactory<>(
                "BufferedRegionFile Flusher Checker",
                (group, runnable, name) -> {
                    Thread thread = new Thread(group, runnable, name);
                    thread.setDaemon(true);
                    return thread;
                },
                Thread.NORM_PRIORITY
            )
        ).scheduleWithFixedDelay(this, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        this.flushOfWriteTimeoutMs = flushOfWriteTimeoutMs;
    }

    public void shutdown() {
        this.flusherChecker.cancel(false);

        ((ExecutorService) this.ioWorkerPool).shutdown();
        for (; ; ) {
            try {
                if (((ExecutorService) this.ioWorkerPool).awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        final long currentNanos = System.nanoTime();
        final BufferedRegionFile[] copied;

        synchronized (this) {
            copied = this.inManagement.toArray(new BufferedRegionFile[0]);
        }

        final List<BufferedRegionFile> toRemove = new ObjectArrayList<>();
        for (BufferedRegionFile file : copied) {
            if (!file.softReadLock()) {
                continue;
            }

            boolean closed;

            try {
                closed = file.isClosedRaw();
            } finally {
                file.releaseReadLock();
            }

            if (closed) {
                toRemove.add(file);
                continue;
            }

            if (!file.shouldSync()) {
                continue;
            }

            final long lastWriteNanos = file.getLastWritten();
            final long timeElapsed = (currentNanos - lastWriteNanos) / 1_000_000; // Convert to milliseconds

            if (timeElapsed >= this.flushOfWriteTimeoutMs) {
                if (!file.markAsBeingSynced()) {
                    continue;
                }

                this.ioWorkerPool.execute(() -> {
                    try {
                        file.syncIfNeeded();
                    } catch (IOException e) {
                        logger.error("Failed to sync master file: ", e);
                    }
                });
            }
        }

        synchronized (this) {
            for (BufferedRegionFile file : toRemove) {
                this.inManagement.remove(file);
            }
        }
    }

    public void removeFile(BufferedRegionFile fileToRemove) {
        synchronized (this) {
            this.inManagement.remove(fileToRemove);
        }
    }

    public void addFile(BufferedRegionFile fileToAdd) {
        synchronized (this) {
            this.inManagement.add(fileToAdd);
        }
    }
}
