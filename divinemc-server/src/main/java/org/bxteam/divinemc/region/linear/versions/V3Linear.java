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

public class V3Linear extends V2LinearBase {
    public V3Linear(Path regionFilePath, int compressionLevel) {
        super(regionFilePath, compressionLevel);
    }

    @Override
    public LinearImplementation implementation() {
        return LinearImplementation.V3;
    }

    @Override
    public void parse(ByteBuffer buffer) throws IOException {
        gridSize = buffer.get();
        if (!(gridSize == 1 || gridSize == 2 || gridSize == 4 || gridSize == 8 || gridSize == 16 || gridSize == 32)) {
            throw new RuntimeException("Invalid grid size: " + gridSize + " file " + regionFilePath);
        }
        bucketSize = 32 / gridSize;

        int bucketCount = gridSize * gridSize;
        int[] bucketSizes = new int[bucketCount];
        long[] bucketHashes = new long[bucketCount];

        for (int i = 0; i < bucketCount; i++) {
            bucketSizes[i] = buffer.getInt();
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
        File tempFile = new File(regionFilePath.toString() + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(tempFile);
             DataOutputStream dataOut = new DataOutputStream(fos)) {

            dataOut.writeLong(SUPERBLOCK);
            dataOut.writeByte(version());
            dataOut.writeByte(gridSize);

            byte[][] buckets = buildBuckets();

            int bucketCount = gridSize * gridSize;
            for (int i = 0; i < bucketCount; i++) {
                dataOut.writeInt(buckets[i] != null ? buckets[i].length : 0);
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
}
