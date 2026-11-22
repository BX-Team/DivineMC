package org.bxteam.divinemc.region.format;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictDecompress;
import org.jspecify.annotations.NullMarked;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Encoding and checking of one chunk record (SPEC.md, "Chunk record"). */
@NullMarked
public final class ChunkRecord {
    private ChunkRecord() {
    }

    /** Compresses raw chunk NBT into a complete record: header followed by the zstd payload. */
    public static byte[] encode(int localIndex, byte[] raw, int offset, int length, long timestamp, int dictId, int level, LinearDictionaries dictionaries) throws LinearFormatException {
        if (length > LinearFormat.MAX_RAW_LENGTH) {
            throw new LinearFormatException("Chunk of " + length + " bytes exceeds the format limit of " + LinearFormat.MAX_RAW_LENGTH);
        }
        final byte[] out = new byte[LinearFormat.RECORD_HEADER_BYTES + (int) Zstd.compressBound(length)];
        final long written = dictId == 0
            ? Zstd.compressByteArray(out, LinearFormat.RECORD_HEADER_BYTES, out.length - LinearFormat.RECORD_HEADER_BYTES, raw, offset, length, level)
            : Zstd.compressFastDict(out, LinearFormat.RECORD_HEADER_BYTES, raw, offset, length, dictionaries.compressor(dictId, level));
        if (Zstd.isError(written)) {
            throw new LinearFormatException("zstd compression failed: " + Zstd.getErrorName(written));
        }
        final int compressed = (int) written;
        final ByteBuffer header = ByteBuffer.wrap(out, 0, LinearFormat.RECORD_HEADER_BYTES);
        header.putInt(LinearFormat.RECORD_MAGIC).putShort((short) localIndex).putShort((short) 0).putInt(dictId)
            .putInt(compressed).putInt(length).putLong(timestamp)
            .putLong(LinearFormat.xxh64(out, LinearFormat.RECORD_HEADER_BYTES, compressed));
        return Arrays.copyOf(out, LinearFormat.RECORD_HEADER_BYTES + compressed);
    }

    public static long timestamp(byte[] record) {
        return ByteBuffer.wrap(record).getLong(20);
    }

    public static int dictionaryId(byte[] record) {
        return ByteBuffer.wrap(record).getInt(8);
    }

    public static int rawLength(byte[] record) {
        return ByteBuffer.wrap(record).getInt(16);
    }

    /** True when a writer should put this chunk into a pack instead of keeping it as its own record. */
    public static boolean packable(byte[] record) {
        return record.length - LinearFormat.RECORD_HEADER_BYTES <= LinearFormat.PACK_MAX_PAYLOAD && rawLength(record) <= LinearFormat.PACK_MAX_MEMBER_RAW;
    }

    /** Checks a record read for the given chunk and returns its decompressed NBT. */
    public static byte[] decode(byte[] record, int localIndex, LinearDictionaries dictionaries) throws LinearFormatException {
        if (record.length < LinearFormat.RECORD_HEADER_BYTES) {
            throw new LinearFormatException("Record of chunk " + localIndex + " is truncated");
        }
        final ByteBuffer header = ByteBuffer.wrap(record);
        final int magic = header.getInt();
        final int index = header.getShort() & 0xFFFF;
        header.getShort();
        final int dictId = header.getInt();
        final int compressed = header.getInt();
        final int raw = header.getInt();
        header.getLong();
        final long checksum = header.getLong();
        if (magic != LinearFormat.RECORD_MAGIC || index != localIndex) {
            throw new LinearFormatException("Index points at a record that is not chunk " + localIndex);
        }
        if (compressed < 0 || compressed != record.length - LinearFormat.RECORD_HEADER_BYTES) {
            throw new LinearFormatException("Record of chunk " + localIndex + " has length " + compressed + ", index says " + (record.length - LinearFormat.RECORD_HEADER_BYTES));
        }
        if (raw < 0 || raw > LinearFormat.MAX_RAW_LENGTH || compressed > LinearFormat.maxCompressedLength(raw)) {
            throw new LinearFormatException("Record of chunk " + localIndex + " claims an impossible size " + raw);
        }
        if (LinearFormat.xxh64(record, LinearFormat.RECORD_HEADER_BYTES, compressed) != checksum) {
            throw new LinearFormatException("Checksum mismatch in chunk " + localIndex);
        }
        final byte[] out = new byte[raw];
        final long result;
        if (dictId == 0) {
            result = Zstd.decompressByteArray(out, 0, raw, record, LinearFormat.RECORD_HEADER_BYTES, compressed);
        } else {
            final ZstdDictDecompress dict = dictionaries.decompressor(dictId);
            if (dict == null) {
                throw new LinearFormatException("Chunk " + localIndex + " needs zstd dictionary " + dictId + ", which this build does not have");
            }
            result = Zstd.decompressFastDict(out, 0, record, LinearFormat.RECORD_HEADER_BYTES, compressed, dict);
        }
        if (Zstd.isError(result)) {
            throw new LinearFormatException("Chunk " + localIndex + " does not decompress: " + Zstd.getErrorName(result));
        }
        if (result != raw) {
            throw new LinearFormatException("Chunk " + localIndex + " decompressed to " + result + " bytes, expected " + raw);
        }
        return out;
    }
}
