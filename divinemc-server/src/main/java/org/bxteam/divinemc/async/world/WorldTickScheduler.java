package org.bxteam.divinemc.async.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bxteam.divinemc.spark.ThreadDumperRegistry;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Ticks the levels of one server tick in parallel: every level has its own named thread, at most {@code threads}
 * levels tick at once, and the levels that took longest last tick start first. The server thread waits for all of
 * them and then rethrows the first failure; {@link MinecraftServer#tickLevel} builds it as for the serial loop.
 */
@NullMarked
public final class WorldTickScheduler {
    private static final Comparator<LevelWorker> LONGEST_FIRST = Comparator.comparingLong((LevelWorker worker) -> worker.lastTickNanos).reversed();

    private final MinecraftServer server;
    private final Semaphore permits;
    private final Map<ServerLevel, LevelWorker> workers = new IdentityHashMap<>();
    private final List<LevelWorker> prepared = new ArrayList<>();
    private final List<LevelWorker> levelOrder = new ArrayList<>();
    private final AtomicInteger running = new AtomicInteger();
    private @Nullable Thread waiter;

    public WorldTickScheduler(final MinecraftServer server, final int threads) {
        this.server = server;
        this.permits = new Semaphore(threads);
    }

    public void prepare(final ServerLevel level, final BooleanSupplier haveTime) {
        LevelWorker worker;
        synchronized (this.workers) {
            worker = this.workers.get(level);
            if (worker == null) {
                worker = new LevelWorker(this, level);
                this.workers.put(level, worker);
            }
        }
        worker.haveTime = haveTime;
        this.prepared.add(worker);
        this.levelOrder.add(worker);
    }

    LevelHandoff.@Nullable Slot slot(final ServerLevel level) {
        synchronized (this.workers) {
            final LevelWorker worker = this.workers.get(level);
            return worker == null ? null : worker.slot;
        }
    }

    public void defer(final ServerLevel source, final Runnable task) {
        final LevelWorker worker;
        synchronized (this.workers) {
            worker = this.workers.get(source);
        }
        if (worker == null) {
            throw new IllegalStateException("Cross-world task queued for level " + source.getWorld().getName() + " that is not ticked in parallel");
        }
        synchronized (worker.deferred) {
            worker.deferred.add(task);
        }
    }

    public void tickPrepared() {
        final List<LevelWorker> levels = this.prepared;
        levels.sort(LONGEST_FIRST);
        this.waiter = Thread.currentThread();
        this.running.set(levels.size());
        for (final LevelWorker worker : levels) {
            this.permits.acquireUninterruptibly();
            worker.start();
        }
        while (this.running.get() != 0) {
            LockSupport.park(this);
        }

        ReportedException failure = null;
        for (final LevelWorker worker : levels) {
            if (failure == null) {
                failure = worker.failure;
            }
            worker.failure = null;
        }
        levels.clear();
        if (failure != null) {
            this.levelOrder.clear();
            throw failure;
        }

        try {
            for (final LevelWorker worker : this.levelOrder) {
                worker.runDeferred();
            }
        } finally {
            this.levelOrder.clear();
        }
    }

    public void remove(final ServerLevel level) {
        final LevelWorker worker;
        synchronized (this.workers) {
            worker = this.workers.remove(level);
        }
        if (worker != null) {
            worker.stop();
        }
    }

    private void finished() {
        this.permits.release();
        if (this.running.decrementAndGet() == 0) {
            LockSupport.unpark(this.waiter);
        }
    }

    private static final class LevelWorker implements Runnable {
        private final WorldTickScheduler scheduler;
        private final ServerLevel level;
        private final LevelTickThread thread;
        private final String name;
        private volatile boolean go;
        private volatile boolean stopped;
        private BooleanSupplier haveTime = () -> false;
        private long lastTickNanos;
        private @Nullable ReportedException failure;
        private final List<Runnable> deferred = new ArrayList<>();
        private final LevelHandoff.Slot slot;

        private LevelWorker(final WorldTickScheduler scheduler, final ServerLevel level) {
            this.scheduler = scheduler;
            this.level = level;
            this.slot = new LevelHandoff.Slot(level);
            this.name = level.getWorld().getName() + " - ServerLevel Tick Worker";
            ThreadDumperRegistry.REGISTRY.add(this.name);
            this.thread = new LevelTickThread(this, this.name);
            this.thread.currentlyTickingServerLevel = level;
            // only ever parked between ticks; the server thread waits for it during one
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void start() {
            this.go = true;
            LockSupport.unpark(this.thread);
        }

        private void stop() {
            ThreadDumperRegistry.REGISTRY.remove(this.name);
            this.stopped = true;
            LockSupport.unpark(this.thread);
        }

        private void runDeferred() {
            if (this.deferred.isEmpty()) {
                return;
            }
            try {
                for (int i = 0; i < this.deferred.size(); i++) {
                    this.deferred.get(i).run();
                }
            } catch (final ReportedException failure) {
                throw failure;
            } catch (final Throwable throwable) {
                final CrashReport report = CrashReport.forThrowable(throwable, "Exception ticking world");
                this.level.fillReportDetails(report);
                throw new ReportedException(report);
            } finally {
                this.deferred.clear();
            }
        }

        @Override
        public void run() {
            while (true) {
                while (!this.go) {
                    if (this.stopped) {
                        return;
                    }
                    LockSupport.park(this);
                }
                this.go = false;

                LevelHandoff.beginTick(this.thread, this.slot);
                final long start = System.nanoTime();
                try {
                    this.scheduler.server.tickLevel(this.level, this.haveTime);
                    this.level.explosionDensityCache.clear();
                } catch (final ReportedException failure) {
                    this.failure = failure;
                } catch (final Throwable throwable) {
                    final CrashReport report = CrashReport.forThrowable(throwable, "Exception ticking world");
                    this.level.fillReportDetails(report);
                    this.failure = new ReportedException(report);
                } finally {
                    LevelHandoff.endTick(this.thread, this.slot);
                    this.lastTickNanos = System.nanoTime() - start;
                    this.scheduler.finished();
                }
            }
        }
    }
}
