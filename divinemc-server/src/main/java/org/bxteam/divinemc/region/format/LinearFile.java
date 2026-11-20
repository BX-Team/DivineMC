package org.bxteam.divinemc.region.format;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One Linear file on disk, without any Minecraft types: open with recovery, read, publish a new state, compact.
 * Reads run concurrently with each other and with a publish; publish and compact are serialized, and only the end of a
 * compaction (the file swap) blocks reads.
 */
@NullMarked
public final class LinearFile implements AutoCloseable {
    private static final int RENAME_ATTEMPTS = 5;

    private final Path path;
    private final int regionX;
    private final int regionZ;
    private final LinearDictionaries dictionaries;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantReadWriteLock channelLock = new ReentrantReadWriteLock();
    private final List<String> warnings = new ArrayList<>();
    private @Nullable FileChannel channel;
    private volatile LinearIndex index = LinearIndex.EMPTY;
    private volatile long indexLength;
    private volatile long fileEnd;
    // slot (0 = A, 1 = B) that holds the current generation, -1 when no slot is valid
    private int currentSlot = -1;
    private boolean closed;
    private Salvage salvage = Salvage.NONE;

    /** How the state was found when no slot was valid (SPEC.md, "Salvage"). */
    public enum Salvage {
        NONE,
        /** both slots empty but records in the log: a crash during the file's first flush */
        FIRST_FLUSH_CUT,
        /** a slot had been written but is unreadable: real damage */
        DAMAGED_SLOTS
    }

    private LinearFile(Path path, int regionX, int regionZ, LinearDictionaries dictionaries) {
        this.path = path;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.dictionaries = dictionaries;
    }

