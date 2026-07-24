package org.bxteam.divinemc.async;

import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AsyncChunkSend {
    public static final ExecutorService POOL = DivineConfig.AsyncCategory.asyncChunkSendingEnabled ? newPool() : null;

    private static ExecutorService newPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            DivineConfig.AsyncCategory.asyncChunkSendingMaxThreads, DivineConfig.AsyncCategory.asyncChunkSendingMaxThreads,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedAgnosticThreadFactory<>("Async Chunk Sending", AsyncChunkSendThread::new, Thread.NORM_PRIORITY),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public static class AsyncChunkSendThread extends Thread {
        protected AsyncChunkSendThread(ThreadGroup group, Runnable task, String name) {
            super(group, task, name);
        }
    }
}

