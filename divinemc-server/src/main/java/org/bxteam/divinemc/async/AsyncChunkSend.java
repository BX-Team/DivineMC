package org.bxteam.divinemc.async;

import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AsyncChunkSend {
    public static final ExecutorService POOL = DivineConfig.AsyncCategory.asyncChunkSendingEnabled ? new ThreadPoolExecutor(
        1, DivineConfig.AsyncCategory.asyncChunkSendingMaxThreads, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        new NamedAgnosticThreadFactory<>("Async Chunk Sending", AsyncChunkSendThread::new, Thread.NORM_PRIORITY),
        new ThreadPoolExecutor.CallerRunsPolicy()
    ) : null;

    public static class AsyncChunkSendThread extends Thread {
        protected AsyncChunkSendThread(ThreadGroup group, Runnable task, String name) {
            super(group, task, name);
        }
    }
}

