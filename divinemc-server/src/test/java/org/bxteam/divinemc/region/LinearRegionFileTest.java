package org.bxteam.divinemc.region;

import net.minecraft.world.level.ChunkPos;
import org.bukkit.support.environment.Normal;
import org.bxteam.divinemc.region.format.LinearDictionaries;
import org.bxteam.divinemc.region.format.LinearFile;
import org.bxteam.divinemc.region.format.LinearFormat;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class LinearRegionFileTest {
    private static final ChunkPos POS = new ChunkPos(35, -3);
    private static final byte[] PAYLOAD = {10, 0, 0, 1, 2, 3, 4, 5, 0};

    @TempDir
    Path dir;

    private @Nullable LinearFlusher flusher;

    @AfterEach
    void stopFlusher() {
        LinearRegionFile.failPublishesForTest = null;
        if (this.flusher != null) this.flusher.shutdown();
    }

    private LinearFlusher flusher(long delayMs, long maxPendingBytes) {
        this.flusher = new LinearFlusher(1, delayMs, 3, maxPendingBytes);
        return this.flusher;
    }

    private LinearFlusher flusher(long delayMs, long maxPendingBytes, long retryDelayMs) {
        this.flusher = new LinearFlusher(1, delayMs, 3, maxPendingBytes, retryDelayMs);
        return this.flusher;
    }

    private Path regionPath() throws IOException {
        final Path folder = this.dir.resolve("region");
        Files.createDirectories(folder);
        return folder.resolve("r.1.-1.linear");
    }

    private static byte @Nullable [] read(LinearRegionFile region, ChunkPos pos) throws IOException {
        try (DataInputStream in = region.getChunkDataInputStream(pos)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void write(LinearRegionFile region, ChunkPos pos, byte[] data) throws IOException {
        try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
            out.write(data);
        }
    }

    @Test
    void readsBackPendingFlushedAndReopened() throws IOException {
        final LinearFlusher flusher = this.flusher(60_000, 1 << 30);
        final Path path = this.regionPath();
        final LinearRegionFile region = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        assertTrue(Files.exists(path), "the file exists from the moment the region is opened");
        write(region, POS, PAYLOAD);
        try (LinearFile onDisk = LinearFile.openReadOnly(path, LinearDictionaries.none())) {
            assertFalse(onDisk.index().exists(LinearFormat.localIndex(POS.x(), POS.z())), "nothing reaches the disk before the flush");
        }
        assertArrayEquals(PAYLOAD, read(region, POS));
        assertTrue(region.hasChunk(POS));
        region.flush();
        assertArrayEquals(PAYLOAD, read(region, POS));
        region.clear(POS);
        assertFalse(region.hasChunk(POS));
        assertNull(read(region, POS));
        write(region, POS, PAYLOAD);
        region.close();

        final LinearRegionFile reopened = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        assertArrayEquals(PAYLOAD, read(reopened, POS));
        reopened.close();
        assertThrows(IOException.class, () -> write(reopened, POS, PAYLOAD));
    }

    @Test
    void moonriseWriteHappensOnlyThroughTheCallback() throws IOException {
        final LinearFlusher flusher = this.flusher(60_000, 1 << 30);
        final LinearRegionFile region = LinearRegionFile.open(this.regionPath(), flusher, LinearDictionaries.none());
        final var writeData = region.moonrise$startWrite(new net.minecraft.nbt.CompoundTag(), POS);
        writeData.output().write(PAYLOAD);
        writeData.output().close();
        assertFalse(region.hasChunk(POS), "closing the stream must not write");
        writeData.write().run(region);
        assertArrayEquals(PAYLOAD, read(region, POS));
        region.close();
    }

    @Test
    void scheduledFlushReachesTheDisk() throws Exception {
        final LinearFlusher flusher = this.flusher(20, 1 << 30);
        final Path path = this.regionPath();
        final LinearRegionFile region = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        write(region, POS, PAYLOAD);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean flushed = false;
        while (!flushed && System.nanoTime() < deadline) {
            if (Files.exists(path)) {
                try (LinearFile file = LinearFile.openReadOnly(path, LinearDictionaries.none())) {
                    flushed = file.index().exists(LinearFormat.localIndex(POS.x(), POS.z()));
                }
            }
            if (!flushed) Thread.sleep(10);
        }
        assertTrue(flushed, "the flusher wrote the chunk without an explicit flush");
        region.close();
    }

    @Test
    void reopenWaitsForAnAsynchronousClose() throws IOException {
        final LinearFlusher flusher = this.flusher(60_000, 1 << 30);
        final Path path = this.regionPath();
        for (int round = 0; round < 50; round++) {
            final LinearRegionFile region = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
            final byte[] data = ByteBuffer.allocate(8).putLong(round).array();
            write(region, POS, data);
            region.closeAsync();
            final LinearRegionFile reopened = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
            assertArrayEquals(data, read(reopened, POS), "round " + round);
            reopened.close();
        }
    }

    @Test
    void failedFlushKeepsTheChunksAndRetries() throws IOException {
        final LinearFlusher flusher = this.flusher(60_000, 1 << 30);
        final Path path = this.regionPath();
        final LinearRegionFile region = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        write(region, POS, PAYLOAD);
        LinearRegionFile.failNextPublishForTest = new IOException("disk full");
        assertThrows(IOException.class, region::flush);
        assertArrayEquals(PAYLOAD, read(region, POS), "a failed flush keeps the chunk readable");
        region.flush();
        region.close();
        final LinearRegionFile reopened = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        assertArrayEquals(PAYLOAD, read(reopened, POS), "the retry wrote it");
        reopened.close();
    }

    @Test
    void compactFoldersHandlesOpenAndClosedFiles() throws IOException {
        final LinearFlusher flusher = this.flusher(60_000, 1 << 30);
        final Path closedPath = this.regionPath();
        final Path openPath = closedPath.resolveSibling("r.2.-1.linear");
        LinearRegionFile open = null;
        for (Path path : List.of(closedPath, openPath)) {
            final LinearRegionFile region = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
            open = region;
            for (int round = 0; round < 2; round++) {
                for (int i = 0; i < 4; i++) write(region, new ChunkPos(i, 0), ByteBuffer.allocate(4000).putInt(round * 10 + i).array());
                region.flush();
            }
            write(region, new ChunkPos(0, 0), ByteBuffer.allocate(4000).putInt(99).array());
            region.flush();
            if (path.equals(closedPath)) region.close();
        }
        assertTrue(open.garbageBytes() > 0);

        assertEquals(2, LinearRegionFile.compactFolders(List.of(closedPath.getParent()), flusher, true));
        assertEquals(0, open.garbageBytes());
        assertArrayEquals(ByteBuffer.allocate(4000).putInt(99).array(), read(open, new ChunkPos(0, 0)));
        // closed files are compacted with the shipped dictionaries, as on a server
        try (LinearFile file = LinearFile.openReadOnly(closedPath, LinearDictionaries.shipped())) {
            assertEquals(0, file.garbageBytes());
            assertArrayEquals(ByteBuffer.allocate(4000).putInt(13).array(), file.read(3));
        }
        open.close();
    }

    @Test
    void writersWaitForTheDiskInsteadOfFillingTheHeap() throws Exception {
        final LinearFlusher flusher = this.flusher(60_000, 1);
        final Path path = this.regionPath();
        final LinearRegionFile region = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        final CompletableFuture<Void> writes = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 20; i++) write(region, new ChunkPos(32 + i, -32), PAYLOAD);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writes.get(30, TimeUnit.SECONDS);
        assertTrue(Files.exists(path), "the over-limit writer flushed its region instead of piling up records");
        region.close();
    }

    @Test
    void asynchronousCloseKeepsRetryingAFailedFlush() throws Exception {
        final LinearFlusher flusher = this.flusher(60_000, 1 << 30, 20);
        final Path path = this.regionPath();
        final LinearRegionFile region = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        write(region, POS, PAYLOAD);
        LinearRegionFile.failPublishesForTest = new IOException("disk full");
        region.closeAsync();
        final CompletableFuture<LinearRegionFile> reopen = CompletableFuture.supplyAsync(() -> {
            try {
                return LinearRegionFile.open(path, flusher, LinearDictionaries.none());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        assertFalse(reopen.isDone(), "the path stays reserved while the close retries");
        LinearRegionFile.failPublishesForTest = null;
        final LinearRegionFile reopened = reopen.get(30, TimeUnit.SECONDS);
        assertArrayEquals(PAYLOAD, read(reopened, POS), "the retried close wrote the chunk");
        reopened.close();
    }

    @Test
    void writersOverTheLimitGetAnErrorWhileFlushesFail() throws Exception {
        final LinearFlusher flusher = this.flusher(60_000, 1);
        final Path path = this.regionPath();
        final LinearRegionFile region = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        LinearRegionFile.failPublishesForTest = new IOException("disk full");
        final CompletableFuture<Void> writes = CompletableFuture.runAsync(() -> {
            try {
                write(region, POS, PAYLOAD);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        final Exception failure = assertThrows(Exception.class, () -> writes.get(30, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof RuntimeException && failure.getCause().getCause() instanceof IOException, "the writer got an error instead of waiting: " + failure);
        assertArrayEquals(PAYLOAD, read(region, POS), "the chunk stays queued");
        LinearRegionFile.failPublishesForTest = null;
        region.flush();
        region.close();
        final LinearRegionFile reopened = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        assertArrayEquals(PAYLOAD, read(reopened, POS));
        reopened.close();
    }

    @Test
    void queuedCompactionDoesNotHoldBackOpening() throws Exception {
        final LinearFlusher flusher = this.flusher(60_000, 1 << 30);
        final Path path = this.regionPath();
        final LinearRegionFile region = LinearRegionFile.open(path, flusher, LinearDictionaries.none());
        for (int round = 0; round < 3; round++) {
            write(region, POS, ByteBuffer.allocate(100_000).putInt(round).array());
            region.flush();
        }
        region.close();
        final CompletableFuture<Void> blocker = new CompletableFuture<>();
        flusher.runCompaction(blocker::join);
        assertEquals(1, flusher.compactNow(List.of(path.getParent())));
        final CompletableFuture<LinearRegionFile> reopen = CompletableFuture.supplyAsync(() -> {
            try {
                return LinearRegionFile.open(path, flusher, LinearDictionaries.none());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        final LinearRegionFile reopened;
        try {
            reopened = reopen.get(10, TimeUnit.SECONDS);
        } finally {
            blocker.complete(null);
        }
        assertArrayEquals(ByteBuffer.allocate(100_000).putInt(2).array(), read(reopened, POS));
        reopened.close();
    }
}
