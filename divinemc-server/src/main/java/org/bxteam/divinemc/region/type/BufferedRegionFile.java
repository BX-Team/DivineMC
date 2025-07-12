package org.bxteam.divinemc.region.type;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.bxteam.divinemc.region.BufferReleaser;
import org.bxteam.divinemc.region.IRegionFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferedRegionFile implements IRegionFile {
    private static final double AUTO_COMPACT_PERCENT = 3.0 / 5.0; // 60%
    private static final long AUTO_COMPACT_SIZE = 1024 * 1024; // 1 MiB
    private static final long SUPER_BLOCK = 0x1145141919810L;
    private static final int HASH_SEED = 0x0721;
    private static final byte VERSION = 0x01; // Version 1

    private final Path filePath;
    private final ReadWriteLock fileAccessLock = new ReentrantReadWriteLock();
    private final XXHash32 xxHash32 = XXHashFactory.fastestInstance().hash32();
    private final Sector[] sectors = new Sector[1024];
    private final AtomicInteger recalculateCount = new AtomicInteger(0);
    private long currentAcquiredIndex = this.headerSize();
    private byte compressionLevel = 6;
    private int xxHash32Seed = HASH_SEED;
    private FileChannel channel;

    public BufferedRegionFile(Path filePath, int compressionLevel) throws IOException {
        this(filePath);

        this.compressionLevel = (byte) compressionLevel;
    }

    public BufferedRegionFile(Path filePath) throws IOException {
        this.channel = FileChannel.open(
            filePath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ
        );
        this.filePath = filePath;

        for (int i = 0; i < 1024; i++) {
            this.sectors[i] = new Sector(i, this.headerSize(), 0);
        }

        this.readHeaders();
    }

    private void readHeaders() throws IOException {
        if (this.channel.size() < this.headerSize()) {
            return;
        }

        final ByteBuffer buffer = ByteBuffer.allocateDirect(this.headerSize());
        this.channel.read(buffer, 0);
        buffer.flip();

        if (buffer.getLong() != SUPER_BLOCK || buffer.get() != VERSION) {
            throw new IOException("Invalid file format or version mismatch");
        }

        this.compressionLevel = buffer.get(); // Compression level
        this.xxHash32Seed = buffer.getInt(); // XXHash32 seed
        this.currentAcquiredIndex = buffer.getLong(); // Acquired index

        for (Sector sector : this.sectors) {
            sector.restoreFrom(buffer);
            if (sector.hasData()) {
                this.currentAcquiredIndex = Math.max(this.currentAcquiredIndex, sector.offset + sector.length);
            }
        }

        BufferReleaser.clean(buffer);
    }

    private void writeHeaders() throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(this.headerSize());

        buffer.putLong(SUPER_BLOCK); // Magic
        buffer.put(VERSION); // Version
        buffer.put(this.compressionLevel); // Compression level
        buffer.putInt(this.xxHash32Seed); // XXHash32 seed
        buffer.putLong(this.currentAcquiredIndex); // Acquired index

        for (Sector sector : this.sectors) {
            buffer.put(sector.getEncoded());
        }

        buffer.flip();

        long offset = 0;
        while (buffer.hasRemaining()) {
            offset += this.channel.write(buffer, offset);
        }

        BufferReleaser.clean(buffer);
    }

    private int sectorSize() {
        return this.sectors.length * Sector.sizeOfSingle();
    }

    private int headerSize() {
        int result = 0;

        result += Long.BYTES; // Magic
        result += Byte.BYTES; // Version
        result += Byte.BYTES; // Compression level
        result += Integer.BYTES; // XXHash32 seed
        result += Long.BYTES; // Acquired index
        result += this.sectorSize(); // Sectors

        return result;
    }

    private void flushInternal() throws IOException {
        this.writeHeaders();

        long spareSize = this.channel.size();

        spareSize -= this.headerSize();
        for (Sector sector : this.sectors) {
            spareSize -= sector.length;
        }

        long sectorSize = 0;
        for (Sector sector : this.sectors) {
            sectorSize += sector.length;
        }

        if (spareSize > AUTO_COMPACT_SIZE && (double)spareSize > ((double)sectorSize) * AUTO_COMPACT_PERCENT) {
            this.compact();
        }
    }

    private void closeInternal() throws IOException {
        this.writeHeaders();
        this.channel.force(true);
        this.compact();
        this.channel.close();
    }

    private void compact() throws IOException {
        this.writeHeaders();
        this.channel.force(true);
        try (FileChannel tempChannel = FileChannel.open(
            new File(this.filePath.toString() + ".tmp").toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ
        )){
            final ByteBuffer headerBuffer = ByteBuffer.allocateDirect(this.headerSize());
            this.channel.read(headerBuffer, 0);
            headerBuffer.flip();

            long offsetHeader = 0;
            while (headerBuffer.hasRemaining()) {
                offsetHeader += tempChannel.write(headerBuffer, offsetHeader);
            }
            BufferReleaser.clean(headerBuffer);

            int offsetPointer = this.headerSize();
            for (Sector sector : this.sectors) {
                if (!sector.hasData()) {
                    continue;
                }

                final ByteBuffer sectorData = sector.read(this.channel);
                final int length = sectorData.remaining();

                final Sector newRecalculated = new Sector(sector.index, offsetPointer, length);
                offsetPointer += length;
                this.sectors[sector.index] = newRecalculated;

                newRecalculated.hasData = true;

                long offset = newRecalculated.offset;
                while (sectorData.hasRemaining()) {
                    offset += tempChannel.write(sectorData, offset);
                }

                BufferReleaser.clean(sectorData);
            }

            tempChannel.force(true);
            this.currentAcquiredIndex = tempChannel.size();
        }

        Files.move(
            new File(this.filePath.toString() + ".tmp").toPath(),
            this.filePath,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );

        this.reopenChannel();
        this.writeHeaders();
    }

    private void reopenChannel() throws IOException {
        if (this.channel.isOpen()) {
            this.channel.close();
        }

        this.channel = FileChannel.open(
            filePath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ
        );
    }

    private void writeChunkDataRaw(int chunkOrdinal, ByteBuffer chunkData) throws IOException {
        final Sector sector = this.sectors[chunkOrdinal];

        sector.store(chunkData, this.channel);
    }

    private @Nullable ByteBuffer readChunkDataRaw(int chunkOrdinal) throws IOException {
        final Sector sector = this.sectors[chunkOrdinal];

        if (!sector.hasData()) {
            return null;
        }

        return sector.read(this.channel);
    }

    private void clearChunkData(int chunkOrdinal) throws IOException {
        final Sector sector = this.sectors[chunkOrdinal];

        sector.clear();

        this.writeHeaders();
    }

    private static int getChunkIndex(int x, int z) {
        return (x & 31) + ((z & 31) << 5);
    }

    private boolean hasData(int chunkOriginal) {
        return this.sectors[chunkOriginal].hasData();
    }

    private void writeChunk(int x, int z, @NotNull ByteBuffer data) throws IOException {
        final int chunkIndex = getChunkIndex(x, z);

        final int oldPositionOfData = data.position();
        final int xxHash32OfData = this.xxHash32.hash(data, this.xxHash32Seed);
        data.position(oldPositionOfData);

        final ByteBuffer compressedData = this.compress(this.ensureDirectBuffer(data));
        final ByteBuffer chunkSectionBuilder = ByteBuffer.allocateDirect(compressedData.remaining() + 4 + 8 + 4);

        chunkSectionBuilder.putInt(data.remaining()); // Uncompressed length
        chunkSectionBuilder.putLong(System.nanoTime()); // Timestamp
        chunkSectionBuilder.putInt(xxHash32OfData); // xxHash32 of the original data
        chunkSectionBuilder.put(compressedData); // Compressed data
        chunkSectionBuilder.flip();

        this.writeChunkDataRaw(chunkIndex, chunkSectionBuilder);
        BufferReleaser.clean(chunkSectionBuilder);
    }

    private @Nullable ByteBuffer readChunk(int x, int z) throws IOException {
        final ByteBuffer compressed = this.readChunkDataRaw(getChunkIndex(x, z));

        if (compressed == null) {
            return null;
        }

        final int uncompressedLength = compressed.getInt(); // compressed length
        final long timestamp = compressed.getLong(); // TODO use this timestamp for something?
        final int dataXXHash32 = compressed.getInt(); // XXHash32 for validation

        final ByteBuffer decompressed = this.decompress(this.ensureDirectBuffer(compressed), uncompressedLength);

        BufferReleaser.clean(compressed);

        final IOException xxHash32CheckFailedEx = this.checkXXHash32(dataXXHash32, decompressed);
        if (xxHash32CheckFailedEx != null) {
            throw xxHash32CheckFailedEx; // prevent from loading
        }

        return decompressed;
    }

    private @NotNull ByteBuffer ensureDirectBuffer(@NotNull ByteBuffer buffer) {
        if (buffer.isDirect()) {
            return buffer;
        }

        ByteBuffer direct = ByteBuffer.allocateDirect(buffer.remaining());
        int originalPosition = buffer.position();
        direct.put(buffer);
        direct.flip();
        buffer.position(originalPosition);

        return direct;
    }

    private @NotNull ByteBuffer compress(@NotNull ByteBuffer input) throws IOException {
        final int originalPosition = input.position();
        final int originalLimit = input.limit();

        try {
            byte[] inputArray;
            int inputLength = input.remaining();
            if (input.hasArray()) {
                inputArray = input.array();
                int arrayOffset = input.arrayOffset() + input.position();
                if (arrayOffset != 0 || inputLength != inputArray.length) {
                    byte[] temp = new byte[inputLength];
                    System.arraycopy(inputArray, arrayOffset, temp, 0, inputLength);
                    inputArray = temp;
                }
            } else {
                inputArray = new byte[inputLength];
                input.get(inputArray);
                input.position(originalPosition);
            }

            byte[] compressed = com.github.luben.zstd.Zstd.compress(inputArray, this.compressionLevel);

            ByteBuffer result = ByteBuffer.allocateDirect(compressed.length);
            result.put(compressed);
            result.flip();

            return result;

        } catch (Exception e) {
            throw new IOException("Compression failed for input size: " + input.remaining(), e);
        } finally {
            input.position(originalPosition);
            input.limit(originalLimit);
        }
    }

    private @NotNull ByteBuffer decompress(@NotNull ByteBuffer input, int originalSize) throws IOException {
        final int originalPosition = input.position();
        final int originalLimit = input.limit();

        try {
            byte[] inputArray;
            int inputLength = input.remaining();

            if (input.hasArray()) {
                inputArray = input.array();
                int arrayOffset = input.arrayOffset() + input.position();
                if (arrayOffset != 0 || inputLength != inputArray.length) {
                    byte[] temp = new byte[inputLength];
                    System.arraycopy(inputArray, arrayOffset, temp, 0, inputLength);
                    inputArray = temp;
                }
            } else {
                inputArray = new byte[inputLength];
                input.get(inputArray);
                input.position(originalPosition);
            }

            byte[] decompressed = com.github.luben.zstd.Zstd.decompress(inputArray, originalSize);

            if (decompressed.length != originalSize) {
                throw new IOException("Decompression size mismatch: expected " +
                    originalSize + ", got " + decompressed.length);
            }

            ByteBuffer result = ByteBuffer.allocateDirect(originalSize);
            result.put(decompressed);
            result.flip();

            return result;

        } catch (Exception e) {
            throw new IOException("Decompression failed", e);
        } finally {
            input.position(originalPosition);
            input.limit(originalLimit);
        }
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
        return this.filePath;
    }

    @Override
    public DataInputStream getChunkDataInputStream(@NotNull ChunkPos pos) throws IOException {
        this.fileAccessLock.readLock().lock();
        try {
            final ByteBuffer data = this.readChunk(pos.x, pos.z);

            if (data == null) {
                return null;
            }

            final byte[] dataBytes = new byte[data.remaining()];
            data.get(dataBytes);

            BufferReleaser.clean(data);

            return new DataInputStream(new ByteArrayInputStream(dataBytes));
        }finally {
            this.fileAccessLock.readLock().unlock();
        }
    }

    @Override
    public boolean doesChunkExist(@NotNull ChunkPos pos) {
        this.fileAccessLock.readLock().lock();
        try {
            return this.hasData(getChunkIndex(pos.x, pos.z));
        }finally {
            this.fileAccessLock.readLock().unlock();
        }
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) {
        return new DataOutputStream(new ChunkBufferHelper(pos));
    }

    @Override
    public void clear(@NotNull ChunkPos pos) throws IOException {
        this.fileAccessLock.writeLock().lock();
        try {
            this.clearChunkData(getChunkIndex(pos.x, pos.z));
        }finally {
            this.fileAccessLock.writeLock().unlock();
        }
    }

    @Override
    public boolean hasChunk(@NotNull ChunkPos pos) {
        this.fileAccessLock.readLock().lock();
        try {
            return this.hasData(getChunkIndex(pos.x, pos.z));
        }finally {
            this.fileAccessLock.readLock().unlock();
        }
    }

    @Override
    public void write(@NotNull ChunkPos pos, ByteBuffer buf) throws IOException {
        this.fileAccessLock.writeLock().lock();
        try {
            this.writeChunk(pos.x, pos.z, buf);
        }finally {
            this.fileAccessLock.writeLock().unlock();
        }
    }

    @Override
    public CompoundTag getOversizedData(int x, int z) {
        return null;
    }

    @Override
    public boolean isOversized(int x, int z) {
        return false;
    }

    @Override
    public boolean recalculateHeader() {
        this.recalculateCount.incrementAndGet();
        return false;
    }

    @Override
    public void setOversized(int x, int z, boolean oversized) {

    }

    @Override
    public int getRecalculateCount() {
        return this.recalculateCount.get();
    }
    // MCC end

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(CompoundTag data, ChunkPos pos) {
        final DataOutputStream out = this.getChunkDataOutputStream(pos);

        return new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData(
            data, ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            out, regionFile -> out.close()
        );
    }

    @Override
    public void flush() throws IOException {
        this.fileAccessLock.writeLock().lock();
        try {
            this.flushInternal();
        }finally {
            this.fileAccessLock.writeLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        this.fileAccessLock.writeLock().lock();
        try {
            this.closeInternal();
        }finally {
            this.fileAccessLock.writeLock().unlock();
        }
    }

    private class Sector{
        private final int index;
        private long offset;
        private long length;
        private boolean hasData = false;

        private Sector(int index, long offset, long length) {
            this.index = index;
            this.offset = offset;
            this.length = length;
        }

        public @NotNull ByteBuffer read(@NotNull FileChannel channel) throws IOException {
            final ByteBuffer result = ByteBuffer.allocateDirect((int) this.length);

            channel.read(result, this.offset);
            result.flip();

            return result;
        }

        public void store(@NotNull ByteBuffer newData, @NotNull FileChannel channel) throws IOException {
            this.hasData = true;
            this.length = newData.remaining();
            this.offset = currentAcquiredIndex;

            BufferedRegionFile.this.currentAcquiredIndex += this.length;

            long offset = this.offset;
            while (newData.hasRemaining()) {
                offset = channel.write(newData, offset);
            }
        }

        private @NotNull ByteBuffer getEncoded() {
            final ByteBuffer buffer = ByteBuffer.allocateDirect(sizeOfSingle());

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
            //     offset  length  hasData
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
            BufferedRegionFile.this.fileAccessLock.writeLock().lock();
            try {
                ByteBuffer bytebuffer = ByteBuffer.wrap(this.buf, 0, this.count);

                BufferedRegionFile.this.writeChunk(this.pos.x, this.pos.z, bytebuffer);
            }finally {
                BufferedRegionFile.this.fileAccessLock.writeLock().unlock();
            }
        }
    }
}
