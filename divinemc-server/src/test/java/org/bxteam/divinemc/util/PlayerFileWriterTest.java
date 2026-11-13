package org.bxteam.divinemc.util;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.support.environment.Normal;
import org.bxteam.divinemc.async.PlayerFileWriter;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class PlayerFileWriterTest {
    private static final Path A = Path.of("players/data/a.dat");
    private static final Path C = Path.of("players/stats/c.json");
    private static final Path D = Path.of("players/advancements/d.json");

    // a quit followed by a rejoin reads the file right after the save was queued
    @Test
    void readerSeesOnlyTheLatestWriteOfItsPath() throws Exception {
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final PlayerFileWriter writer = new PlayerFileWriter(enabled::get, "test writer");
        final List<String> written = new CopyOnWriteArrayList<>();
        final CountDownLatch blocker = new CountDownLatch(1);
        final CountDownLatch blocking = new CountDownLatch(1);

        writer.submit(C, () -> {
            blocking.countDown();
            await(blocker);
            written.add("c");
        });
        assertTrue(blocking.await(10, TimeUnit.SECONDS));
        writer.submit(A, () -> written.add("a1"));
        writer.submit(D, () -> {
            throw new IllegalStateException("disk full");
        });
        writer.submit(A, () -> written.add("a2"));
        writer.submit(Path.of("players/data/../data/b.dat"), () -> written.add("b"));

        final CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> writer.await(A));
        Thread.sleep(100);
        assertFalse(reader.isDone(), "await returned while a write of its path was still queued");

        blocker.countDown();
        reader.get(10, TimeUnit.SECONDS);
        writer.awaitAll();
        assertEquals(List.of("c", "a2", "b"), written);

        enabled.set(false);
        writer.submit(A, () -> written.add("inline"));
        assertEquals("inline", written.getLast());
    }

    // turning the option off must not let a write queued earlier land after the inline one
    @Test
    void inlineWriteWaitsForQueuedWriteOfSamePath() throws Exception {
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final PlayerFileWriter writer = new PlayerFileWriter(enabled::get, "test writer");
        final List<String> written = new CopyOnWriteArrayList<>();
        final CountDownLatch blocker = new CountDownLatch(1);

        writer.submit(A, () -> {
            await(blocker);
            written.add("queued");
        });
        enabled.set(false);
        final CompletableFuture<Void> inline = CompletableFuture.runAsync(() -> writer.submit(A, () -> written.add("inline")));
        Thread.sleep(100);
        assertFalse(inline.isDone());
        blocker.countDown();
        inline.get(10, TimeUnit.SECONDS);
        assertEquals(List.of("queued", "inline"), written);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
