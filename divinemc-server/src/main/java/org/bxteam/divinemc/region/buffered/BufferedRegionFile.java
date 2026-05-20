package org.bxteam.divinemc.region.buffered;

import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.Validate;
import org.bxteam.divinemc.region.IRegionFile;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A buffered region file implementation that provides efficient chunk storage and retrieval
 * with compression, checksums, and automatic compaction capabilities.
 *
 * <p>For conversion tools between MCA and buffered region file formats, see:
 * <a href="https://github.com/NONPLAYT/LinearRegionFileFormatTools">LinearRegionFileFormatTools</a>
 */
@SuppressWarnings({"unused", "FieldMayBeFinal"})
public class BufferedRegionFile implements IRegionFile {
    private static final double SWAP_FILE_AUTO_COMPACT_PERCENT = 3.0 / 5.0; // 60 %
    private static final long SWAP_FILE_AUTO_COMPACT_SIZE = 1024 * 1024; // 1 MiB

    private static final long SWAP_FILE_SUPER_BLOCK = 0x1145141919810L;
    private static final int SWAP_FILE_HASH_SEED = 0x0721;
    private static final byte SWAP_FILE_VERSION = 0x02; // Version 2

    private static final long MASTER_FILE_SUPER_BLOCK = -0x200812250269L;
    private static final byte MASTER_FILE_VERSION = 0x02; // Version 2

    private static final long LINEAR_FILE_SUPER_BLOCK = 0xc3ff13183cca9d9aL;

    private static final StandardOpenOption[] SWAP_FILE_CHANNEL_OPTIONS = new StandardOpenOption[]{
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.READ,
        StandardOpenOption.DELETE_ON_CLOSE
    };
    private static final StandardOpenOption[] TMP_FILE_CHANNEL_OPTIONS = new StandardOpenOption[]{
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE
    };

    private final Path masterFilePath;
    private final Path swapFilePath;

    private final ReadWriteLock regionObjectLock = new ReentrantReadWriteLock();
    private final XXHash32 xxHash32 = XXHashFactory.fastestInstance().hash32();
    private Sector[] sectors = new Sector[1024];
    private long currentAcquiredIndex = this.headerSize();
    private int xxHash32Seed = SWAP_FILE_HASH_SEED;
    private FileChannel swapFileChannel;

    private final byte compressionLevel;
    private final LinearMasterFileParser masterFileParser = new LinearMasterFileParser();
    private final CompressingOps compressingOps = new CompressingOps();

    // managed by VarHandles following
    private boolean closed = false;
    private boolean beingSynced = false;
    private boolean synced = false;
    private long lastWritten = System.nanoTime();

    private static final VarHandle CLOSED_HANDLE = ConcurrentUtil.getVarHandle(BufferedRegionFile.class, "closed", boolean.class);
    private static final VarHandle SYNCED_HANDLE = ConcurrentUtil.getVarHandle(BufferedRegionFile.class, "synced", boolean.class);
    private static final VarHandle BEING_SYNCED_HANDLE = ConcurrentUtil.getVarHandle(BufferedRegionFile.class, "beingSynced", boolean.class);
    private static final VarHandle LAST_WRITTEN_HANDLE = ConcurrentUtil.getVarHandle(BufferedRegionFile.class, "lastWritten", long.class);

    private final BufferedRegionFileFlusher flusher;

    public BufferedRegionFile(Path masterFilePath, int compressionLevel, @NotNull BufferedRegionFileFlusher flusher) throws IOException {
        this.masterFilePath = masterFilePath;
        this.swapFilePath = Path.of(this.masterFilePath.toString() + ".swp");

        Validate.inclusiveBetween(1, 22, compressionLevel);
        this.compressionLevel = (byte) compressionLevel;

        this.initSwapFile();
        this.loadSwapDataFromMasterFile();

        this.flusher = flusher;
        this.flusher.addFile(this);
    }

    public boolean markAsBeingSynced() {
        return BEING_SYNCED_HANDLE.compareAndSet(this, false, true);
    }

    public long getLastWritten() {
        return (long) LAST_WRITTEN_HANDLE.getVolatile(this);
    }

    public boolean shouldSync() {
        return !((boolean) SYNCED_HANDLE.getVolatile(this));
    }

