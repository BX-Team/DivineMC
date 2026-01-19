package org.bxteam.divinemc.region.buffered;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.Validate;
import org.bxteam.divinemc.region.Flusher;

public class BufferedRegionFileFlusher extends Flusher<BufferedRegionFile> {
    private final Set<BufferedRegionFile> inManagement = new ObjectArraySet<>();

    public BufferedRegionFileFlusher(int nIoThreads, long checkIntervalMs, long flushOfWriteTimeoutMs) {
        super(nIoThreads, checkIntervalMs, flushOfWriteTimeoutMs);
        Validate.isTrue(nIoThreads > 0, "Number of I/O threads must > 0!");
        Validate.isTrue(checkIntervalMs > 0, "Check interval must > 0");
        Validate.isTrue(flushOfWriteTimeoutMs > 0, "Flush of write timeout must > 0");

    }

    public void shutdown() {
        shutdownExecutor(scheduler);
        shutdownExecutor(executor);
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

                executor.execute(() -> {
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
