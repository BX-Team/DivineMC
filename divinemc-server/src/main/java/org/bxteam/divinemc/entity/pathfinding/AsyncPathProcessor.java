package org.bxteam.divinemc.entity.pathfinding;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.pathfinder.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Used to handle the scheduling of async path processing
 */
@SuppressWarnings("DuplicatedCode")
public class AsyncPathProcessor {
    private static final String THREAD_PREFIX = "Async Pathfinding";
    private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);

    private static long lastWarnMillis = System.currentTimeMillis();
    private static final ThreadPoolExecutor pathProcessingExecutor = new ThreadPoolExecutor(
        1,
        DivineConfig.AsyncCategory.asyncPathfindingMaxThreads,
        DivineConfig.AsyncCategory.asyncPathfindingKeepalive, TimeUnit.SECONDS,
        getQueueImpl(),
        new NamedAgnosticThreadFactory<>(THREAD_PREFIX, TickThread::new, Thread.NORM_PRIORITY - 2),
        new RejectedTaskHandler()
    );

    private static class RejectedTaskHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable rejectedTask, ThreadPoolExecutor executor) {
            BlockingQueue<Runnable> workQueue = executor.getQueue();
            if (!executor.isShutdown()) {
                switch (DivineConfig.AsyncCategory.asyncPathfindingRejectPolicy) {
                    case FLUSH_ALL -> {
                        if (!workQueue.isEmpty()) {
                            List<Runnable> pendingTasks = new ArrayList<>(workQueue.size());

                            workQueue.drainTo(pendingTasks);

                            for (Runnable pendingTask : pendingTasks) {
                                pendingTask.run();
                            }
                        }
                        rejectedTask.run();
                    }
                    case CALLER_RUNS -> rejectedTask.run();
                }
            }

            if (System.currentTimeMillis() - lastWarnMillis > 30000L) {
                LOGGER.warn("Async pathfinding processor is busy! Pathfinding tasks will be treated as policy defined in config. Increasing max-threads in DivineMC config may help.");
                lastWarnMillis = System.currentTimeMillis();
            }
        }
    }

    protected static CompletableFuture<Void> queue(@NotNull AsyncPath path) {
        return CompletableFuture.runAsync(path::process, pathProcessingExecutor)
            .orTimeout(60L, TimeUnit.SECONDS)
            .exceptionally(throwable -> {
                if (throwable instanceof TimeoutException e) {
                    LOGGER.warn("Async Pathfinding process timed out", e);
                } else LOGGER.warn("Error occurred while processing async path", throwable);
                return null;
            });
    }

    /**
     * takes a possibly unprocessed path, and waits until it is completed
     * the consumer will be immediately invoked if the path is already processed
     * the consumer will always be called on the main thread
     *
     * @param path            a path to wait on
     * @param afterProcessing a consumer to be called
     */
    public static void awaitProcessing(@Nullable Path path, Consumer<@Nullable Path> afterProcessing) {
        if (path != null && !path.isProcessed() && path instanceof AsyncPath asyncPath) {
            asyncPath.postProcessing(() ->
                MinecraftServer.getServer().scheduleOnMain(() -> afterProcessing.accept(path))
            );
        } else {
            afterProcessing.accept(path);
        }
    }

    private static BlockingQueue<Runnable> getQueueImpl() {
        final int queueCapacity = DivineConfig.AsyncCategory.asyncPathfindingQueueSize;

        return new LinkedBlockingQueue<>(queueCapacity);
    }
}
