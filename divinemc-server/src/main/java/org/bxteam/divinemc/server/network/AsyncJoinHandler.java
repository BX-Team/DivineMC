package org.bxteam.divinemc.server.network;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.spark.ThreadDumperRegistry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class AsyncJoinHandler {
    private static final String THREAD_PREFIX = "Async Join Thread";
    public static final Logger LOGGER = LogManager.getLogger(AsyncJoinHandler.class.getSimpleName());
    public static ExecutorService JOIN_EXECUTOR;

    private static boolean enabled = false;
    private static int threadCount = 2;

    /**
     * Initialize the AsyncJoinHandler with configuration settings
     */
    public static void init(boolean enabled, int threadCount) {
        AsyncJoinHandler.enabled = enabled;
        AsyncJoinHandler.threadCount = Math.max(1, threadCount);

        if (enabled) {
            if (JOIN_EXECUTOR != null) {
                JOIN_EXECUTOR.shutdown();
            }

            JOIN_EXECUTOR = org.bxteam.divinemc.config.DivineConfig.PerformanceCategory.virtualThreadsEnabled &&
                DivineConfig.AsyncCategory.asyncJoinUseVirtualThreads
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(
                    threadCount,
                    new ThreadFactoryBuilder()
                        .setNameFormat(THREAD_PREFIX)
                        .setDaemon(true)
                        .build()
            );

            ThreadDumperRegistry.REGISTRY.add(THREAD_PREFIX);

            LOGGER.info("Initialized AsyncJoinHandler with {} threads", threadCount);
        }
    }

    /**
     * Execute a potentially blocking task asynchronously
     *
     * @param task The task to run asynchronously
     * @param callback The callback to execute on the main thread when the task completes
     * @param <T> The return type of the task
     */
    public static <T> void runAsync(Supplier<T> task, java.util.function.Consumer<T> callback) {
        if (!enabled || JOIN_EXECUTOR == null) {
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

    /**
     * Execute a potentially blocking task asynchronously without a result
     *
     * @param asyncTask The task to run asynchronously
     */
    public static void runAsync(Runnable asyncTask) {
        if (!enabled || JOIN_EXECUTOR == null) {
            asyncTask.run();
            return;
        }

        CompletableFuture.runAsync(asyncTask, JOIN_EXECUTOR)
            .thenRun(() -> MinecraftServer.getServer().execute(asyncTask))
            .exceptionally(ex -> {
                LOGGER.error("Error during async join operation", ex);
                return null;
            });
    }

    /**
     * Get the executor service for async join operations
     */
    public static Executor getExecutor() {
        return enabled && JOIN_EXECUTOR != null ? JOIN_EXECUTOR : Runnable::run;
    }
}
