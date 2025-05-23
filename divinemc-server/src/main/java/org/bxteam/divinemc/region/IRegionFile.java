package org.bxteam.divinemc.region;

import ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemRegionFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public interface IRegionFile extends AutoCloseable, ChunkSystemRegionFile {
    Path getPath();

    void flush() throws IOException;

    void clear(ChunkPos pos) throws IOException;

    @Override
    void close() throws IOException;

    void setOversized(int x, int z, boolean b) throws IOException;

    void write(ChunkPos pos, ByteBuffer buffer) throws IOException;

    boolean hasChunk(ChunkPos pos);

    boolean doesChunkExist(ChunkPos pos) throws Exception;

    boolean isOversized(int x, int z);

    boolean recalculateHeader() throws IOException;

    int getRecalculateCount();

    DataOutputStream getChunkDataOutputStream(ChunkPos pos) throws IOException;

    DataInputStream getChunkDataInputStream(ChunkPos pos) throws IOException;

    CompoundTag getOversizedData(int x, int z) throws IOException;
}
