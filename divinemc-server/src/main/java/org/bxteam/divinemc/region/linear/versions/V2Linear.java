package org.bxteam.divinemc.region.linear.versions;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.openhft.hashing.LongHashFunction;
import org.bxteam.divinemc.region.linear.LinearImplementation;
import org.bxteam.divinemc.region.linear.V2LinearBase;

public class V2Linear extends V2LinearBase {
    public V2Linear(Path regionFilePath, int compressionLevel) {
        super(regionFilePath, compressionLevel);
    }

    @Override
    public LinearImplementation implementation() {
        return LinearImplementation.V2;
    }

    @Override
    public void parse(ByteBuffer buffer) throws IOException {
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

    @Override
    public synchronized void flush() throws IOException {
        long timestamp = currentTimestamp();
        File tempFile = new File(regionFilePath.toString() + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(tempFile);
             DataOutputStream dataOut = new DataOutputStream(fos)) {

            dataOut.writeLong(SUPERBLOCK);
            dataOut.writeByte(version());
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

    private void writeNBTFeatures(DataOutputStream dataOut) throws IOException {
        dataOut.writeByte(0);
    }

    protected int[] parseRegionCoordinates(String fileName) {
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
