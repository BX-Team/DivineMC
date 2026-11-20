package org.bxteam.divinemc.region.format;

import net.openhft.hashing.LongHashFunction;
import org.jspecify.annotations.NullMarked;

import java.nio.ByteBuffer;

/** Constants and fixed-size structures of the Linear format; see linear-format/SPEC.md. */
@NullMarked
public final class LinearFormat {
    public static final long MAGIC = 0x894C494E0D0A1A0AL;
    public static final long LEGACY_MAGIC = 0xC3FF13183CCA9D9AL;
    public static final int MAJOR_VERSION = 1;
    public static final int MINOR_VERSION = 0;

    public static final int HEADER_BYTES = 32;
    public static final int SLOT_BYTES = 32;
    public static final int SLOT_A_OFFSET = HEADER_BYTES;
    public static final int SLOT_B_OFFSET = HEADER_BYTES + SLOT_BYTES;
    public static final int LOG_START = HEADER_BYTES + 2 * SLOT_BYTES;

    public static final int RECORD_MAGIC = 0x4C4E4352;
    public static final int RECORD_HEADER_BYTES = 36;
    public static final int INDEX_MAGIC = 0x4C4E4958;
    public static final int INDEX_HEADER_BYTES = 28;
    public static final int INDEX_ENTRY_BYTES = 20;
    public static final int CHUNKS = 1024;
    public static final int INDEX_BODY_BYTES = CHUNKS * INDEX_ENTRY_BYTES;

    public static final int MAX_RAW_LENGTH = 512 * 1024 * 1024;
    public static final int INDEX_COMPRESSION_LEVEL = 3;

    private static final LongHashFunction XXH64 = LongHashFunction.xx();

    private LinearFormat() {
    }

    public static long xxh64(byte[] bytes, int offset, int length) {
        return XXH64.hashBytes(bytes, offset, length);
    }

    public static long xxh64(ByteBuffer buffer) {
        return XXH64.hashBytes(buffer);
    }

    public static int maxCompressedLength(int rawLength) {
        return rawLength + rawLength / 128 + 1024;
    }

    public static int localIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) + ((chunkZ & 31) << 5);
    }

    public static byte[] header(int regionX, int regionZ) {
        final ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES);
        buf.putLong(MAGIC).putShort((short) MAJOR_VERSION).putShort((short) MINOR_VERSION).putInt(0).putInt(regionX).putInt(regionZ);
        buf.putLong(xxh64(buf.array(), 0, 24));
        return buf.array();
    }

    public static byte[] slot(long generation, long indexOffset, int indexLength) {
        final ByteBuffer buf = ByteBuffer.allocate(SLOT_BYTES);
        buf.putLong(generation).putLong(indexOffset).putInt(indexLength).putInt(0);
        buf.putLong(xxh64(buf.array(), 0, 24));
        return buf.array();
    }

    public record Slot(long generation, long indexOffset, int indexLength, boolean checksumValid) {
        public static Slot parse(byte[] bytes, int offset) {
            final ByteBuffer buf = ByteBuffer.wrap(bytes, offset, SLOT_BYTES);
            final long generation = buf.getLong();
            final long indexOffset = buf.getLong();
            final int indexLength = buf.getInt();
            buf.getInt();
            final long checksum = buf.getLong();
            return new Slot(generation, indexOffset, indexLength, checksum == xxh64(bytes, offset, 24));
        }
    }
}
