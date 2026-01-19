package org.bxteam.divinemc.region.linear;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.github.luben.zstd.ZstdInputStream;
import com.mojang.logging.LogUtils;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.region.IRegionFile;
import org.bxteam.divinemc.region.linear.versions.V1Linear;
import org.bxteam.divinemc.region.linear.versions.V2Linear;
import org.bxteam.divinemc.region.linear.versions.V3Linear;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class LinearRegionFile implements IRegionFile {
    protected static final int MAX_CHUNK_SIZE = 500 * 1024 * 1024;
    protected static final long SUPERBLOCK = 0xc3ff13183cca9d9aL;
    protected static final Logger LOGGER = LogUtils.getLogger();

    protected final LZ4Compressor compressor = LZ4Factory.fastestInstance().fastCompressor();
    protected final LZ4FastDecompressor decompressor = LZ4Factory.fastestInstance().fastDecompressor();
    private final LinearBase linearBase;

    private final Path regionFilePath;

    private final AtomicBoolean markedToSave = new AtomicBoolean(false);
    protected boolean closed = false;

    public LinearRegionFile(Path path, int compressionLevel, LinearImplementation linearImplementation) {
        this.regionFilePath = path;
        linearBase = switch (linearImplementation) {
            case V1 -> new V1Linear(path, compressionLevel);
            case V2 -> new V2Linear(path, compressionLevel);
            case V3 -> new V3Linear(path, compressionLevel);
            case null -> throw new IllegalStateException("Unexpected value: " + null);
        };

        File file = regionFilePath.toFile();
        if (!file.canRead()) {
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
            if (version == 1) version = 2;

            if (linearBase.version() == version) {
                linearBase.parse(byteBuffer);
            } else {
                synchronized (linearBase) {
                    LOGGER.info("Converting region file {} from version {} to version {}",
                        regionFilePath, version, linearBase.version());

                    // Create a temporary shared instance for the old version
                    LinearBase linearBaseWrong = LinearBase.getShared(version, regionFilePath, linearBase.compressionLevel);

                    // Parse with the old format parser
                    linearBaseWrong.parse(byteBuffer);

                    if (linearBaseWrong instanceof V2LinearBase v2LinearBase) {
                        v2LinearBase.extractChunksFromBuckets();
                    }

                    // Transfer data from old shared to current shared
                    for (int i = 0; i < 1024; i++) {
                        if (linearBaseWrong.chunkUncompressedSizes[i] > 0) {
                            linearBase.chunkCompressedBuffers[i] = linearBaseWrong.chunkCompressedBuffers[i];
                            linearBase.chunkUncompressedSizes[i] = linearBaseWrong.chunkUncompressedSizes[i];
                            linearBase.chunkTimestamps[i] = linearBaseWrong.chunkTimestamps[i];
                        }
                    }

                    LOGGER.info("Successfully converted region file {} to version {}",
                        regionFilePath, linearBase.version());
                    markToSave();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to open region file " + regionFilePath, e);
        }
    }

    private static int getChunkIndex(int x, int z) {
        return (x & 31) + ((z & 31) << 5);
    }

    private static int currentTimestamp() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    @Override
    public Path getPath() {
        return regionFilePath;
    }

    @Override
    public synchronized void flush() throws IOException {
        if (isMarkedToSave()) linearBase.flush();
    }

    private void markToSave() {
        ((LinearRegionFileFlusher) DivineConfig.RegionSettingsCategory.flusher).addFile(this);
        markedToSave.set(true);
    }

    public boolean isMarkedToSave() {
        return markedToSave.getAndSet(false);
    }

    public void flushWrapper() {
        try {
            linearBase.flush();
        } catch (IOException e) {
            LOGGER.error("Failed to flush region file {}", regionFilePath.toAbsolutePath(), e);
        }
    }

    @Override
    public synchronized boolean doesChunkExist(ChunkPos pos) {
        return hasChunk(pos);
    }

    @Override
    public synchronized boolean hasChunk(ChunkPos pos) {
        openBucketForChunk(pos.x, pos.z);
        int index = getChunkIndex(pos.x, pos.z);
        return linearBase.chunkUncompressedSizes[index] > 0;
    }

    private void openBucketForChunk(int chunkX, int chunkZ) {
        if (!(linearBase instanceof V2LinearBase v2Shared)) return;

        int modX = Math.floorMod(chunkX, 32);
        int modZ = Math.floorMod(chunkZ, 32);
        int bucketSize = v2Shared.bucketSize;
        int gridSize = v2Shared.gridSize;

        int bucketIdx = (modX / bucketSize) * gridSize + (modZ / bucketSize);
        if (v2Shared.bucketBuffers == null || v2Shared.bucketBuffers[bucketIdx] == null) {
            return;
        }

        try (ByteArrayInputStream bucketBAIS = new ByteArrayInputStream(v2Shared.bucketBuffers[bucketIdx]);
             ZstdInputStream bucketZstdIn = new ZstdInputStream(bucketBAIS)) {

            ByteBuffer bucketBuffer = ByteBuffer.wrap(bucketZstdIn.readAllBytes());
            int cellsPerBucket = 32 / gridSize;
            int bx = modX / bucketSize, bz = modZ / bucketSize;
            for (int cx = 0; cx < cellsPerBucket; cx++) {
                for (int cz = 0; cz < cellsPerBucket; cz++) {
                    int chunkIndex = (bx * cellsPerBucket + cx) + (bz * cellsPerBucket + cz) * 32;
                    int chunkSize = bucketBuffer.getInt();
                    long timestamp = bucketBuffer.getLong();
                    v2Shared.chunkTimestamps[chunkIndex] = timestamp;

                    if (chunkSize > 0) {
                        byte[] chunkData = new byte[chunkSize - 8];
                        bucketBuffer.get(chunkData);

                        int maxCompressedLength = compressor.maxCompressedLength(chunkData.length);
                        byte[] compressed = new byte[maxCompressedLength];
                        int compressedLength = compressor.compress(chunkData, 0, chunkData.length, compressed, 0, maxCompressedLength);
                        byte[] finalCompressed = new byte[compressedLength];
                        System.arraycopy(compressed, 0, finalCompressed, 0, compressedLength);

                        v2Shared.chunkCompressedBuffers[chunkIndex] = finalCompressed;
                        v2Shared.chunkUncompressedSizes[chunkIndex] = chunkData.length;
                    }
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Region file corrupted: " + regionFilePath + " bucket: " + bucketIdx, ex);
        }
        v2Shared.bucketBuffers[bucketIdx] = null;
    }

    @Override
    public synchronized void write(ChunkPos pos, ByteBuffer buffer) {
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
                linearBase.chunkCompressedBuffers[index] = finalCompressed;
                linearBase.chunkTimestamps[index] = currentTimestamp();
                linearBase.chunkUncompressedSizes[index] = uncompressedSize;
            }
        } catch (IOException e) {
            LOGGER.error("Chunk write IOException {} {}", e, regionFilePath);
        }
        markToSave();
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) {
        openBucketForChunk(pos.x, pos.z);
        return new DataOutputStream(new BufferedOutputStream(new ChunkBuffer(pos)));
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
        openBucketForChunk(pos.x, pos.z);
        int index = getChunkIndex(pos.x, pos.z);
        if (linearBase.chunkUncompressedSizes[index] != 0) {
            byte[] decompressed = new byte[linearBase.chunkUncompressedSizes[index]];
            decompressor.decompress(linearBase.chunkCompressedBuffers[index], 0, decompressed, 0, linearBase.chunkUncompressedSizes[index]);
            return new DataInputStream(new ByteArrayInputStream(decompressed));
        }
        return null;
    }

    @Override
    public synchronized void clear(ChunkPos pos) {
        openBucketForChunk(pos.x, pos.z);
        int index = getChunkIndex(pos.x, pos.z);
        linearBase.chunkCompressedBuffers[index] = null;
        linearBase.chunkUncompressedSizes[index] = 0;
        linearBase.chunkTimestamps[index] = 0;
        markToSave();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        flush();
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(CompoundTag data, ChunkPos pos) {
        DataOutputStream out = getChunkDataOutputStream(pos);
        return new MoonriseRegionFileIO.RegionDataController.WriteData(
            data,
            MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
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
}
