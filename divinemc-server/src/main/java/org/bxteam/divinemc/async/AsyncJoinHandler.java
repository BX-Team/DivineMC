package org.bxteam.divinemc.async;

import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class AsyncJoinHandler {
    private static final String THREAD_PREFIX = "Async Join Thread";
    private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);

    public static final ThreadPoolExecutor JOIN_EXECUTOR = new ThreadPoolExecutor(
        1,
        DivineConfig.AsyncCategory.asyncJoinThreadCount,
        0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        getThreadFactory(),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static <T> void runAsync(Supplier<T> task, java.util.function.Consumer<T> callback) {
        if (!DivineConfig.AsyncCategory.asyncJoinEnabled) {
            T result = task.get();
            callback.accept(result);
            return;
        }

        CompletableFuture.supplyAsync(task, JOIN_EXECUTOR)
            .thenAccept(result -> {
                MinecraftServer.getServer().execute(() -> callback.accept(result));
            })
            .exceptionally(ex -> {
                LOGGER.error("Error during async join operation", ex);
                return null;
            });
    }

    public static void runAsync(Runnable asyncTask) {
        if (!DivineConfig.AsyncCategory.asyncJoinEnabled) {
            asyncTask.run();
            return;
        }

        CompletableFuture.runAsync(asyncTask, JOIN_EXECUTOR)
            .exceptionally(ex -> {
                LOGGER.error("Error during async join operation", ex);
                return null;
            });
    }

    private static @NotNull ThreadFactory getThreadFactory() {
        if (DivineConfig.AsyncCategory.asyncJoinUseVirtualThreads) return Thread.ofVirtual().name(THREAD_PREFIX).factory();

        return new NamedAgnosticThreadFactory<>(THREAD_PREFIX, AsyncJoinThread::new, Thread.NORM_PRIORITY);
    }

    public static class AsyncJoinThread extends Thread {
        protected AsyncJoinThread(ThreadGroup group, Runnable task, String name) {
            super(group, task, name);
        }
    }
}
