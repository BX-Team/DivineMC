package org.bxteam.divinemc.region;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.bxteam.divinemc.region.format.ChunkRecord;
import org.bxteam.divinemc.region.format.LinearDictionaries;
import org.bxteam.divinemc.region.format.LinearFile;
import org.bxteam.divinemc.region.format.LinearFormat;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Region storage on the Linear format (linear-format/SPEC.md). Writes compress the chunk on the calling thread and
 * keep the record in memory; a flush about {@code flushDelayMs} later appends every pending record and publishes a
 * new index. Reads see pending records first, so a chunk reads back what was last written before it reaches disk.
 */
@NullMarked
public final class LinearRegionFile implements IRegionFile {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.linear$");
    private static final long MIN_COMPACTION_GARBAGE = 64 * 1024;
    // one live instance per path; a path being closed asynchronously must finish before it is opened again
    private static final Map<Path, LinearRegionFile> OPEN = new ConcurrentHashMap<>();
    private static final Map<Path, CompletableFuture<Void>> CLOSING = new ConcurrentHashMap<>();
    // paths being opened; OPEN, CLOSING and OPENING change together under REGISTRY
    private static final Set<Path> OPENING = new HashSet<>();
    private static final Object REGISTRY = new Object();
    // tests make the next publish fail: a real failing disk cannot be arranged once the file is open
    static volatile @Nullable IOException failNextPublishForTest;
    // tests make every publish fail until cleared, like a full disk
    static volatile @Nullable IOException failPublishesForTest;

    private final Path path;
    private final Path key;
    private final LinearFile file;
    private final LinearFlusher flusher;
    private final LinearDictionaries dictionaries;
    private final int dictionaryId;
    private final ReentrantLock flushLock = new ReentrantLock();
    // guarded by flushLock: the last flush failed and the flusher counts this region as failing
    private boolean failing;
    private final AtomicBoolean compactionQueued = new AtomicBoolean();

    // guarded by this
    private byte @Nullable [][] pending = new byte[LinearFormat.CHUNKS][];
    private boolean[] pendingDeleted = new boolean[LinearFormat.CHUNKS];
    private int pendingCount;
    private byte @Nullable [][] inFlight = new byte[LinearFormat.CHUNKS][];
    private boolean[] inFlightDeleted = new boolean[LinearFormat.CHUNKS];
    private long accountedBytes;
    private boolean flushScheduled;
    private boolean closing;
    private boolean closed;

    private LinearRegionFile(Path path, LinearFile file, LinearFlusher flusher, LinearDictionaries dictionaries) {
        this.path = path;
        this.key = path.toAbsolutePath().normalize();
        this.file = file;
        this.flusher = flusher;
        this.dictionaries = dictionaries;
        final Path folder = path.getParent();
        this.dictionaryId = folder == null ? 0 : dictionaries.newestFor(folder.getFileName().toString());
    }

    public static LinearRegionFile open(Path path, LinearFlusher flusher) throws IOException {
        return open(path, flusher, LinearDictionaries.shipped());
    }

    public static LinearRegionFile open(Path path, LinearFlusher flusher, LinearDictionaries dictionaries) throws IOException {
        final Path key = path.toAbsolutePath().normalize();
        final Matcher m = NAME.matcher(path.getFileName().toString());
        if (!m.matches()) {
            throw new IOException("Not a Linear region file name: " + path);
        }
        while (true) {
            final CompletableFuture<Void> busy;
            synchronized (REGISTRY) {
                busy = CLOSING.get(key);
                if (busy == null) {
                    if (OPEN.containsKey(key) || !OPENING.add(key)) {
                        throw new IllegalStateException("Linear region " + path + " is already open");
                    }
                    break;
                }
            }
            busy.join();
        }
        try {
            return openReserved(path, key, m, flusher, dictionaries);
        } finally {
            synchronized (REGISTRY) {
                OPENING.remove(key);
            }
        }
    }

