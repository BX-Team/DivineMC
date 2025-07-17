package gg.pufferfish.pufferfish.util;

import it.unimi.dsi.fastutil.PriorityQueue;
import it.unimi.dsi.fastutil.PriorityQueues;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import net.minecraft.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.spark.ThreadDumperRegistry;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.LockSupport;

public class AsyncExecutor implements Runnable {
    private static final Logger LOGGER = LogManager.getLogger(AsyncExecutor.class);

    public final Thread thread;
    private final PriorityQueue<Runnable> jobs = PriorityQueues.synchronize(new ObjectArrayFIFOQueue<>());
    private volatile boolean killswitch = false;

    public AsyncExecutor(String threadName) {
        this.thread = Thread.ofPlatform()
            .name(threadName)
            .priority(Thread.NORM_PRIORITY - 1)
            .daemon(false)
            .uncaughtExceptionHandler(Util::onThreadException)
            .unstarted(this);
    }

    public void start() {
        thread.start();
        ThreadDumperRegistry.REGISTRY.add(thread.getName());
    }

    public void join(long millis) throws InterruptedException {
        killswitch = true;
        LockSupport.unpark(thread);
        thread.join(millis);
    }

    public void submit(Runnable runnable) {
        jobs.enqueue(runnable);
        LockSupport.unpark(thread);
    }

    @Override
    public void run() {
        while (!killswitch) {
            try {
                Runnable runnable;
                try {
                    runnable = jobs.dequeue();
                } catch (NoSuchElementException e) {
                    LockSupport.park();
                    continue;
                }
                runnable.run();
            } catch (Exception e) {
                LOGGER.error("Failed to execute async job for thread {}", thread.getName(), e);
            }
        }
    }
}
