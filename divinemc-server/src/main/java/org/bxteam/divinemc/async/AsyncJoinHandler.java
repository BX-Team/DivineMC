package org.bxteam.divinemc.async;

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

    public static void init(boolean enabled, int threadCount) {
        AsyncJoinHandler.enabled = enabled;

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

    public static Executor getExecutor() {
        return enabled && JOIN_EXECUTOR != null ? JOIN_EXECUTOR : Runnable::run;
    }
}
