package org.bxteam.divinemc.region;

import ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemRegionFile;
import net.minecraft.nbt.CompoundTag;
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

    default CompoundTag getOversizedData(int x, int z) throws IOException {
        return null;
    }

    default boolean isOversized(int x, int z) {
        return false;
    }

    default boolean recalculateHeader() throws IOException {
        return false;
    }

    default void setOversized(int x, int z, boolean oversized) throws IOException {

    }

    default int getRecalculateCount() {
        return 0;
    };
}