    public boolean softReadLock() {
        // not done close logic yet
        return this.regionObjectLock.readLock().tryLock();
    }

    public void releaseReadLock() {
        this.regionObjectLock.readLock().unlock();
    }

    public boolean isClosedRaw() {
        return (boolean) CLOSED_HANDLE.getVolatile(this);
    }

    public boolean isClosed() {
        this.regionObjectLock.readLock().lock();
        try {
            return (boolean) CLOSED_HANDLE.getVolatile(this);
        } finally {
            this.regionObjectLock.readLock().unlock();
        }
    }

    public void syncIfNeeded() throws IOException {
        if (!this.regionObjectLock.readLock().tryLock()) {
            BEING_SYNCED_HANDLE.setVolatile(this, false); // mark as not being synced
            return;
        }

        try {
            // skip if closed already
            if (this.isClosedRaw()) {
                return;
            }

            this.syncToMasterFile();
        } finally {
            BEING_SYNCED_HANDLE.setVolatile(this, false); // mark as not being synced

            this.regionObjectLock.readLock().unlock();
        }
    }

    private void syncToMasterFile() throws IOException {
        // prevent multiple syncs in the same time
        if (!SYNCED_HANDLE.compareAndSet(this, false, true)) {
            return;
        }

        try {
            this.masterFileParser.writeMainFile(this.masterFilePath);
        } catch (Exception e) {
            // set back
            SYNCED_HANDLE.setVolatile(this, false);

            throw new IOException("Failed to sync to master file!", e);
        }
    }

    private void loadSwapDataFromMasterFile() throws IOException {
        this.masterFileParser.parseMainFile(this.masterFilePath);
    }

    private void initSwapFile() throws IOException {
        this.swapFileChannel = FileChannel.open(
            this.swapFilePath,
            SWAP_FILE_CHANNEL_OPTIONS
        );

        // fill default sectors
        for (int i = 0; i < 1024; i++) {
            this.sectors[i] = new Sector(i, this.headerSize(), 0);
        }

        // load sectors
        this.readSwapFileHeaders();
    }

    private void readSwapFileHeaders() throws IOException {
        if (this.swapFileChannel.size() < this.headerSize()) {
            return;
        }

        final ByteBuffer buffer = ByteBuffer.allocate(this.headerSize());
        this.swapFileChannel.read(buffer, 0);
        buffer.flip();

        if (buffer.getLong() != SWAP_FILE_SUPER_BLOCK || buffer.get() != SWAP_FILE_VERSION) {
            throw new IOException("Invalid file format or version mismatch");
        }

        this.xxHash32Seed = buffer.getInt(); // XXHash32 seed
        this.currentAcquiredIndex = buffer.getLong(); // Acquired index

        for (Sector sector : this.sectors) {
            sector.restoreFrom(buffer);
            if (sector.hasData()) {
                this.currentAcquiredIndex = Math.max(this.currentAcquiredIndex, sector.offset + sector.length);
            }
        }
    }

    private void recalculateAcquiredIndex() {
        long newValue = this.headerSize();

        for (Sector sector : this.sectors) {
            if (sector.hasData()) {
                newValue = Math.max(newValue, sector.offset + sector.length);
            }
        }

        this.currentAcquiredIndex = newValue;
    }

    private void writeSwapFileHeaders(boolean forceFile, boolean forceMeta) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(this.headerSize());

        buffer.putLong(SWAP_FILE_SUPER_BLOCK); // Magic
        buffer.put(SWAP_FILE_VERSION); // Version
        buffer.putInt(this.xxHash32Seed); // XXHash32 seed
        buffer.putLong(this.currentAcquiredIndex); // Acquired index

        for (Sector sector : this.sectors) {
            buffer.put(sector.getEncoded());
        }

        buffer.flip();

        long offset = 0;
        while (buffer.hasRemaining()) {
            offset += this.swapFileChannel.write(buffer, offset);
        }

