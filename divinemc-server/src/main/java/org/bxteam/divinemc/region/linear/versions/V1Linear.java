package org.bxteam.divinemc.region.linear.versions;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import org.bxteam.divinemc.region.linear.LinearBase;
import org.bxteam.divinemc.region.linear.LinearImplementation;

public class V1Linear extends LinearBase {
    public V1Linear(Path regionFilePath, int compressionLevel) {
        super(regionFilePath, compressionLevel);
    }

    @Override
    public LinearImplementation implementation() {
        return LinearImplementation.V1;
    }

    @Override
    public void parse(ByteBuffer buffer) throws IOException {
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

    @Override
    public synchronized void flush() throws IOException {
        long timestamp = currentTimestamp();
        short chunkCount = 0;
        File tempFile = new File(regionFilePath.toString() + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(tempFile);
             ByteArrayOutputStream zstdBAOS = new ByteArrayOutputStream();
             ZstdOutputStream zstdOut = new ZstdOutputStream(zstdBAOS, compressionLevel);
             DataOutputStream zstdDataOut = new DataOutputStream(zstdOut);
             DataOutputStream fileDataOut = new DataOutputStream(fos)) {

            fileDataOut.writeLong(SUPERBLOCK);
            fileDataOut.writeByte(version());
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
}
