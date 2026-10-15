package org.bxteam.divinemc.async;

import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactoryAffinity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.bxteam.divinemc.config.DivineConfig.GuruMode.asyncChunkSendingAffinity;

public class AsyncChunkSend {
    public static final ExecutorService POOL = DivineConfig.AsyncCategory.asyncChunkSendingEnabled ? newPool() : null;

    private static ExecutorService newPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            DivineConfig.AsyncCategory.asyncChunkSendingMaxThreads, DivineConfig.AsyncCategory.asyncChunkSendingMaxThreads,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedAgnosticThreadFactoryAffinity<>("Async Chunk Sending", AsyncChunkSendThread::new,
                Thread.NORM_PRIORITY, asyncChunkSendingAffinity
            ),
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

