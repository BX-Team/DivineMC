package org.bxteam.divinemc.region.linear;

import java.nio.file.Path;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.bxteam.divinemc.region.linear.versions.V1Linear;
import org.bxteam.divinemc.region.linear.versions.V2Linear;
import org.bxteam.divinemc.region.linear.versions.V3Linear;
import org.bxteam.divinemc.region.linear.versions.Version;
import org.slf4j.Logger;

public abstract class LinearBase implements Version {

    protected static final Logger LOGGER = LinearRegionFile.LOGGER;
    protected static final long SUPERBLOCK = LinearRegionFile.SUPERBLOCK;
    protected final Path regionFilePath;
    protected final byte[][] chunkCompressedBuffers = new byte[1024][];
    protected final int[] chunkUncompressedSizes = new int[1024];
    protected final long[] chunkTimestamps = new long[1024];
    protected final LZ4Compressor compressor = LZ4Factory.fastestInstance().fastCompressor();
    protected final LZ4FastDecompressor decompressor = LZ4Factory.fastestInstance().fastDecompressor();
    protected final int compressionLevel;

    public LinearBase(Path regionFilePath, int compressionLevel) {
        this.regionFilePath = regionFilePath;
        this.compressionLevel = compressionLevel;
    }

    protected static int currentTimestamp() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    public static LinearBase getShared(byte version, Path regionFilePath, int compressionLevel) {
        return switch (version) {
            case 1, 2 -> new V1Linear(regionFilePath, compressionLevel);
            case 3 -> new V2Linear(regionFilePath, compressionLevel);
            case 4 -> new V3Linear(regionFilePath, compressionLevel);
            default -> throw new RuntimeException("Invalid version: " + version + " file " + regionFilePath);
        };
    }
}