    private static LinearRegionFile openReserved(Path path, Path key, Matcher m, LinearFlusher flusher, LinearDictionaries dictionaries) throws IOException {
        final LinearFile file = LinearFile.open(path, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), dictionaries);
        for (String warning : file.takeWarnings()) {
            LOGGER.warn("Linear region: {}", warning);
        }
        final LinearFile.Salvage salvage = file.salvage();
        if (salvage != LinearFile.Salvage.NONE) {
            try {
                file.publishSalvaged();
            } catch (IOException e) {
                file.close();
                throw new IOException("Failed to rewrite the salvaged Linear region " + path, e);
            }
            LOGGER.warn("Linear region {} was rebuilt from its log{}", path,
                salvage == LinearFile.Salvage.DAMAGED_SLOTS ? "; the damaged file is kept as " + path.getFileName() + ".corrupt" : "");
        }
        try {
            // RegionFileStorage answers reads with "no chunk" when the file does not exist; a region whose first
            // flush is still pending in an asynchronous close must already exist on disk
            file.createIfMissing();
        } catch (IOException e) {
            file.close();
            throw e;
        }
        final LinearRegionFile region = new LinearRegionFile(path, file, flusher, dictionaries);
        synchronized (REGISTRY) {
            OPEN.put(key, region);
        }
        if (region.shouldCompact(1.0)) {
            region.requestCompaction();
        }
        return region;
    }

    /**
     * Compacts every Linear file in the folders that has garbage: open regions on the compactor thread, closed files
     * there too while their path is held, so the server cannot open one mid-rewrite. Returns how many were queued.
     */
    static int compactFolders(List<Path> folders, LinearFlusher flusher, boolean wait) {
        final List<Path> files = new ArrayList<>();
        for (Path folder : folders) {
            try (Stream<Path> list = Files.list(folder)) {
                list.filter(p -> NAME.matcher(p.getFileName().toString()).matches()).sorted().forEach(files::add);
            } catch (IOException e) {
                // a folder that does not exist yet has nothing to compact
            }
        }
        final List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Path path : files) {
            final Path key = path.toAbsolutePath().normalize();
            final CompletableFuture<Void> done = new CompletableFuture<>();
            final LinearRegionFile open;
            synchronized (REGISTRY) {
                open = OPEN.get(key);
                if (open == null && (CLOSING.containsKey(key) || OPENING.contains(key))) continue;
            }
            final Runnable task = open != null ? open::compactFromScheduler : () -> {
                // reserved only once its turn comes: a file waiting in the queue must not hold back a chunk load
                synchronized (REGISTRY) {
                    if (OPEN.containsKey(key) || CLOSING.containsKey(key) || OPENING.contains(key)) {
                        done.complete(null);
                        return;
                    }
                    CLOSING.put(key, done);
                }
                try {
                    compactClosedFile(path);
                } finally {
                    synchronized (REGISTRY) {
                        CLOSING.remove(key, done);
                    }
                    done.complete(null);
                }
            };
            if (open != null) {
                pending.add(flusher.runCompaction(task));
            } else {
                flusher.runCompaction(task);
                pending.add(done);
            }
        }
        if (wait) {
            for (CompletableFuture<Void> f : pending) f.join();
        }
        return pending.size();
    }

    private static void compactClosedFile(Path path) {
        final Matcher m = NAME.matcher(path.getFileName().toString());
        if (!m.matches()) return;
        try (LinearFile file = LinearFile.open(path, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), LinearDictionaries.shipped())) {
            if (file.salvage() != LinearFile.Salvage.NONE) {
                file.publishSalvaged();
            } else {
                file.compact();
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to compact Linear region {}", path, e);
        }
    }

    static void flushAllOpen() {
        for (LinearRegionFile region : List.copyOf(OPEN.values())) {
            try {
                region.flushNow();
            } catch (IOException e) {
                LOGGER.error("Failed to flush Linear region {}", region.path, e);
            }
        }
    }

    @Override
    public Path getPath() {
        return this.path;
    }

    private static int index(ChunkPos pos) {
        return LinearFormat.localIndex(pos.x(), pos.z());
    }

    @Override
    public void write(ChunkPos pos, ByteBuffer buffer) throws IOException {
        final int index = index(pos);
        final byte[] raw;
        final int offset;
        final int length = buffer.remaining();
        if (buffer.hasArray()) {
            raw = buffer.array();
            offset = buffer.arrayOffset() + buffer.position();
        } else {
            raw = new byte[length];
            buffer.duplicate().get(raw);
            offset = 0;
        }
        if (length > LinearFormat.MAX_RAW_LENGTH) {
            // Paper's RegionFile drops such chunks the same way: writing garbage is worse than not saving
            LOGGER.error("Chunk {} in {} serialised to {} bytes, over the limit; not saved", pos, this.path, length);
            return;
        }
        final byte[] record = ChunkRecord.encode(index, raw, offset, length, System.currentTimeMillis(), this.dictionaryId, this.flusher.compressionLevel, this.dictionaries);
        synchronized (this) {
            if (this.closed) {
                throw new IOException("Linear region " + this.path + " is closed");
            }
            if (this.pending[index] == null && !this.pendingDeleted[index]) this.pendingCount++;
            this.pending[index] = record;
            this.pendingDeleted[index] = false;
            this.markDirty();
        }
        this.flusher.awaitCapacity(this);
    }

    @Override
    public void clear(ChunkPos pos) throws IOException {
        final int index = index(pos);
        synchronized (this) {
            if (this.closed) {
                throw new IOException("Linear region " + this.path + " is closed");
            }
            if (this.pending[index] == null && !this.pendingDeleted[index]) this.pendingCount++;
            this.pending[index] = null;
            this.pendingDeleted[index] = true;
            this.markDirty();
        }
    }

    // caller holds this
    private void markDirty() {
        this.updateAccounting();
        if (!this.flushScheduled) {
            this.flushScheduled = true;
            this.flusher.scheduleFlush(this, this.flusher.flushDelayMs);
        }
    }

    // caller holds this
    private void updateAccounting() {
        long bytes = 0;
        for (int i = 0; i < LinearFormat.CHUNKS; i++) {
            final byte[] p = this.pending[i];
            if (p != null) bytes += p.length;
            final byte[] f = this.inFlight[i];
            if (f != null) bytes += f.length;
        }
        this.flusher.addPending(bytes - this.accountedBytes);
        this.accountedBytes = bytes;
    }

    /** The record the newest write left in memory; null when the file decides. Deleted chunks give an empty array. */
    private synchronized byte @Nullable [] memoryRecord(int index) {
        if (this.pendingDeleted[index]) return EMPTY;
        if (this.pending[index] != null) return this.pending[index];
        if (this.inFlightDeleted[index]) return EMPTY;
        return this.inFlight[index];
    }

    private static final byte[] EMPTY = new byte[0];

    private byte @Nullable [] readRaw(int index) throws IOException {
        final byte[] record = this.memoryRecord(index);
        if (record != null) {
            return record.length == 0 ? null : ChunkRecord.decode(record, index, this.dictionaries);
        }
        return this.file.read(index);
    }

    @Override
    public @Nullable DataInputStream getChunkDataInputStream(ChunkPos pos) throws IOException {
        final byte[] raw = this.readRaw(index(pos));
        return raw == null ? null : new DataInputStream(new ByteArrayInputStream(raw));
    }

    @Override
    public boolean doesChunkExist(ChunkPos pos) {
        return this.hasChunk(pos);
    }

    @Override
    public boolean hasChunk(ChunkPos pos) {
        final int index = index(pos);
        final byte[] record = this.memoryRecord(index);
        if (record != null) {
            return record.length != 0;
        }
        return this.file.index().exists(index);
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) {
        return new DataOutputStream(new ChunkBuffer(pos, true));
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(CompoundTag data, ChunkPos pos) {
        // Moonrise closes the stream and then calls the write callback: only the callback may write
        final ChunkBuffer buffer = new ChunkBuffer(pos, false);
        return new MoonriseRegionFileIO.RegionDataController.WriteData(
            data,
            MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            new DataOutputStream(buffer),
            regionFile -> buffer.writeTo(regionFile)
        );
    }

    /** Flushes now, waiting for a flush already running; used by save-all flush, shutdown and close. */
    @Override
    public void flush() throws IOException {
        this.flushNow();
    }

    void flushFromScheduler() {
        try {
            this.flushNow();
        } catch (IOException e) {
            LOGGER.error("Failed to flush Linear region {}; retrying in {} s", this.path, this.flusher.retryDelayMs / 1000, e);
            synchronized (this) {
                if (!this.closed && this.pendingCount > 0 && !this.flushScheduled) {
                    this.flushScheduled = true;
                    this.flusher.scheduleFlush(this, this.flusher.retryDelayMs);
                }
            }
        }
    }

    private void flushNow() throws IOException {
        this.flushLock.lock();
        try {
            final byte[][] records;
            final boolean[] deleted;
            synchronized (this) {
                this.flushScheduled = false;
                if (this.pendingCount == 0) return;
                records = this.pending;
                deleted = this.pendingDeleted;
                this.inFlight = records;
                this.inFlightDeleted = deleted;
                this.pending = new byte[LinearFormat.CHUNKS][];
                this.pendingDeleted = new boolean[LinearFormat.CHUNKS];
                this.pendingCount = 0;
            }
            try {
                final IOException injected = failNextPublishForTest;
                if (injected != null) {
                    failNextPublishForTest = null;
                    throw injected;
                }
                final IOException persistent = failPublishesForTest;
                if (persistent != null) throw persistent;
                this.file.publish(records, deleted);
            } catch (Throwable t) {
                this.setFailing(true);
                synchronized (this) {
                    // writes made during the failed flush are newer and stay
                    for (int i = 0; i < LinearFormat.CHUNKS; i++) {
                        if (this.pending[i] != null || this.pendingDeleted[i] || (records[i] == null && !deleted[i])) continue;
                        this.pending[i] = records[i];
                        this.pendingDeleted[i] = deleted[i];
                        this.pendingCount++;
                    }
                    this.inFlight = new byte[LinearFormat.CHUNKS][];
                    this.inFlightDeleted = new boolean[LinearFormat.CHUNKS];
                    this.updateAccounting();
                }
                throw t;
            }
            synchronized (this) {
                this.inFlight = new byte[LinearFormat.CHUNKS][];
                this.inFlightDeleted = new boolean[LinearFormat.CHUNKS];
                this.updateAccounting();
            }
            this.setFailing(false);
        } finally {
            this.flushLock.unlock();
        }
        if (this.shouldCompact(1.0)) {
            this.requestCompaction();
        }
    }

    // caller holds flushLock
    private void setFailing(boolean failing) {
        if (this.failing != failing) {
            this.failing = failing;
            this.flusher.regionFailing(failing);
        }
    }

    private void requestCompaction() {
        if (this.compactionQueued.compareAndSet(false, true)) {
            this.flusher.requestCompaction(this);
        }
    }

    private boolean shouldCompact(double ratio) {
        final long garbage = this.file.garbageBytes();
        return garbage > MIN_COMPACTION_GARBAGE && garbage > this.file.liveBytes() * ratio;
    }

    void compactFromScheduler() {
        this.compactionQueued.set(false);
        synchronized (this) {
            if (this.closed) return;
        }
        this.compactQuietly();
    }

    private void compactQuietly() {
        try {
            this.file.compact();
        } catch (IOException e) {
            LOGGER.warn("Failed to compact Linear region {}; it stays as it is and is compacted later", this.path, e);
        }
    }

    /**
     * Closes now: flushes, then compacts when garbage exceeds the live data (or any garbage after /divinemc compact
     * shutdown). Used on shutdown and wherever the caller needs the data on disk.
     */
    @Override
    public void close() throws IOException {
        final CompletableFuture<Void> closing;
        synchronized (this) {
            closing = this.closing ? CLOSING.get(this.key) : null;
            if (!this.closing) {
                if (this.closed) return;
                this.closed = true;
            }
        }
        if (closing != null) {
            closing.join();
            return;
        }
        try {
            this.flushNow();
            if (this.shouldCompact(1.0) || (this.flusher.compactAllOnShutdown() && this.file.garbageBytes() > 0)) {
                this.compactQuietly();
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to flush Linear region {} while closing it; its unsaved chunks are lost", this.path, t);
            throw t;
        } finally {
            this.release();
        }
    }

    /**
     * Closes on the flusher: the caller (region cache eviction) does not wait; reopening the path does. Writes keep
     * being accepted until the last flush found nothing new, because Moonrise writes to a region it fetched from the
     * cache outside the cache lock and an eviction can land in between.
     */
    @Override
    public void closeAsync() {
        synchronized (this) {
            if (this.closed || this.closing) return;
            this.closing = true;
        }
        final CompletableFuture<Void> done = new CompletableFuture<>();
        synchronized (REGISTRY) {
            CLOSING.put(this.key, done);
        }
        this.flusher.submitClose(() -> {
            try {
                while (true) {
                    try {
                        this.flushNow();
                    } catch (IOException e) {
                        // the chunks were accepted as saved: keep them and the path reserved until the disk takes them
                        LOGGER.error("Failed to flush Linear region {} while closing it; retrying in {} s", this.path, this.flusher.retryDelayMs / 1000, e);
                        if (!this.flusher.awaitRetry()) throw e;
                        continue;
                    }
                    synchronized (this) {
                        if (this.pendingCount == 0) {
                            this.closed = true;
                            break;
                        }
                    }
                }
                if (!LinearFlusher.serverStopping() && this.shouldCompact(0.25)) {
                    this.compactQuietly();
                }
            } catch (Throwable t) {
                synchronized (this) {
                    this.closed = true;
                }
                LOGGER.error("Failed to flush Linear region {} while closing it; its unsaved chunks are lost", this.path, t);
            } finally {
                try {
                    this.release();
                } catch (IOException e) {
                    LOGGER.error("Failed to close Linear region {}", this.path, e);
                }
                synchronized (REGISTRY) {
                    CLOSING.remove(this.key, done);
                }
                done.complete(null);
            }
        });
    }

    private void release() throws IOException {
        this.flushLock.lock();
        try {
            this.setFailing(false);
        } finally {
            this.flushLock.unlock();
        }
        try {
            this.file.close();
        } finally {
            synchronized (this) {
                this.pending = new byte[LinearFormat.CHUNKS][];
                this.inFlight = new byte[LinearFormat.CHUNKS][];
                this.pendingCount = 0;
                this.updateAccounting();
            }
            synchronized (REGISTRY) {
                OPEN.remove(this.key, this);
            }
        }
    }

    // for tests
    long garbageBytes() {
        return this.file.garbageBytes();
    }

    private final class ChunkBuffer extends ByteArrayOutputStream {
        private final ChunkPos pos;
        private final boolean writeOnClose;

        ChunkBuffer(ChunkPos pos, boolean writeOnClose) {
            super(8192);
            this.pos = pos;
            this.writeOnClose = writeOnClose;
        }

        void writeTo(IRegionFile regionFile) throws IOException {
            regionFile.write(this.pos, ByteBuffer.wrap(this.buf, 0, this.count));
        }

        @Override
        public void close() throws IOException {
            if (this.writeOnClose) {
                this.writeTo(LinearRegionFile.this);
            }
        }
    }
}
