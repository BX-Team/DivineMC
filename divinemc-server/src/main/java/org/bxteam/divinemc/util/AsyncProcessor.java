package org.bxteam.divinemc.util;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.spark.ThreadDumperRegistry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AsyncProcessor {
    private static final Logger LOGGER = LogManager.getLogger(AsyncProcessor.class);

    private final BlockingQueue<Runnable> taskQueue;
    private final Thread workerThread;
    private volatile boolean isRunning;

    public AsyncProcessor(String threadName) {
        this.taskQueue = new LinkedBlockingQueue<>();
        this.isRunning = true;

        this.workerThread = new TickThread(() -> {
            while (isRunning || !taskQueue.isEmpty()) {
                try {
                    Runnable task = taskQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("An unexpected error occurred when running async processor: {}", e.getMessage(), e);
                }
            }
        }, threadName);

        ThreadDumperRegistry.REGISTRY.add(threadName);
        this.workerThread.start();
    }

    public void submit(Runnable task) {
        if (!isRunning) {
            throw new IllegalStateException("AsyncExecutor is not running.");
        }

        taskQueue.offer(task);
    }

    public void shutdown() {
        isRunning = false;
        workerThread.interrupt();
    }

    public void shutdownNow() {
        isRunning = false;
        workerThread.interrupt();
        taskQueue.clear();
    }

    public boolean isRunning() {
        return isRunning;
    }
}