    public static LinearFile open(Path path, int regionX, int regionZ, LinearDictionaries dictionaries) throws IOException {
        final LinearFile file = new LinearFile(path, regionX, regionZ, dictionaries);
        Files.deleteIfExists(compactPath(path));
        if (!Files.exists(path)) {
            return file;
        }
        final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            file.channel = channel;
            file.recover();
        } catch (Throwable t) {
            try {
                channel.close();
            } catch (IOException e) {
                t.addSuppressed(e);
            }
            throw t;
        }
        return file;
    }

    /** Reads only the state of a file, without keeping it open for writing; for tools and checks. */
    public static LinearFile openReadOnly(Path path, LinearDictionaries dictionaries) throws IOException {
        final LinearFile file = new LinearFile(path, 0, 0, dictionaries);
        final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        file.channel = channel;
        try {
            file.recover();
        } catch (Throwable t) {
            channel.close();
            throw t;
        }
        return file;
    }

    private static Path compactPath(Path path) {
        return path.resolveSibling(path.getFileName() + ".compact");
    }

    private void recover() throws IOException {
        final FileChannel ch = this.channel;
        final long size = ch.size();
        this.fileEnd = size;
        if (size < LinearFormat.LOG_START) {
            // cut off while being created: holds nothing, the first publish recreates it
            this.fileEnd = 0;
            return;
        }
        final byte[] head = readFully(ch, 0, LinearFormat.LOG_START);
        final ByteBuffer header = ByteBuffer.wrap(head);
        final long magic = header.getLong();
        if (magic == LinearFormat.LEGACY_MAGIC) {
            throw new LinearFormatException.Legacy(this.path + " is in a legacy Linear layout; convert it with the region converter");
        }
        if (magic != LinearFormat.MAGIC) {
            throw new LinearFormatException(this.path + " is not a Linear file");
        }
        final int major = header.getShort() & 0xFFFF;
        header.getShort();
        header.getInt();
        final int fileRegionX = header.getInt();
        final int fileRegionZ = header.getInt();
        if (header.getLong() != LinearFormat.xxh64(head, 0, 24)) {
            throw new LinearFormatException(this.path + " has a corrupt header");
        }
        if (major != LinearFormat.MAJOR_VERSION) {
            throw new LinearFormatException(this.path + " is Linear format " + major + ", this build reads " + LinearFormat.MAJOR_VERSION);
        }
        if (fileRegionX != this.regionX || fileRegionZ != this.regionZ) {
            this.warnings.add(this.path + " says it holds region " + fileRegionX + "," + fileRegionZ);
        }

        final LinearFormat.Slot[] slots = {
            LinearFormat.Slot.parse(head, LinearFormat.SLOT_A_OFFSET),
            LinearFormat.Slot.parse(head, LinearFormat.SLOT_B_OFFSET)
        };
        final LinearIndex[] indexes = new LinearIndex[2];
        for (int i = 0; i < 2; i++) {
            final LinearFormat.Slot slot = slots[i];
            if (slot.generation() == 0) continue;
            if (!slot.checksumValid() || slot.indexOffset() < LinearFormat.LOG_START || slot.indexLength() < LinearFormat.INDEX_HEADER_BYTES
                || slot.indexOffset() + slot.indexLength() > size) {
                this.warnings.add(this.path + ": slot " + (i == 0 ? "A" : "B") + " (generation " + slot.generation() + ") is not valid");
                continue;
            }
            indexes[i] = LinearIndex.decode(readFully(ch, slot.indexOffset(), slot.indexLength()), slot.generation(), slot.indexOffset());
            if (indexes[i] == null) {
                this.warnings.add(this.path + ": index of slot " + (i == 0 ? "A" : "B") + " (generation " + slot.generation() + ") is not valid");
            }
        }
        if (indexes[0] != null && indexes[1] != null && indexes[0].generation == indexes[1].generation) {
            throw new LinearFormatException(this.path + " has two slots with generation " + indexes[0].generation);
        }
        int current = -1;
        for (int i = 0; i < 2; i++) {
            if (indexes[i] != null && (current < 0 || indexes[i].generation > indexes[current].generation)) current = i;
        }
        this.currentSlot = current;
        if (current < 0 && size > LinearFormat.LOG_START) {
            final boolean damaged = slots[0].generation() != 0 || slots[1].generation() != 0;
            this.index = salvageLog(readFully(ch, 0, Math.toIntExact(size)));
            this.salvage = damaged ? Salvage.DAMAGED_SLOTS : Salvage.FIRST_FLUSH_CUT;
            this.warnings.add(this.path + ": no valid slot; salvaged " + this.index.chunkCount() + " chunks by scanning the log"
                + (damaged ? " (the slots are damaged)" : " (cut off during its first flush)"));
            return;
        }
        if (current >= 0) {
            this.index = indexes[current];
            this.indexLength = slots[current].indexLength();
            if (slots[1 - current].generation() > slots[current].generation()) {
                this.warnings.add(this.path + ": fell back to generation " + slots[current].generation());
            }
        }
    }

    /** SPEC.md, "Salvage": the newest checksummed record of every chunk anywhere in the log. */
    static LinearIndex salvageLog(byte[] bytes) {
        final long[] offsets = new long[LinearFormat.CHUNKS];
        final int[] lengths = new int[LinearFormat.CHUNKS];
        final long[] timestamps = new long[LinearFormat.CHUNKS];
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        int pos = LinearFormat.LOG_START;
        while (pos + LinearFormat.RECORD_HEADER_BYTES <= bytes.length) {
            final int magic = buf.getInt(pos);
            if (magic == LinearFormat.RECORD_MAGIC) {
                final int index = buf.getShort(pos + 4) & 0xFFFF;
                final int compressed = buf.getInt(pos + 12);
                final int raw = buf.getInt(pos + 16);
                final long end = (long) pos + LinearFormat.RECORD_HEADER_BYTES + compressed;
                if (index < LinearFormat.CHUNKS && compressed >= 0 && raw >= 0 && raw <= LinearFormat.MAX_RAW_LENGTH
                    && compressed <= LinearFormat.maxCompressedLength(raw) && end <= bytes.length
                    && LinearFormat.xxh64(bytes, pos + LinearFormat.RECORD_HEADER_BYTES, compressed) == buf.getLong(pos + 28)) {
                    final long timestamp = buf.getLong(pos + 20);
                    if (offsets[index] == 0 || timestamp >= timestamps[index]) {
                        offsets[index] = pos;
                        lengths[index] = (int) (end - pos);
                        timestamps[index] = timestamp;
                    }
                    pos = (int) end;
                    continue;
                }
            } else if (magic == LinearFormat.INDEX_MAGIC && pos + LinearFormat.INDEX_HEADER_BYTES <= bytes.length) {
                final int compressed = buf.getInt(pos + 16);
                final long end = (long) pos + LinearFormat.INDEX_HEADER_BYTES + compressed;
                if (compressed >= 0 && end <= bytes.length
                    && LinearFormat.xxh64(bytes, pos + LinearFormat.INDEX_HEADER_BYTES, compressed) == buf.getLong(pos + 20)) {
                    pos = (int) end;
                    continue;
                }
            }
            pos++;
        }
        return new LinearIndex(0, offsets, lengths, timestamps);
    }

    public Salvage salvage() {
        return this.salvage;
    }

    /**
     * Makes a salvaged state durable: keeps the damaged original as {@code <name>.corrupt} when the slots were damaged,
     * then rewrites the file from the salvaged records.
     */
    public void publishSalvaged() throws IOException {
        if (this.salvage == Salvage.NONE) return;
        if (this.salvage == Salvage.DAMAGED_SLOTS) {
            Files.copy(this.path, this.path.resolveSibling(this.path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
        }
        this.compactInternal(true);
        this.salvage = Salvage.NONE;
    }

    public List<String> takeWarnings() {
        final List<String> out = List.copyOf(this.warnings);
        this.warnings.clear();
        return out;
    }

    public Path path() {
        return this.path;
    }

    public LinearIndex index() {
        return this.index;
    }

    public long liveBytes() {
        return this.index.liveBytes;
    }

    public long garbageBytes() {
        final long end = this.fileEnd;
        if (end < LinearFormat.LOG_START) return 0;
        return Math.max(0, end - LinearFormat.LOG_START - this.index.liveBytes - this.indexLength);
    }

    public long fileBytes() {
        return this.fileEnd;
    }

    /** The decompressed NBT of a chunk, or null when the chunk does not exist. */
    public byte @Nullable [] read(int localIndex) throws IOException {
        final byte[] record = this.readRecord(localIndex);
        return record == null ? null : ChunkRecord.decode(record, localIndex, this.dictionaries);
    }

    /** The raw record bytes of a chunk as stored, or null when the chunk does not exist. */
    public byte @Nullable [] readRecord(int localIndex) throws IOException {
        this.channelLock.readLock().lock();
        try {
            final LinearIndex idx = this.index;
            if (!idx.exists(localIndex)) return null;
            final FileChannel ch = this.channel;
            if (ch == null) throw new IOException(this.path + " is closed");
            return readFully(ch, idx.offsets[localIndex], idx.lengths[localIndex]);
        } finally {
            this.channelLock.readLock().unlock();
        }
    }

    /**
     * Publishes a new state (SPEC.md, "Flush"). {@code records[i]} is the new record of chunk i (from
     * {@link ChunkRecord#encode}), {@code deleted[i]} removes chunk i; chunks with neither keep their current record.
     * On failure the current state stays published and nothing is lost; the call can be repeated.
     */
    public void publish(byte @Nullable [][] records, boolean[] deleted) throws IOException {
        this.writeLock.lock();
        try {
            this.ensureOpenForWrite();
            final FileChannel ch = this.channel;
            final LinearIndex old = this.index;
            final long start = ch.size();
            final long[] offsets = old.offsets.clone();
            final int[] lengths = old.lengths.clone();
            final long[] timestamps = old.timestamps.clone();
            int appendBytes = 0;
            for (int i = 0; i < LinearFormat.CHUNKS; i++) {
                if (records[i] != null) appendBytes += records[i].length;
            }
            final ByteBuffer out = ByteBuffer.allocate(appendBytes);
            long pos = start;
            for (int i = 0; i < LinearFormat.CHUNKS; i++) {
                final byte[] record = records[i];
                if (record != null) {
                    offsets[i] = pos;
                    lengths[i] = record.length;
                    timestamps[i] = ChunkRecord.timestamp(record);
                    out.put(record);
                    pos += record.length;
                } else if (deleted[i]) {
                    offsets[i] = 0;
                    lengths[i] = 0;
                    timestamps[i] = 0;
                }
            }
            final LinearIndex next = new LinearIndex(old.generation + 1, offsets, lengths, timestamps);
            final byte[] indexBytes = next.encode();
            out.flip();
            writeFully(ch, out, start);
            writeFully(ch, ByteBuffer.wrap(indexBytes), pos);
            ch.force(false);
            final int slot = this.currentSlot == 0 ? 1 : 0;
            writeFully(ch, ByteBuffer.wrap(LinearFormat.slot(next.generation, pos, indexBytes.length)), slot == 0 ? LinearFormat.SLOT_A_OFFSET : LinearFormat.SLOT_B_OFFSET);
            ch.force(false);
            this.currentSlot = slot;
            this.indexLength = indexBytes.length;
            this.fileEnd = pos + indexBytes.length;
            this.index = next;
        } catch (Throwable t) {
            // a failed append leaves bytes past the published index; they are garbage until compaction
            final FileChannel ch = this.channel;
            if (ch != null) {
                try {
                    this.fileEnd = Math.max(this.fileEnd, ch.size());
                } catch (IOException e) {
                    t.addSuppressed(e);
                }
            }
            throw t;
        } finally {
            this.writeLock.unlock();
        }
    }

    /** Creates the file (header and empty slots) if it does not exist yet, so the path exists before any flush. */
    public void createIfMissing() throws IOException {
        this.writeLock.lock();
        try {
            this.ensureOpenForWrite();
        } finally {
            this.writeLock.unlock();
        }
    }

    private void ensureOpenForWrite() throws IOException {
        if (this.closed) throw new IOException(this.path + " is closed");
        if (this.channel != null && this.fileEnd >= LinearFormat.LOG_START) return;
        if (this.channel == null) {
            this.channel = FileChannel.open(this.path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
        final FileChannel ch = this.channel;
        ch.truncate(0);
        final ByteBuffer head = ByteBuffer.allocate(LinearFormat.LOG_START);
        head.put(LinearFormat.header(this.regionX, this.regionZ));
        head.position(LinearFormat.LOG_START).flip();
        writeFully(ch, head, 0);
        ch.force(true);
        syncDirectory(this.path.toAbsolutePath().getParent());
        this.currentSlot = -1;
        this.index = LinearIndex.EMPTY;
        this.indexLength = 0;
        this.fileEnd = LinearFormat.LOG_START;
    }

    /**
     * Rewrites the file with only the live records (SPEC.md, "Compaction"). The old file stays in use if anything
     * fails. Returns false when there was nothing to compact.
     */
    public boolean compact() throws IOException {
        return this.compactInternal(false);
    }

    private boolean compactInternal(boolean force) throws IOException {
        this.writeLock.lock();
        try {
            if (this.closed || this.channel == null || this.fileEnd < LinearFormat.LOG_START || (!force && this.garbageBytes() == 0)) return false;
            final LinearIndex old = this.index;
            final Path tmp = compactPath(this.path);
            Files.deleteIfExists(tmp);
            final long[] offsets = new long[LinearFormat.CHUNKS];
            final int[] lengths = old.lengths.clone();
            final long[] timestamps = old.timestamps.clone();
            final LinearIndex next;
            final byte[] indexBytes;
            long pos = LinearFormat.LOG_START;
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                for (int i = 0; i < LinearFormat.CHUNKS; i++) {
                    if (!old.exists(i)) continue;
                    final byte[] record = readFully(this.channel, old.offsets[i], old.lengths[i]);
                    writeFully(out, ByteBuffer.wrap(record), pos);
                    offsets[i] = pos;
                    pos += record.length;
                }
                next = new LinearIndex(old.generation + 1, offsets, lengths, timestamps);
                indexBytes = next.encode();
                writeFully(out, ByteBuffer.wrap(indexBytes), pos);
                final ByteBuffer head = ByteBuffer.allocate(LinearFormat.LOG_START);
                head.put(LinearFormat.header(this.regionX, this.regionZ)).put(LinearFormat.slot(next.generation, pos, indexBytes.length));
                head.position(LinearFormat.LOG_START).flip();
                writeFully(out, head, 0);
                out.force(true);
            } catch (Throwable t) {
                Files.deleteIfExists(tmp);
                throw t;
            }

            this.channelLock.writeLock().lock();
            try {
                // closed first: Windows refuses to replace a file this process still has open
                this.channel.close();
                this.channel = null;
                IOException failure = null;
                for (int attempt = 0; attempt < RENAME_ATTEMPTS; attempt++) {
                    try {
                        moveReplacing(tmp, this.path);
                        failure = null;
                        break;
                    } catch (IOException e) {
                        failure = e;
                        try {
                            Thread.sleep(20L << attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                this.channel = FileChannel.open(this.path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                if (failure != null) {
                    Files.deleteIfExists(tmp);
                    throw failure;
                }
                syncDirectory(this.path.toAbsolutePath().getParent());
                this.currentSlot = 0;
                this.indexLength = indexBytes.length;
                this.fileEnd = pos + indexBytes.length;
                this.index = next;
            } finally {
                this.channelLock.writeLock().unlock();
            }
            return true;
        } finally {
            this.writeLock.unlock();
        }
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void syncDirectory(@Nullable Path dir) {
        if (dir == null) return;
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // directories cannot be opened or synced on Windows; NTFS journals the rename itself
        }
    }

    @Override
    public void close() throws IOException {
        this.writeLock.lock();
        try {
            this.channelLock.writeLock().lock();
            try {
                this.closed = true;
                if (this.channel != null) {
                    this.channel.close();
                    this.channel = null;
                }
            } finally {
                this.channelLock.writeLock().unlock();
            }
        } finally {
            this.writeLock.unlock();
        }
    }

    static byte[] readFully(FileChannel ch, long position, int length) throws IOException {
        final ByteBuffer buf = ByteBuffer.allocate(length);
        long pos = position;
        while (buf.hasRemaining()) {
            final int n = ch.read(buf, pos);
            if (n < 0) throw new LinearFormatException("Unexpected end of file at " + pos);
            pos += n;
        }
        return buf.array();
    }

    static void writeFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            pos += ch.write(buf, pos);
        }
    }
}
