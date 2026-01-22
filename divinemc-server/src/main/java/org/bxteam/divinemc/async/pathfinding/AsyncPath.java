package org.bxteam.divinemc.async.pathfinding;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AsyncPath {
    private static final String THREAD_PREFIX = "Async Pathfinding";
    private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);
    private static final Path SENTINEL_NULL_PATH = new Path();
    private static final CallbackChain EMPTY_CALLBACK_CHAIN = new CallbackChain(path -> {}, null);

    private final int navigationAccuracy;
    @Nullable
    private volatile Path computedPath;
    @Nullable
    private PathNavigation navigationListener;
    private volatile boolean isCompleted;
    private CallbackChain callbackChain;
    private static long lastWarnMillis = System.currentTimeMillis();

    public static final ThreadPoolExecutor PATH_PROCESSING_EXECUTOR = DivineConfig.AsyncCategory.asyncPathfinding ? new ThreadPoolExecutor(
        1,
        DivineConfig.AsyncCategory.asyncPathfindingMaxThreads,
        DivineConfig.AsyncCategory.asyncPathfindingKeepalive, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(DivineConfig.AsyncCategory.asyncPathfindingQueueSize),
        new NamedAgnosticThreadFactory<>(THREAD_PREFIX, TickThread::new, Thread.NORM_PRIORITY),
        new RejectedTaskHandler()
    ) : null;

    public AsyncPath(int accuracy, Supplier<@Nullable Path> pathSupplier) {
        this.navigationAccuracy = accuracy;
        this.computedPath = null;
        this.isCompleted = false;
        this.navigationListener = null;
        this.callbackChain = EMPTY_CALLBACK_CHAIN;

        executePathComputation(pathSupplier);
    }

    private void executePathComputation(Supplier<@Nullable Path> pathSupplier) {
        Runnable computationTask = () -> {
            Path result = pathSupplier.get();
            this.computedPath = (result != null) ? result : SENTINEL_NULL_PATH;
        };

        if (PATH_PROCESSING_EXECUTOR != null) {
            PATH_PROCESSING_EXECUTOR.execute(computationTask);
        } else {
            computationTask.run();
        }
    }

    public static void awaitProcessing(@Nullable Path path, Consumer<@Nullable Path> afterProcessing) {
        AsyncPath asyncTask = extractAsyncTask(path);

        if (asyncTask != null) {
            asyncTask.addCallback(afterProcessing);
        } else {
            afterProcessing.accept(path);
        }
    }

    @Nullable
    private static AsyncPath extractAsyncTask(@Nullable Path path) {
        return (path != null) ? path.task : null;
    }

    private void addCallback(Consumer<@Nullable Path> callback) {
        if (isTaskComplete()) {
            invokeCallbackWithResult(callback);
        } else {
            chainCallback(callback);
        }
    }

    private void invokeCallbackWithResult(Consumer<@Nullable Path> callback) {
        Path result = unwrapComputedPath();
        callback.accept(result);
    }

    private void chainCallback(Consumer<@Nullable Path> newCallback) {
        if (callbackChain == EMPTY_CALLBACK_CHAIN) {
            callbackChain = new CallbackChain(newCallback, null);
        } else {
            callbackChain = new CallbackChain(newCallback, callbackChain);
        }
    }

    public boolean complete() {
        if (isTaskComplete()) {
            notifyCompletion();
            return true;
        }

        return false;
    }

    private boolean isTaskComplete() {
        return isCompleted || computedPath != null;
    }

    private void notifyCompletion() {
        Path finalPath = unwrapComputedPath();
        processListenerIfPresent(finalPath);
        executeCallbackChain(finalPath);
    }

    @Nullable
    private Path unwrapComputedPath() {
        Path result = computedPath;

        return (result != SENTINEL_NULL_PATH) ? result : null;
    }

    private void processListenerIfPresent(@Nullable Path path) {
        if (navigationListener == null) {
            return;
        }

        PathNavigation navigation = navigationListener;
        navigationListener = null;

        if (path != null) {
            updateNavigationWithPath(navigation, path);
        }
    }

    private void updateNavigationWithPath(PathNavigation navigation, Path path) {
        navigation.path = path;
        navigation.targetPos = path.getTarget();
        navigation.reachRange = navigationAccuracy;
        navigation.resetStuckTimeout();
    }

    private void executeCallbackChain(@Nullable Path result) {
        if (isCompleted) {
            return;
        }

        isCompleted = true;
        invokeAllCallbacks(result);
        resetCallbackChain();
    }

    private void invokeAllCallbacks(@Nullable Path result) {
        callbackChain.callback.accept(result);

        CallbackChain current = callbackChain.next;
        while (current != null) {
            current.callback().accept(result);
            current = current.next;
        }
    }

    private void resetCallbackChain() {
        callbackChain = EMPTY_CALLBACK_CHAIN;
    }

    public static void moveTo(PathNavigation navigation, @Nullable Path path) {
        if (path == null) {
            clearNavigationPath(navigation);
            return;
        }

        AsyncPath asyncTask = extractAsyncTask(path);

        if (asyncTask == null || asyncTask.isTaskComplete()) {
            setNavigationPathDirectly(navigation, path, asyncTask);
        } else {
            attachNavigationListener(navigation, path, asyncTask);
        }
    }

    private static void clearNavigationPath(PathNavigation navigation) {
        navigation.path = null;
    }

    private static void setNavigationPathDirectly(PathNavigation navigation, Path path, @Nullable AsyncPath asyncTask) {
        navigation.path = (asyncTask != null) ? asyncTask.computedPath : path;
    }

    private static void attachNavigationListener(PathNavigation navigation, Path path, AsyncPath asyncTask) {
        asyncTask.navigationListener = navigation;

        if (navigation.path != null) {
            navigation.path.task = asyncTask;
        } else {
            navigation.path = path;
        }
    }

    public void stop() {
        navigationListener = null;
        complete();
    }

    record CallbackChain(Consumer<@Nullable Path> callback, @Nullable AsyncPath.CallbackChain next) { }

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
}
