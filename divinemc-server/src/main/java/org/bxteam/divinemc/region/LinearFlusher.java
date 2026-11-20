package org.bxteam.divinemc.region;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs delayed flushes, asynchronous closes and compactions of Linear regions, and bounds the memory held by records
 * that wait for a flush.
 */
@NullMarked
public final class LinearFlusher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long RETRY_DELAY_MS = 5_000;
    private static @Nullable LinearFlusher instance;

    final long flushDelayMs;
    final int compressionLevel;
    private final long maxPendingBytes;
    private final ScheduledThreadPoolExecutor flushers;
    private final ExecutorService compactor;
    private final AtomicLong pendingBytes = new AtomicLong();
    private final Object pendingMonitor = new Object();
    private volatile boolean shutdown;
    private volatile List<Path> compactOnShutdown = List.of();

    public LinearFlusher(int threads, long flushDelayMs, int compressionLevel, long maxPendingBytes) {
        this.flushDelayMs = flushDelayMs;
        this.compressionLevel = compressionLevel;
        this.maxPendingBytes = maxPendingBytes;
        this.flushers = new ScheduledThreadPoolExecutor(threads, new ThreadFactoryBuilder().setNameFormat("linear-flusher-%d").setDaemon(true).build());
        this.flushers.setRemoveOnCancelPolicy(true);
        this.compactor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("linear-compactor").setDaemon(true).build());
    }

    /** The server's flusher, started with the settings of misc.region-settings on first use. */
    public static synchronized LinearFlusher get() {
        if (instance == null) {
            instance = new LinearFlusher(
                DivineConfig.MiscCategory.linearFlushThreads,
                DivineConfig.MiscCategory.linearFlushDelayMs,
                DivineConfig.MiscCategory.linearCompressionLevel,
                (long) DivineConfig.MiscCategory.linearMaxPendingMb << 20
            );
        }
        return instance;
    }

    /** Flushes and stops the server's flusher if Linear regions were ever opened. */
    public static void shutdownIfStarted() {
        final LinearFlusher flusher;
        synchronized (LinearFlusher.class) {
            flusher = instance;
        }
        if (flusher != null) {
            LOGGER.info("Flushing Linear regions and stopping the Linear flusher...");
            flusher.shutdown();
        }
    }

    void scheduleFlush(LinearRegionFile region, long delayMs) {
        try {
            this.flushers.schedule(region::flushFromScheduler, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // after shutdown the region is flushed by its synchronous close
        }
    }

    void submitClose(Runnable task) {
        try {
            this.flushers.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    void requestCompaction(LinearRegionFile region) {
        if (this.shutdown || serverStopping()) return;
        try {
            this.compactor.execute(region::compactFromScheduler);
        } catch (RejectedExecutionException ignored) {
            // shutting down: the next open compacts it
        }
    }

    CompletableFuture<Void> runCompaction(Runnable task) {
        try {
            return CompletableFuture.runAsync(task, this.compactor);
        } catch (RejectedExecutionException e) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Compacts every Linear file with garbage in the folders now, in the background; returns how many were queued. */
    public int compactNow(List<Path> folders) {
        return LinearRegionFile.compactFolders(folders, this, false);
    }

    /** Compacts every Linear file with garbage in the folders when the server stops. */
    public void compactOnShutdown(List<Path> folders) {
        this.compactOnShutdown = List.copyOf(folders);
    }

    boolean compactAllOnShutdown() {
        return !this.compactOnShutdown.isEmpty();
    }

    static long retryDelayMs() {
        return RETRY_DELAY_MS;
    }

    /** True once the server began stopping: closes then only flush, the next start compacts. */
    static boolean serverStopping() {
        final MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.hasStopped();
    }

    void addPending(long delta) {
        if (delta == 0) return;
        final long now = this.pendingBytes.addAndGet(delta);
        if (delta < 0 && now + (-delta) > this.maxPendingBytes && now <= this.maxPendingBytes) {
            synchronized (this.pendingMonitor) {
                this.pendingMonitor.notifyAll();
            }
        }
    }

    long pendingBytes() {
        return this.pendingBytes.get();
    }

    /** Blocks a writer while too many records wait for a flush; the caller's region is flushed right away. */
    void awaitCapacity(LinearRegionFile region) {
        if (this.pendingBytes.get() <= this.maxPendingBytes || this.shutdown) return;
        this.scheduleFlush(region, 0);
        boolean warned = false;
        synchronized (this.pendingMonitor) {
            while (this.pendingBytes.get() > this.maxPendingBytes && !this.shutdown) {
                if (!warned) {
                    LOGGER.warn("Linear region writes are waiting: {} MB of chunks wait for the disk (limit region-settings.linear.max-pending-mb)", this.pendingBytes.get() >> 20);
                    warned = true;
                }
                try {
                    this.pendingMonitor.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Flushes every open region, then stops the threads once queued closes and compactions finished. */
    public void shutdown() {
        LinearRegionFile.flushAllOpen();
        if (!this.compactOnShutdown.isEmpty()) {
            LOGGER.info("Compacting Linear region files (requested with /divinemc compact shutdown)...");
            final int count = LinearRegionFile.compactFolders(this.compactOnShutdown, this, true);
            LOGGER.info("Compacted {} Linear region files", count);
        }
        this.shutdown = true;
        synchronized (this.pendingMonitor) {
            this.pendingMonitor.notifyAll();
        }
        this.flushers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.flushers.shutdown();
        this.compactor.shutdown();
        try {
            if (!this.flushers.awaitTermination(10, TimeUnit.MINUTES)) {
                LOGGER.error("Linear flusher did not finish within 10 minutes");
            }
            if (!this.compactor.awaitTermination(10, TimeUnit.MINUTES)) {
                LOGGER.error("Linear compactor did not finish within 10 minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LinearRegionFile.flushAllOpen();
    }
}
