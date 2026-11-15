package org.bxteam.divinemc.async;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Write-behind queue for player files (.dat, stats, advancements), keyed by the target path.
 * A newer write for a path that is still waiting replaces the waiting one; readers call {@link #await(Path)} first.
 */
@NullMarked
public final class PlayerFileWriter {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final PlayerFileWriter INSTANCE = new PlayerFileWriter(() -> DivineConfig.AsyncCategory.playerDataWriterEnabled, "DivineMC Player Data Writer");

    private final BooleanSupplier enabled;
    private final String threadName;
    private final Object lock = new Object();
    private final Map<Path, Runnable> queued = new HashMap<>();
    private final ArrayDeque<Path> order = new ArrayDeque<>();
    private @Nullable Path running;
    private @Nullable Thread thread;

    public PlayerFileWriter(final BooleanSupplier enabled, final String threadName) {
        this.enabled = enabled;
        this.threadName = threadName;
    }

    public void submit(final Path target, final Runnable write) {
        final Path key = key(target);
        if (!this.enabled.getAsBoolean()) {
            // a write queued before the option was turned off must not land after this one
            this.await(key);
            write.run();
            return;
        }
        synchronized (this.lock) {
            if (this.queued.put(key, write) == null) {
                this.order.add(key);
            }
            if (this.thread == null) {
                this.thread = Thread.ofPlatform().name(this.threadName).daemon(true).start(this::run);
            }
            this.lock.notifyAll();
        }
    }

    public void await(final Path target) {
        final Path key = key(target);
        boolean interrupted = false;
        synchronized (this.lock) {
            while (this.queued.containsKey(key) || key.equals(this.running)) {
                try {
                    this.lock.wait();
                } catch (final InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Adds to a listing of {@code dir} the files ending in {@code suffix} that are queued or being written, so a file
     * written for the first time is listed before it reaches the disk.
     */
    public String @Nullable [] withPending(final Path dir, final String @Nullable [] listed, final String suffix) {
        final Path parent = key(dir);
        LinkedHashSet<String> names = null;
        synchronized (this.lock) {
            for (final Path path : this.queued.keySet()) {
                names = addPending(names, listed, parent, path, suffix);
            }
            if (this.running != null) {
                names = addPending(names, listed, parent, this.running, suffix);
            }
        }
        return names == null ? listed : names.toArray(new String[0]);
    }

    private static @Nullable LinkedHashSet<String> addPending(@Nullable LinkedHashSet<String> names, final String @Nullable [] listed, final Path parent, final Path path, final String suffix) {
        final Path fileName = path.getFileName();
        if (fileName == null || !parent.equals(path.getParent()) || !fileName.toString().endsWith(suffix)) {
            return names;
        }
        if (names == null) {
            names = new LinkedHashSet<>();
            if (listed != null) {
                Collections.addAll(names, listed);
            }
        }
        names.add(fileName.toString());
        return names;
    }

    public void awaitAll() {
        boolean interrupted = false;
        synchronized (this.lock) {
            while (!this.order.isEmpty() || this.running != null) {
                try {
                    this.lock.wait();
                } catch (final InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (true) {
            final Path key;
            final Runnable write;
            synchronized (this.lock) {
                while (this.order.isEmpty()) {
                    try {
                        this.lock.wait();
                    } catch (final InterruptedException ignored) {
                    }
                }
                key = this.order.poll();
                write = this.queued.remove(key);
                this.running = key;
            }
            try {
                write.run();
            } catch (final Throwable t) {
                LOGGER.error("Failed to write player file {}", key, t);
            } finally {
                synchronized (this.lock) {
                    this.running = null;
                    this.lock.notifyAll();
                }
            }
        }
    }

    private static Path key(final Path path) {
        return path.toAbsolutePath().normalize();
    }
}
