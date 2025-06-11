package org.bxteam.divinemc.region;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.mojang.logging.LogUtils;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.openhft.hashing.LongHashFunction;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.spark.ThreadDumperRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class LinearRegionFile implements IRegionFile {
    public static final int MAX_CHUNK_SIZE = 500 * 1024 * 1024;
    private static final Object SAVE_LOCK = new Object();
    private static final long SUPERBLOCK = 0xc3ff13183cca9d9aL;
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final byte V1_VERSION = 2;
    private static final byte V2_VERSION = 3;

    private byte[][] bucketBuffers;
    private final byte[][] chunkCompressedBuffers = new byte[1024][];
    private final int[] chunkUncompressedSizes = new int[1024];
    private final long[] chunkTimestamps = new long[1024];

    private final Object markedToSaveLock = new Object();
    private boolean markedToSave = false;

    private final LZ4Compressor compressor;
    private final LZ4FastDecompressor decompressor;

    private volatile boolean regionFileOpen = false;
    private volatile boolean close = false;

    private final Path regionFilePath;
    private final int gridSizeDefault = 8;
    private int gridSize = gridSizeDefault;
    private int bucketSize = 4;
    private final int compressionLevel;
    private final LinearImplementation linearImpl;
    private final Thread schedulingThread;

    private static int activeSaveThreads = 0;

    public LinearRegionFile(Path path, LinearImplementation linearImplementation, int compressionLevel) {
        this.regionFilePath = path;
        this.linearImpl = linearImplementation;
        this.compressionLevel = compressionLevel;
        this.compressor = LZ4Factory.fastestInstance().fastCompressor();
        this.decompressor = LZ4Factory.fastestInstance().fastDecompressor();

        Runnable flushCheck = () -> {
            while (!close) {
                synchronized (SAVE_LOCK) {
                    if (markedToSave && activeSaveThreads < DivineConfig.MiscCategory.linearFlushMaxThreads) {
                        activeSaveThreads++;
                        Runnable flushOperation = () -> {
                            try {
                                flush();
                            } catch (IOException ex) {
                                LOGGER.error("Region file {} flush failed", regionFilePath.toAbsolutePath(), ex);
                            } finally {
                                synchronized (SAVE_LOCK) {
                                    activeSaveThreads--;
                                }
                            }
                        };
                        Thread saveThread = DivineConfig.MiscCategory.linearUseVirtualThread
                            ? Thread.ofVirtual().name("Linear IO - " + this.hashCode()).unstarted(flushOperation)
                            : Thread.ofPlatform().name("Linear IO - " + this.hashCode()).unstarted(flushOperation);
                        saveThread.setPriority(Thread.NORM_PRIORITY - 3);
                        saveThread.start();
                        ThreadDumperRegistry.REGISTRY.add(saveThread.getName());
                    }
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(DivineConfig.MiscCategory.linearFlushDelay));
            }
        };
        this.schedulingThread = DivineConfig.MiscCategory.linearUseVirtualThread
            ? Thread.ofVirtual().unstarted(flushCheck)
            : Thread.ofPlatform().unstarted(flushCheck);
        this.schedulingThread.setName("Linear IO Schedule - " + this.hashCode());
        ThreadDumperRegistry.REGISTRY.add(this.schedulingThread.getName());
    }

    private synchronized void openRegionFile() {
        if (regionFileOpen) return;
        regionFileOpen = true;

        File file = regionFilePath.toFile();
        if (!file.canRead()) {
            schedulingThread.start();
            return;
        }

        try {
            byte[] fileContent = Files.readAllBytes(regionFilePath);
            ByteBuffer byteBuffer = ByteBuffer.wrap(fileContent);

            long superBlock = byteBuffer.getLong();
            if (superBlock != SUPERBLOCK) {
                throw new RuntimeException("Invalid superblock: " + superBlock + " file " + regionFilePath);
            }

            byte version = byteBuffer.get();
            if (version == V1_VERSION) {
                parseLinearV1(byteBuffer);
            } else if (version == V2_VERSION) {
                parseLinearV2(byteBuffer);
            } else {
                throw new RuntimeException("Invalid version: " + version + " file " + regionFilePath);
            }

            schedulingThread.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open region file " + regionFilePath, e);
        }
    }

    private void parseLinearV1(ByteBuffer buffer) throws IOException {
        final int HEADER_SIZE = 32;
        final int FOOTER_SIZE = 8;
        buffer.position(buffer.position() + 11);

        int dataCount = buffer.getInt();
        long fileLength = regionFilePath.toFile().length();
        if (fileLength != HEADER_SIZE + dataCount + FOOTER_SIZE) {
            throw new IOException("Invalid file length: " + regionFilePath + " " + fileLength + " expected " + (HEADER_SIZE + dataCount + FOOTER_SIZE));
        }

        buffer.position(buffer.position() + 8);

        byte[] rawCompressed = new byte[dataCount];
        buffer.get(rawCompressed);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(rawCompressed);
             ZstdInputStream zstdIn = new ZstdInputStream(bais)) {
            ByteBuffer decompressedBuffer = ByteBuffer.wrap(zstdIn.readAllBytes());
            int[] starts = new int[1024];
            for (int i = 0; i < 1024; i++) {
                starts[i] = decompressedBuffer.getInt();
                decompressedBuffer.getInt();
            }

            for (int i = 0; i < 1024; i++) {
                if (starts[i] > 0) {
                    int size = starts[i];
                    byte[] chunkData = new byte[size];
                    decompressedBuffer.get(chunkData);

                    int maxCompressedLength = compressor.maxCompressedLength(size);
                    byte[] compressed = new byte[maxCompressedLength];
                    int compressedLength = compressor.compress(chunkData, 0, size, compressed, 0, maxCompressedLength);
                    byte[] finalCompressed = new byte[compressedLength];
                    System.arraycopy(compressed, 0, finalCompressed, 0, compressedLength);

                    chunkCompressedBuffers[i] = finalCompressed;
                    chunkUncompressedSizes[i] = size;
                    chunkTimestamps[i] = currentTimestamp();
                }
            }
        }
    }

    private void parseLinearV2(ByteBuffer buffer) throws IOException {
        buffer.getLong();
        gridSize = buffer.get();
        if (!(gridSize == 1 || gridSize == 2 || gridSize == 4 || gridSize == 8 || gridSize == 16 || gridSize == 32)) {
            throw new RuntimeException("Invalid grid size: " + gridSize + " file " + regionFilePath);
        }
        bucketSize = 32 / gridSize;

        buffer.getInt();
        buffer.getInt();

        boolean[] chunkExistenceBitmap = deserializeExistenceBitmap(buffer);

        while (true) {
            byte featureNameLength = buffer.get();
            if (featureNameLength == 0) break;
            byte[] featureNameBytes = new byte[featureNameLength];
            buffer.get(featureNameBytes);
            String featureName = new String(featureNameBytes);
            int featureValue = buffer.getInt();
        }

        int bucketCount = gridSize * gridSize;
        int[] bucketSizes = new int[bucketCount];
        byte[] bucketCompressionLevels = new byte[bucketCount];
        long[] bucketHashes = new long[bucketCount];

        for (int i = 0; i < bucketCount; i++) {
            bucketSizes[i] = buffer.getInt();
            bucketCompressionLevels[i] = buffer.get();
            bucketHashes[i] = buffer.getLong();
        }

        bucketBuffers = new byte[bucketCount][];
        for (int i = 0; i < bucketCount; i++) {
            if (bucketSizes[i] > 0) {
                bucketBuffers[i] = new byte[bucketSizes[i]];
                buffer.get(bucketBuffers[i]);
                long rawHash = LongHashFunction.xx().hashBytes(bucketBuffers[i]);
                if (rawHash != bucketHashes[i]) {
                    throw new IOException("Region file hash incorrect " + regionFilePath);
                }
            }
        }

        long footerSuperBlock = buffer.getLong();
        if (footerSuperBlock != SUPERBLOCK) {
            throw new IOException("Footer superblock invalid " + regionFilePath);
        }
    }

    private synchronized void markToSave() {
        synchronized (markedToSaveLock) {
            markedToSave = true;
        }
    }

    private synchronized boolean isMarkedToSave() {
        synchronized (markedToSaveLock) {
            if (markedToSave) {
                markedToSave = false;
                return true;
            }
            return false;
        }
    }

    @Override
    public synchronized boolean doesChunkExist(ChunkPos pos) {
        openRegionFile();
        return hasChunk(pos);
    }

    @Override
    public synchronized boolean hasChunk(ChunkPos pos) {
        openRegionFile();
        openBucketForChunk(pos.x, pos.z);
        int index = getChunkIndex(pos.x, pos.z);
        return chunkUncompressedSizes[index] > 0;
    }

    @Override
    public synchronized void flush() throws IOException {
        if (!isMarkedToSave()) return;
        openRegionFile();
        if (linearImpl == LinearImplementation.V1) {
            flushLinearV1();
        } else if (linearImpl == LinearImplementation.V2) {
            flushLinearV2();
        }
    }

    private void flushLinearV1() throws IOException {
        long timestamp = currentTimestamp();
        short chunkCount = 0;
        File tempFile = new File(regionFilePath.toString() + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(tempFile);
             ByteArrayOutputStream zstdBAOS = new ByteArrayOutputStream();
             ZstdOutputStream zstdOut = new ZstdOutputStream(zstdBAOS, compressionLevel);
             DataOutputStream zstdDataOut = new DataOutputStream(zstdOut);
             DataOutputStream fileDataOut = new DataOutputStream(fos)) {

            fileDataOut.writeLong(SUPERBLOCK);
            fileDataOut.writeByte(V1_VERSION);
            fileDataOut.writeLong(timestamp);
            fileDataOut.writeByte(compressionLevel);

            ArrayList<byte[]> decompressedChunks = new ArrayList<>(1024);
            for (int i = 0; i < 1024; i++) {
                if (chunkUncompressedSizes[i] != 0) {
                    chunkCount++;
                    byte[] decompressed = new byte[chunkUncompressedSizes[i]];
                    decompressor.decompress(chunkCompressedBuffers[i], 0, decompressed, 0, chunkUncompressedSizes[i]);
                    decompressedChunks.add(decompressed);
                } else {
                    decompressedChunks.add(null);
                }
            }

            for (int i = 0; i < 1024; i++) {
                zstdDataOut.writeInt(chunkUncompressedSizes[i]);
                zstdDataOut.writeInt((int) chunkTimestamps[i]);
            }

            for (int i = 0; i < 1024; i++) {
                if (decompressedChunks.get(i) != null) {
                    zstdDataOut.write(decompressedChunks.get(i));
                }
            }
            zstdDataOut.close();

            fileDataOut.writeShort(chunkCount);
            byte[] compressedZstdData = zstdBAOS.toByteArray();
            fileDataOut.writeInt(compressedZstdData.length);
            fileDataOut.writeLong(0);
            fileDataOut.write(compressedZstdData);
            fileDataOut.writeLong(SUPERBLOCK);

            fileDataOut.flush();
            fos.getFD().sync();
            fos.getChannel().force(true);
        }
        Files.move(tempFile.toPath(), regionFilePath, StandardCopyOption.REPLACE_EXISTING);
    }

    private void flushLinearV2() throws IOException {
        long timestamp = currentTimestamp();
        File tempFile = new File(regionFilePath.toString() + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(tempFile);
             DataOutputStream dataOut = new DataOutputStream(fos)) {

            dataOut.writeLong(SUPERBLOCK);
            dataOut.writeByte(V2_VERSION);
            dataOut.writeLong(timestamp);
            dataOut.writeByte(gridSize);

            int[] regionCoords = parseRegionCoordinates(regionFilePath.getFileName().toString());
            dataOut.writeInt(regionCoords[0]);
            dataOut.writeInt(regionCoords[1]);

            boolean[] chunkExistence = new boolean[1024];
            for (int i = 0; i < 1024; i++) {
                chunkExistence[i] = (chunkUncompressedSizes[i] > 0);
            }
            writeExistenceBitmap(dataOut, chunkExistence);

            writeNBTFeatures(dataOut);

            byte[][] buckets = buildBuckets();

            int bucketCount = gridSize * gridSize;
            for (int i = 0; i < bucketCount; i++) {
                dataOut.writeInt(buckets[i] != null ? buckets[i].length : 0);
                dataOut.writeByte(compressionLevel);
                long bucketHash = buckets[i] != null ? LongHashFunction.xx().hashBytes(buckets[i]) : 0;
                dataOut.writeLong(bucketHash);
            }
            for (int i = 0; i < bucketCount; i++) {
                if (buckets[i] != null) {
                    dataOut.write(buckets[i]);
                }
            }
            dataOut.writeLong(SUPERBLOCK);

            dataOut.flush();
            fos.getFD().sync();
            fos.getChannel().force(true);
        }
        Files.move(tempFile.toPath(), regionFilePath, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeNBTFeatures(DataOutputStream dataOut) throws IOException {
        dataOut.writeByte(0);
    }

    private byte[][] buildBuckets() throws IOException {
        int bucketCount = gridSize * gridSize;
        byte[][] buckets = new byte[bucketCount][];

        for (int bx = 0; bx < gridSize; bx++) {
            for (int bz = 0; bz < gridSize; bz++) {
                int bucketIdx = bx * gridSize + bz;
                if (bucketBuffers != null && bucketBuffers[bucketIdx] != null) {
                    buckets[bucketIdx] = bucketBuffers[bucketIdx];
                    continue;
                }

                try (ByteArrayOutputStream bucketBAOS = new ByteArrayOutputStream();
                     ZstdOutputStream bucketZstdOut = new ZstdOutputStream(bucketBAOS, compressionLevel);
                     DataOutputStream bucketDataOut = new DataOutputStream(bucketZstdOut)) {

                    boolean hasData = false;
                    int cellCount = 32 / gridSize;
                    for (int cx = 0; cx < cellCount; cx++) {
                        for (int cz = 0; cz < cellCount; cz++) {
                            int chunkIndex = (bx * cellCount + cx) + (bz * cellCount + cz) * 32;
                            if (chunkUncompressedSizes[chunkIndex] > 0) {
                                hasData = true;
                                byte[] chunkData = new byte[chunkUncompressedSizes[chunkIndex]];
                                decompressor.decompress(chunkCompressedBuffers[chunkIndex], 0, chunkData, 0, chunkUncompressedSizes[chunkIndex]);
                                bucketDataOut.writeInt(chunkData.length + 8);
                                bucketDataOut.writeLong(chunkTimestamps[chunkIndex]);
                                bucketDataOut.write(chunkData);
                            } else {
                                bucketDataOut.writeInt(0);
                                bucketDataOut.writeLong(chunkTimestamps[chunkIndex]);
                            }
                        }
                    }
                    bucketDataOut.close();
                    if (hasData) {
                        buckets[bucketIdx] = bucketBAOS.toByteArray();
                    }
                }
            }
        }
        return buckets;
    }

    private void openBucketForChunk(int chunkX, int chunkZ) {
        int modX = Math.floorMod(chunkX, 32);
        int modZ = Math.floorMod(chunkZ, 32);
        int bucketIdx = chunkToBucketIndex(modX, modZ);
        if (bucketBuffers == null || bucketBuffers[bucketIdx] == null) {
            return;
        }

        try (ByteArrayInputStream bucketBAIS = new ByteArrayInputStream(bucketBuffers[bucketIdx]);
             ZstdInputStream bucketZstdIn = new ZstdInputStream(bucketBAIS)) {

            ByteBuffer bucketBuffer = ByteBuffer.wrap(bucketZstdIn.readAllBytes());
            int cellsPerBucket = 32 / gridSize;
            int bx = modX / bucketSize, bz = modZ / bucketSize;
            for (int cx = 0; cx < cellsPerBucket; cx++) {
                for (int cz = 0; cz < cellsPerBucket; cz++) {
                    int chunkIndex = (bx * cellsPerBucket + cx) + (bz * cellsPerBucket + cz) * 32;
                    int chunkSize = bucketBuffer.getInt();
                    long timestamp = bucketBuffer.getLong();
                    chunkTimestamps[chunkIndex] = timestamp;

                    if (chunkSize > 0) {
                        byte[] chunkData = new byte[chunkSize - 8];
                        bucketBuffer.get(chunkData);

                        int maxCompressedLength = compressor.maxCompressedLength(chunkData.length);
                        byte[] compressed = new byte[maxCompressedLength];
                        int compressedLength = compressor.compress(chunkData, 0, chunkData.length, compressed, 0, maxCompressedLength);
                        byte[] finalCompressed = new byte[compressedLength];
                        System.arraycopy(compressed, 0, finalCompressed, 0, compressedLength);

                        chunkCompressedBuffers[chunkIndex] = finalCompressed;
                        chunkUncompressedSizes[chunkIndex] = chunkData.length;
                    }
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Region file corrupted: " + regionFilePath + " bucket: " + bucketIdx, ex);
        }
        bucketBuffers[bucketIdx] = null;
    }

    @Override
    public synchronized void write(ChunkPos pos, ByteBuffer buffer) {
        openRegionFile();
        openBucketForChunk(pos.x, pos.z);
        try {
            byte[] rawData = toByteArray(new ByteArrayInputStream(buffer.array()));
            int uncompressedSize = rawData.length;
            if (uncompressedSize > MAX_CHUNK_SIZE) {
                LOGGER.error("Chunk dupe attempt {}", regionFilePath);
                clear(pos);
            } else {
                int maxCompressedLength = compressor.maxCompressedLength(uncompressedSize);
                byte[] compressed = new byte[maxCompressedLength];
                int compressedLength = compressor.compress(rawData, 0, uncompressedSize, compressed, 0, maxCompressedLength);
                byte[] finalCompressed = new byte[compressedLength];
                System.arraycopy(compressed, 0, finalCompressed, 0, compressedLength);

                int index = getChunkIndex(pos.x, pos.z);
                chunkCompressedBuffers[index] = finalCompressed;
                chunkTimestamps[index] = currentTimestamp();
                chunkUncompressedSizes[index] = uncompressedSize;
            }
        } catch (IOException e) {
            LOGGER.error("Chunk write IOException {} {}", e, regionFilePath);
        }
        markToSave();
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) {
        openRegionFile();
        openBucketForChunk(pos.x, pos.z);
        return new DataOutputStream(new BufferedOutputStream(new ChunkBuffer(pos)));
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(CompoundTag data, ChunkPos pos) {
        DataOutputStream out = getChunkDataOutputStream(pos);
        return new ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData(
            data,
            ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
            out,
            regionFile -> {
                try {
                    out.close();
                } catch (IOException e) {
                    LOGGER.error("Failed to close region file stream", e);
                }
            }
        );
    }

    private class ChunkBuffer extends ByteArrayOutputStream {
        private final ChunkPos pos;
        public ChunkBuffer(ChunkPos pos) {
            super();
            this.pos = pos;
        }
        @Override
        public void close() {
            ByteBuffer byteBuffer = ByteBuffer.wrap(this.buf, 0, this.count);
            LinearRegionFile.this.write(this.pos, byteBuffer);
        }
    }

    private byte[] toByteArray(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tempBuffer = new byte[4096];
        int length;
        while ((length = in.read(tempBuffer)) >= 0) {
            out.write(tempBuffer, 0, length);
        }
        return out.toByteArray();
    }

    @Nullable
    @Override
    public synchronized DataInputStream getChunkDataInputStream(ChunkPos pos) {
        openRegionFile();
        openBucketForChunk(pos.x, pos.z);
        int index = getChunkIndex(pos.x, pos.z);
        if (chunkUncompressedSizes[index] != 0) {
            byte[] decompressed = new byte[chunkUncompressedSizes[index]];
            decompressor.decompress(chunkCompressedBuffers[index], 0, decompressed, 0, chunkUncompressedSizes[index]);
            return new DataInputStream(new ByteArrayInputStream(decompressed));
        }
        return null;
    }

    @Override
    public synchronized void clear(ChunkPos pos) {
        openRegionFile();
        openBucketForChunk(pos.x, pos.z);
        int index = getChunkIndex(pos.x, pos.z);
        chunkCompressedBuffers[index] = null;
        chunkUncompressedSizes[index] = 0;
        chunkTimestamps[index] = 0;
        markToSave();
    }

    @Override
    public synchronized void close() throws IOException {
        openRegionFile();
        close = true;
        try {
            flush();
        } catch (IOException e) {
            throw new IOException("Region flush IOException " + e + " " + regionFilePath, e);
        }
    }

    private static int getChunkIndex(int x, int z) {
        return (x & 31) + ((z & 31) << 5);
    }

    private static int currentTimestamp() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    @Override
    public boolean recalculateHeader() {
        return false;
    }

    @Override
    public int getRecalculateCount() {
        return 0;
    }

    @Override
    public void setOversized(int x, int z, boolean something) {
        // stub
    }

    @Override
    public CompoundTag getOversizedData(int x, int z) throws IOException {
        throw new IOException("getOversizedData is a stub " + regionFilePath);
    }

    @Override
    public boolean isOversized(int x, int z) {
        return false;
    }

    @Override
    public Path getPath() {
        return regionFilePath;
    }

    private boolean[] deserializeExistenceBitmap(ByteBuffer buffer) {
        boolean[] result = new boolean[1024];
        for (int i = 0; i < 128; i++) {
            byte b = buffer.get();
            for (int j = 0; j < 8; j++) {
                result[i * 8 + j] = ((b >> (7 - j)) & 1) == 1;
            }
        }
        return result;
    }

    private void writeExistenceBitmap(DataOutputStream out, boolean[] bitmap) throws IOException {
        for (int i = 0; i < 128; i++) {
            byte b = 0;
            for (int j = 0; j < 8; j++) {
                if (bitmap[i * 8 + j]) {
                    b |= (1 << (7 - j));
                }
            }
            out.writeByte(b);
        }
    }

    private int chunkToBucketIndex(int chunkX, int chunkZ) {
        int bx = chunkX / bucketSize, bz = chunkZ / bucketSize;
        return bx * gridSize + bz;
    }

    private int[] parseRegionCoordinates(String fileName) {
        int regionX = 0;
        int regionZ = 0;
        String[] parts = fileName.split("\\.");
        if (parts.length >= 4) {
            try {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                LOGGER.error("Failed to parse region coordinates from file name: {}", fileName, e);
            }
        } else {
            LOGGER.warn("Unexpected file name format: {}", fileName);
        }
        return new int[]{regionX, regionZ};
    }
}