        if (forceFile) {
            this.swapFileChannel.force(forceMeta);
        }
    }

    private int sectorSize() {
        return this.sectors.length * Sector.sizeOfSingle();
    }

    private int headerSize() {
        int result = 0;

        result += Long.BYTES; // Magic
        result += Byte.BYTES; // Version
        result += Integer.BYTES; // XXHash32 seed
        result += Long.BYTES; // Acquired index
        result += this.sectorSize(); // Sectors

        return result;
    }

    private void flushInternal() throws IOException {
        this.regionObjectLock.writeLock().lock();
        try {
            if (this.isClosedRaw()) {
                return;
            }

            this.writeSwapFileHeaders(true, false);

            long spareSize = this.swapFileChannel.size();

            spareSize -= this.headerSize();
            for (Sector sector : this.sectors) {
                if (!sector.hasData()) {
                    continue;
                }

                spareSize -= sector.length;
            }

            long sectorSize = 0;
            for (Sector sector : this.sectors) {
                if (!sector.hasData()) {
                    continue;
                }

                sectorSize += sector.length;
            }

            boolean compacted = false;
            if (spareSize > SWAP_FILE_AUTO_COMPACT_SIZE && (double) spareSize > ((double) sectorSize) * SWAP_FILE_AUTO_COMPACT_PERCENT) {
                compacted = true;
                this.compactSwapFile();
            }

            if (!Files.exists(this.masterFilePath) && !compacted) {
                this.syncToMasterFile();
            }
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }
    }

    private void closeInternal() throws IOException {
        this.regionObjectLock.writeLock().lock();
        try {
            this.markClosed();

            try {
                this.writeSwapFileHeaders(true, true);
                this.syncToMasterFile();
            } finally {
                this.swapFileChannel.close();
            }
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }
    }

    private void markClosed() throws IOException {
        if (!CLOSED_HANDLE.compareAndSet(this, false, true)) {
            throw new IOException("Already closed!");
        }

        this.flusher.removeFile(this);
    }

    private void compactSwapFile() throws IOException {
        this.writeSwapFileHeaders(true, true); // save headers for compact

        final Sector[] newSectorsToBeReplaced = new Sector[this.sectors.length];

        for (int i = 0; i < this.sectors.length; i++) {
            final Sector old = this.sectors[i];

            if (old.hasData()) {
                newSectorsToBeReplaced[i] = old;
                continue;
            }

            // note:
            // we reset length to 0 and this would make length <= newLength(which is >= 0) is always true.
            // so that the following write operation wouldn't override the data of other sectors
            // see the write method in Sector class
            newSectorsToBeReplaced[i] = new Sector(i, 0, 0);
        }

        long newAcquiredIndex;

        final Path targetTemp = new File(this.swapFilePath.toString() + ".tmp").toPath();
        try (FileChannel tempChannel = FileChannel.open(
            targetTemp,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ
        )) {
            long offsetPointer = this.headerSize();
            tempChannel.position(offsetPointer);

            for (Sector sector : newSectorsToBeReplaced) {
                if (!sector.hasData()) {
                    continue;
                }

                sector.transferTo(this.swapFileChannel, tempChannel);

                final Sector newRecalculated = new Sector(sector.index, offsetPointer, sector.length);
                newRecalculated.hasData = true;

                offsetPointer += sector.length;
                newSectorsToBeReplaced[sector.index] = newRecalculated; // update sector infos
            }

            tempChannel.force(true);

            newAcquiredIndex = offsetPointer;
        } catch (Exception ex) {
            this.recalculateAcquiredIndex();
            Files.deleteIfExists(targetTemp);
            this.markClosed(); // prevent new writing & sync operations
            throw new IOException("Failed to compact swap file!", ex);
        }

        this.swapFileChannel.close();

        final Path target = new File(this.swapFilePath + ".tmp").toPath();
        try {
            Files.move(
                target,
                this.swapFilePath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (Exception e) {
            try {
                Files.move(
                    target,
                    this.swapFilePath,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (Exception ex) {
                e.addSuppressed(ex);

                Files.deleteIfExists(target);
                this.recalculateAcquiredIndex();
                this.reopenSwapFileChannel();
                this.markClosed(); // prevent new writing & sync opeartions
                throw new IOException("Failed to replace original swap file!", e);
            }
        }

        this.reopenSwapFileChannel();

        this.sectors = newSectorsToBeReplaced;
        this.currentAcquiredIndex = newAcquiredIndex;

        this.writeSwapFileHeaders(true, true);
    }

    private void reopenSwapFileChannel() throws IOException {
        if (this.swapFileChannel.isOpen()) {
            this.swapFileChannel.close();
        }

        this.swapFileChannel = FileChannel.open(
            this.swapFilePath,
            SWAP_FILE_CHANNEL_OPTIONS
        );
    }

    private void writeChunkDataRaw(int chunkOrdinal, ByteBuffer chunkData, boolean skipSync) throws IOException {
        final ByteBuffer committed = this.compressingOps.commitSectionData(chunkData); // run compression out of lock

        this.regionObjectLock.writeLock().lock();
        try {
            final Sector sector = this.sectors[chunkOrdinal];

            sector.store(committed, this.swapFileChannel);
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        if (skipSync) {
            return;
        }

        this.markAsToSync();
    }

    private @Nullable ByteBuffer readChunkDataRaw(int chunkOrdinal) throws IOException {
        final ByteBuffer raw;

        this.regionObjectLock.readLock().lock();
        try {
            final Sector sector = this.sectors[chunkOrdinal];

            if (!sector.hasData()) {
                return null;
            }

            raw = sector.read(this.swapFileChannel);
        } finally {
            this.regionObjectLock.readLock().unlock();
        }

        return this.compressingOps.fromCommitedSection(raw);
    }

    private void clearChunkData(int chunkOrdinal) throws IOException {
        this.regionObjectLock.writeLock().lock();
        try {
            final Sector sector = this.sectors[chunkOrdinal];

            sector.clear();

            this.writeSwapFileHeaders(true, false);
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        this.markAsToSync();
    }

    private void markAsToSync() {
        SYNCED_HANDLE.setVolatile(this, false);
        LAST_WRITTEN_HANDLE.setVolatile(this, System.nanoTime());
    }

    private static int getChunkIndex(int x, int z) {
        return (x & 31) + ((z & 31) << 5);
    }

    private boolean hasData(int chunkOrdinal) {
        this.regionObjectLock.readLock().lock();
        try {
            return this.sectors[chunkOrdinal].hasData();
        } finally {
            this.regionObjectLock.readLock().unlock();
        }
    }

    private void writeChunk(int x, int z, @NotNull ByteBuffer data) throws IOException {
        final int chunkIndex = getChunkIndex(x, z);

        final int oldPositionOfData = data.position();
        final int xxHash32OfData = this.xxHash32.hash(data, this.xxHash32Seed);
        data.position(oldPositionOfData);

        // uncompressed length(int) + timestamp(long) + xxhash32(int)
        final ByteBuffer chunkSectionBuilder = ByteBuffer.allocate(data.remaining() + 4 + 8 + 4);

        chunkSectionBuilder.putInt(data.remaining()); // Length(int)
        chunkSectionBuilder.putLong(System.currentTimeMillis()); // Timestamp(long)
        chunkSectionBuilder.putInt(xxHash32OfData); // xxHash32 of the original data(int)
        chunkSectionBuilder.put(data); // Data(bytes)
        chunkSectionBuilder.flip();

        this.writeChunkDataRaw(chunkIndex, chunkSectionBuilder, false);
    }

    private @Nullable ByteBuffer readChunk(int x, int z) throws IOException {
        final ByteBuffer data = this.readChunkDataRaw(getChunkIndex(x, z));

        if (data == null) {
            return null;
        }

        final int length = data.getInt(); // compressed length(int)
        final long timestamp = data.getLong(); // TODO use this timestamp(long) for something?
        final int dataXXHash32 = data.getInt(); // XXHash32 for validation(int)

        final IOException xxHash32CheckFailedEx = this.checkXXHash32(dataXXHash32, data);
        if (xxHash32CheckFailedEx != null) {
            throw xxHash32CheckFailedEx; // prevent from loading
        }

        return data;
    }

    private @Nullable IOException checkXXHash32(long originalXXHash32, @NotNull ByteBuffer input) {
        final int oldPositionOfInput = input.position();
        final int currentXXHash32 = this.xxHash32.hash(input, this.xxHash32Seed);
        input.position(oldPositionOfInput);

        if (originalXXHash32 != currentXXHash32) {
            return new IOException("XXHash32 check failed ! Expected: " + originalXXHash32 + ",but got: " + currentXXHash32);
        }

        return null;
    }

    @Override
    public Path getPath() {
        return this.masterFilePath;
    }

    @Override
    public DataInputStream getChunkDataInputStream(@NotNull ChunkPos pos) throws IOException {
        final ByteBuffer data = this.readChunk(pos.x, pos.z);

        if (data == null) {
            return null;
        }

        return new DataInputStream(new ByteBufferInputStream(data));
    }

    @Override
    public boolean doesChunkExist(@NotNull ChunkPos pos) {
        return this.hasData(getChunkIndex(pos.x, pos.z));
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) {
        return new DataOutputStream(new ChunkBufferHelper(pos));
    }

    @Override
    public void clear(@NotNull ChunkPos pos) throws IOException {
        this.clearChunkData(getChunkIndex(pos.x, pos.z));
    }

    @Override
    public boolean hasChunk(@NotNull ChunkPos pos) {
        return this.hasData(getChunkIndex(pos.x, pos.z));
    }

    @Override
    public void write(@NotNull ChunkPos pos, ByteBuffer buf) throws IOException {
        this.writeChunk(pos.x, pos.z, buf);
    }

    // MCC end

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(CompoundTag data, ChunkPos pos) {
        final DataOutputStream out = this.getChunkDataOutputStream(pos);

        return new MoonriseRegionFileIO.RegionDataController.WriteData(
            data, MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            out, regionFile -> out.close()
        );
    }

    @Override
    public void flush() throws IOException {
        this.flushInternal();
    }

    @Override
    public void close() throws IOException {
        this.closeInternal();
    }

    public static class ByteBufferInputStream extends InputStream {
        protected final ByteBuffer internal;

        public ByteBufferInputStream(ByteBuffer buf) {
            this.internal = buf;
        }

        @Override
        public int available() {
            return this.internal.remaining();
        }

        @Override
        public int read() throws IOException {
            return this.internal.hasRemaining() ? (this.internal.get() & 0xFF) : -1;
        }

        @Override
        public int read(byte @NotNull [] bytes, int off, int len) throws IOException {
            if (!this.internal.hasRemaining()) return -1;
            len = Math.min(len, this.internal.remaining());
            this.internal.get(bytes, off, len);
            return len;
        }
    }

    private static class CompressingOps {
        private final LZ4Compressor lz4Compressor = LZ4Factory.fastestInstance().fastCompressor();
        private final LZ4FastDecompressor lz4Decompressor = LZ4Factory.fastestInstance().fastDecompressor();

        public @NotNull ByteBuffer commitSectionData(@NotNull ByteBuffer in) {
            final int bufferLenToAllocate = this.lz4Compressor.maxCompressedLength(in.remaining());
            final ByteBuffer result = ByteBuffer.allocate(bufferLenToAllocate + 4);

            result.putInt(in.remaining());
            this.lz4Compressor.compress(in, result);

            return result.flip();
        }

        public @NotNull ByteBuffer fromCommitedSection(@NotNull ByteBuffer flippedIn) {
            final int originalLen = flippedIn.getInt();
            final byte[] raw = new byte[flippedIn.remaining()];
            flippedIn.get(raw);

            final byte[] decompressed = new byte[originalLen];
            this.lz4Decompressor.decompress(raw, decompressed);

            return ByteBuffer.wrap(decompressed);
        }
    }

    public class Sector {
        private final int index;
        private long offset;
        private long length;
        private boolean hasData = false;

        private Sector(int index, long offset, long length) {
            this.index = index;
            this.offset = offset;
            this.length = length;
        }

        public void transferTo(@NotNull FileChannel source, @NotNull FileChannel target) throws IOException {
            long transferred = 0;
            while (transferred < this.length) {
                transferred += source.transferTo(
                    this.offset + transferred,
                    this.length - transferred,
                    target);
            }
        }

        public @NotNull ByteBuffer read(@NotNull FileChannel channel) throws IOException {
            final ByteBuffer result = ByteBuffer.allocate((int) this.length);

            int totalRead = 0;
            while (totalRead < this.length) {
                int read = channel.read(result, this.offset + totalRead);
                if (read == -1) {
                    throw new IOException("Unexpected EOF while reading sector " + this.index +
                        ", expected " + this.length + " bytes, got " + totalRead);
                }
                totalRead += read;
            }

            result.flip();
            return result;
        }

        public void store(@NotNull ByteBuffer newData, @NotNull FileChannel channel) throws IOException {
            final long oldLength = this.length;
            final long newDataLength = newData.remaining();

            this.hasData = true;
            this.length = newDataLength;

            if (newDataLength <= oldLength) {
                long localOffset = this.offset;
                while (newData.hasRemaining()) {
                    localOffset += channel.write(newData, localOffset);
                }

                return;
            }

            this.offset = BufferedRegionFile.this.currentAcquiredIndex;

            BufferedRegionFile.this.currentAcquiredIndex += this.length;

            long localOffset = this.offset;
            while (newData.hasRemaining()) {
                localOffset += channel.write(newData, localOffset);
            }
        }

        private @NotNull ByteBuffer getEncoded() {
            final ByteBuffer buffer = ByteBuffer.allocate(sizeOfSingle());

            buffer.putLong(this.offset);
            buffer.putLong(this.length);
            buffer.put((byte) (this.hasData ? 1 : 0));
            buffer.flip();

            return buffer;
        }

        public void restoreFrom(@NotNull ByteBuffer buffer) {
            this.offset = buffer.getLong();
            this.length = buffer.getLong();
            this.hasData = buffer.get() == 1;

            if (this.length < 0 || this.offset < 0) {
                throw new IllegalStateException("Invalid sector data: " + this);
            }
        }

        public void clear() {
            this.hasData = false;
        }

        public boolean hasData() {
            return this.hasData;
        }

        static int sizeOfSingle() {
            return Long.BYTES * 2 + 1;
        }
    }

    private class ChunkBufferHelper extends ByteArrayOutputStream {
        private final ChunkPos pos;

        private ChunkBufferHelper(ChunkPos pos) {
            this.pos = pos;
        }

        @Override
        public void close() throws IOException {
            ByteBuffer bytebuffer = ByteBuffer.wrap(this.buf, 0, this.count);

            BufferedRegionFile.this.writeChunk(this.pos.x, this.pos.z, bytebuffer);
            BufferedRegionFile.this.flushInternal();
        }
    }

    private class LinearMasterFileParser {
        private void parseBufferedLinear(@NotNull DataInputStream ioStream, Path file) throws IOException {
            final byte version = ioStream.readByte();
            if (version != MASTER_FILE_VERSION)
                throw new RuntimeException("Invalid version: " + version + " in " + file);

            // Skip newestTimestamp (Long) + Compression level (Byte): Unused.
            ioStream.skipBytes(9);

            try (final ZstdInputStream decompressStream = new ZstdInputStream(ioStream)) {
                // only used as a helper stream
                // the parent stream will be closed in the try-catch block upper
                final DataInputStream decompressedStreamHelper = new DataInputStream(decompressStream);

                for (int index = 0; index < 1024; index++) {
                    int size = decompressedStreamHelper.readInt(); // len

                    if (size > 0) {
                        byte[] sectorData = new byte[size];
                        decompressedStreamHelper.readFully(sectorData, 0, size); // data

                        final ByteBuffer sectorDataNioBuffer = ByteBuffer.wrap(sectorData);

                        BufferedRegionFile.this.writeChunkDataRaw(index, sectorDataNioBuffer, true);
                    }
                }
            }
        }

        @Contract(value = "_ -> new", pure = true)
        public static int @NotNull [] coordinatesFromOrdinal(int chunkIndex) {
            int x = chunkIndex & 31;
            int z = (chunkIndex >> 5) & 31;
            return new int[]{x, z};
        }

        private void parseLinear(@NotNull DataInputStream ioStream, Path file) throws IOException {
            final byte version = ioStream.readByte();

            if (version != 1 && version != 2) {
                throw new IOException("Unsupported version for linear format : " + version);
            }

            // Skip newestTimestamp (Long) + Compression level (Byte) + Chunk count (Short): Unused.
            ioStream.skipBytes(11);
            // Skip chunk data len(Int)(Unused).
            ioStream.skipBytes(4);
            // Skip data hash (Long): Unused.
            ioStream.skipBytes(8);

            try (final ZstdInputStream decompressedStream = new ZstdInputStream(ioStream)) {
                // only used as a helper stream
                // the parent stream will be closed in the try-catch block upper
                final DataInputStream bufferHelper = new DataInputStream(decompressedStream);

                final int[] chunkStarts = new int[1024];
                for (int i = 0; i < 1024; i++) {
                    chunkStarts[i] = bufferHelper.readInt();
                    bufferHelper.skipBytes(4); // Skip timestamps (Int): Unused.
                }

                for (int i = 0; i < 1024; i++) {
                    if (chunkStarts[i] > 0) {
                        int size = chunkStarts[i];
                        byte[] chunkData = new byte[size];
                        bufferHelper.readFully(chunkData);

                        final ByteBuffer chunkDataNioBuffer = ByteBuffer.wrap(chunkData);

                        final int[] posByAxis = coordinatesFromOrdinal(i);

                        final int x = posByAxis[0];
                        final int z = posByAxis[1];

                        BufferedRegionFile.this.writeChunk(x, z, chunkDataNioBuffer);
                    }
                }
            }
        }

        public void parseMainFile(@NotNull Path mainFilePath) throws IOException {
            final File file = mainFilePath.toFile();

            if (!file.exists() || !file.canRead()) {
                return;
            }

            // those streams will be closed in the parse logic, or we will close it manually
            final FileInputStream fileStream = new FileInputStream(file);
            final DataInputStream rawDataStream = new DataInputStream(fileStream);

            final long superBlock;
            try {
                superBlock = rawDataStream.readLong();

                if (superBlock == MASTER_FILE_SUPER_BLOCK) {
                    this.parseBufferedLinear(rawDataStream, mainFilePath);
                    return;
                }

                if (superBlock == LINEAR_FILE_SUPER_BLOCK) {
                    this.parseLinear(rawDataStream, mainFilePath);
                    return;
                }

            } catch (Exception ex) {
                // error caught during other reading logics, close directly
                try {
                    rawDataStream.close();
                } catch (IOException ex2) {
                    ex.addSuppressed(ex2);
                }

                throw new IOException("Failed to parse master file: " + mainFilePath, ex);
            }

            // anyone non-matched, close stream and throw the error
            rawDataStream.close();

            throw new IOException("Unknown or unsupported super block : " + superBlock);
        }

        public void writeMainFile(@NotNull Path mainFile) throws IOException {
            final Path tmpFilePath = Path.of(mainFile + ".tmp");

            long timestamp = System.currentTimeMillis();

            File tempFile = tmpFilePath.toFile();

            try (final OutputStream fileStream = Files.newOutputStream(tmpFilePath, TMP_FILE_CHANNEL_OPTIONS);
                 final ZstdOutputStream zstdStream = new ZstdOutputStream(fileStream, BufferedRegionFile.this.compressionLevel)
            ) {

                // only used as a helper stream
                // the parent stream will be closed in the try-catch block upper
                final DataOutputStream fileDataStreamHelper = new DataOutputStream(fileStream);

                fileDataStreamHelper.writeLong(MASTER_FILE_SUPER_BLOCK); // super block
                fileDataStreamHelper.writeByte(MASTER_FILE_VERSION); // version
                fileDataStreamHelper.writeLong(timestamp); // timestamp
                fileDataStreamHelper.write(BufferedRegionFile.this.compressionLevel); // compression level
                fileDataStreamHelper.flush();

                // only used as a helper stream
                // the parent stream will be closed in the try-catch block upper
                final DataOutputStream zstdDataStreamHelper = new DataOutputStream(zstdStream);

                for (int i = 0; i < 1024; i++) {
                    // read from swap file
                    final ByteBuffer chunkData = BufferedRegionFile.this.readChunkDataRaw(i);

                    // not found
                    if (chunkData == null) {
                        zstdDataStreamHelper.writeInt(0);
                        continue;
                    }

                    final byte[] buffer = new byte[chunkData.remaining()];
                    chunkData.get(buffer);

                    // store
                    zstdDataStreamHelper.writeInt(buffer.length); // len
                    zstdDataStreamHelper.write(buffer); // data
                }

                zstdDataStreamHelper.flush();
            }

            try {
                Files.move(tempFile.toPath(), masterFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                // retry with non-atomic move
                try {
                    Files.move(tempFile.toPath(), masterFilePath, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    // now we are totally failed

                    // fast-fail
                    Files.deleteIfExists(masterFilePath);
                    throw new IOException("Failed to replace original master file!", e);
                }
            }
        }
    }
}
