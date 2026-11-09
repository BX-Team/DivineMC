package org.bxteam.divinemc.region;

import ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemRegionFile;
import net.minecraft.world.level.ChunkPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public interface IRegionFile extends ChunkSystemRegionFile, AutoCloseable {
    Path getPath();

    DataInputStream getChunkDataInputStream(ChunkPos pos) throws IOException;

    boolean doesChunkExist(ChunkPos pos) throws Exception;

    DataOutputStream getChunkDataOutputStream(ChunkPos pos) throws IOException;

    void flush() throws IOException;

    void clear(ChunkPos pos) throws IOException;

    boolean hasChunk(ChunkPos pos);

    void close() throws IOException;

    void write(ChunkPos pos, ByteBuffer buf) throws IOException;

    @Override
    default int moonrise$getRecalculateCount() {
        return 0;
    }

    @Override
    default boolean moonrise$recalculateHeader() throws IOException {
        return false;
    }

    @Override
    default Boolean moonrise$tryRecalcHeader(final int expectedRecalcCount) throws IOException {
        return Boolean.FALSE;
    }
}
