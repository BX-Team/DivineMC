package org.bxteam.divinemc.region.linear;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;

public abstract class V2LinearBase extends LinearBase {
    protected final int gridSizeDefault = 8;
    protected int gridSize = gridSizeDefault;
    protected int bucketSize = 4;
    protected byte[][] bucketBuffers;

    public V2LinearBase(Path regionFilePath, int compressionLevel) {
        super(regionFilePath, compressionLevel);
    }

    protected byte[][] buildBuckets() throws IOException {
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

    /**
     * Extracts chunk data from bucketBuffers and populates chunkCompressedBuffers, chunkUncompressedSizes,
     * and chunkTimestamps arrays.
     *
     * @throws IOException if there's an error during decompression or reading the data
     */
    protected void extractChunksFromBuckets() throws IOException {
        if (bucketBuffers == null) {
            return;
        }

        // Reset chunk data arrays
        for (int i = 0; i < 1024; i++) {
            chunkCompressedBuffers[i] = null;
            chunkUncompressedSizes[i] = 0;
            chunkTimestamps[i] = 0;
        }

        int cellCount = 32 / gridSize;

        for (int bx = 0; bx < gridSize; bx++) {
            for (int bz = 0; bz < gridSize; bz++) {
                int bucketIdx = bx * gridSize + bz;
                byte[] bucketData = bucketBuffers[bucketIdx];

                if (bucketData == null || bucketData.length == 0) {
                    continue;
                }

                try (ByteArrayInputStream bucketBAIS = new ByteArrayInputStream(bucketData);
                     ZstdInputStream bucketZstdIn = new ZstdInputStream(bucketBAIS);
                     DataInputStream bucketDataIn = new DataInputStream(bucketZstdIn)) {

                    for (int cx = 0; cx < cellCount; cx++) {
                        for (int cz = 0; cz < cellCount; cz++) {
                            int chunkIndex = (bx * cellCount + cx) + (bz * cellCount + cz) * 32;

                            int dataSize = bucketDataIn.readInt();
                            long timestamp = bucketDataIn.readLong();
                            chunkTimestamps[chunkIndex] = timestamp;

                            if (dataSize > 0) {
                                // Real size is dataSize - 8 (timestamp size)
                                int chunkSize = dataSize - 8;
                                byte[] chunkData = new byte[chunkSize];
                                bucketDataIn.readFully(chunkData);

                                // Compress the chunk data using LZ4
                                int maxCompressedSize = compressor.maxCompressedLength(chunkSize);
                                byte[] compressedData = new byte[maxCompressedSize];
                                int compressedSize = compressor.compress(chunkData, 0, chunkSize,
                                    compressedData, 0, maxCompressedSize);

                                // Trim the compressed data to actual size
                                byte[] trimmedCompressedData = new byte[compressedSize];
                                System.arraycopy(compressedData, 0, trimmedCompressedData, 0, compressedSize);

                                chunkCompressedBuffers[chunkIndex] = trimmedCompressedData;
                                chunkUncompressedSizes[chunkIndex] = chunkSize;
                            }
                        }
                    }
                }
            }
        }
    }
}
