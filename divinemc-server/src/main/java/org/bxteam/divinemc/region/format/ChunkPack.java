package org.bxteam.divinemc.region.format;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictDecompress;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Encoding and checking of a pack record: several small chunks compressed as one frame (SPEC.md, "Pack record"). */
@NullMarked
public final class ChunkPack {
    private ChunkPack() {
    }

    /**
     * Compresses {@code count} chunks, in ascending local index order, into one complete pack record. Its timestamp is
     * the newest of the chunks' timestamps.
     */
    public static byte[] encode(int[] indices, byte[][] raws, long[] timestamps, int count, int dictId, int level, LinearDictionaries dictionaries) throws LinearFormatException {
        if (count < 1 || count > LinearFormat.CHUNKS) {
            throw new LinearFormatException("A pack holds 1 to " + LinearFormat.CHUNKS + " chunks, not " + count);
        }
        long rawTotal = (long) count * LinearFormat.PACK_MEMBER_BYTES;
        long newest = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            if (i > 0 && indices[i] <= indices[i - 1]) {
                throw new LinearFormatException("Pack members must be in ascending local index order");
            }
            rawTotal += raws[i].length;
            newest = Math.max(newest, timestamps[i]);
        }
        if (rawTotal > LinearFormat.MAX_RAW_LENGTH) {
            throw new LinearFormatException("Pack of " + rawTotal + " bytes exceeds the format limit of " + LinearFormat.MAX_RAW_LENGTH);
        }
        final ByteBuffer raw = ByteBuffer.allocate((int) rawTotal);
        for (int i = 0; i < count; i++) {
            raw.putShort((short) indices[i]).putInt(raws[i].length);
        }
        for (int i = 0; i < count; i++) {
            raw.put(raws[i]);
        }
        final byte[] in = raw.array();
        final byte[] out = new byte[LinearFormat.RECORD_HEADER_BYTES + (int) Zstd.compressBound(in.length)];
        final long written = dictId == 0
            ? Zstd.compressByteArray(out, LinearFormat.RECORD_HEADER_BYTES, out.length - LinearFormat.RECORD_HEADER_BYTES, in, 0, in.length, level)
            : Zstd.compressFastDict(out, LinearFormat.RECORD_HEADER_BYTES, in, 0, in.length, dictionaries.compressor(dictId, level));
        if (Zstd.isError(written)) {
            throw new LinearFormatException("zstd compression failed: " + Zstd.getErrorName(written));
        }
        final int compressed = (int) written;
        final ByteBuffer header = ByteBuffer.wrap(out, 0, LinearFormat.RECORD_HEADER_BYTES);
        header.putInt(LinearFormat.PACK_MAGIC).putShort((short) count).putShort((short) 0).putInt(dictId)
            .putInt(compressed).putInt(in.length).putLong(newest)
            .putLong(LinearFormat.xxh64(out, LinearFormat.RECORD_HEADER_BYTES, compressed));
        return Arrays.copyOf(out, LinearFormat.RECORD_HEADER_BYTES + compressed);
    }

    public static long timestamp(byte[] record) {
        return ByteBuffer.wrap(record).getLong(20);
    }

    /**
     * The pack starting at {@code pos} decoded, or null when it is not a valid pack; used by salvage, which accepts a
     * pack only when all of it checks out.
     */
    static @Nullable Decoded salvage(byte[] bytes, int pos, LinearDictionaries dictionaries) {
        if (pos + LinearFormat.RECORD_HEADER_BYTES > bytes.length) return null;
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        final int compressed = buf.getInt(pos + 12);
        if (compressed < 0 || (long) pos + LinearFormat.RECORD_HEADER_BYTES + compressed > bytes.length) return null;
        try {
            return decode(Arrays.copyOfRange(bytes, pos, pos + LinearFormat.RECORD_HEADER_BYTES + compressed), dictionaries);
        } catch (LinearFormatException e) {
            return null;
        }
    }

    /** Checks a pack record and decompresses all of its chunks. */
    public static Decoded decode(byte[] record, LinearDictionaries dictionaries) throws LinearFormatException {
        if (record.length < LinearFormat.RECORD_HEADER_BYTES) {
            throw new LinearFormatException("Pack record is truncated");
        }
        final ByteBuffer buf = ByteBuffer.wrap(record);
        if (buf.getInt(0) != LinearFormat.PACK_MAGIC) {
            throw new LinearFormatException("Not a pack record");
        }
        final int count = buf.getShort(4) & 0xFFFF;
        final int dictId = buf.getInt(8);
        final int compressed = buf.getInt(12);
        final int raw = buf.getInt(16);
        final long timestamp = buf.getLong(20);
        if (count < 1 || count > LinearFormat.CHUNKS || raw < (long) count * LinearFormat.PACK_MEMBER_BYTES || raw > LinearFormat.MAX_RAW_LENGTH
            || compressed < 0 || compressed > LinearFormat.maxCompressedLength(raw)) {
            throw new LinearFormatException("Pack record claims an impossible size");
        }
        if (compressed != record.length - LinearFormat.RECORD_HEADER_BYTES) {
            throw new LinearFormatException("Pack record has a length that disagrees with the index");
        }
        if (LinearFormat.xxh64(record, LinearFormat.RECORD_HEADER_BYTES, compressed) != buf.getLong(28)) {
            throw new LinearFormatException("Checksum mismatch in pack record");
        }
        final byte[] out = new byte[raw];
        final long result;
        if (dictId == 0) {
            result = Zstd.decompressByteArray(out, 0, raw, record, LinearFormat.RECORD_HEADER_BYTES, compressed);
        } else {
            final ZstdDictDecompress dict = dictionaries.decompressor(dictId);
            if (dict == null) {
                throw new LinearFormatException("Pack needs zstd dictionary " + dictId + ", which this build does not have");
            }
            result = Zstd.decompressFastDict(out, 0, record, LinearFormat.RECORD_HEADER_BYTES, compressed, dict);
        }
        if (Zstd.isError(result)) {
            throw new LinearFormatException("Pack does not decompress: " + Zstd.getErrorName(result));
        }
        if (result != raw) {
            throw new LinearFormatException("Pack decompressed to " + result + " bytes, expected " + raw);
        }
        final ByteBuffer table = ByteBuffer.wrap(out);
        final int[] indices = new int[count];
        final int[] starts = new int[count];
        final int[] lengths = new int[count];
        long at = (long) count * LinearFormat.PACK_MEMBER_BYTES;
        for (int i = 0; i < count; i++) {
            indices[i] = table.getShort() & 0xFFFF;
            lengths[i] = table.getInt();
            if (indices[i] >= LinearFormat.CHUNKS || (i > 0 && indices[i] <= indices[i - 1]) || lengths[i] < 0) {
                throw new LinearFormatException("Pack record has a corrupt member table");
            }
            starts[i] = (int) Math.min(at, Integer.MAX_VALUE);
            at += lengths[i];
        }
        if (at != raw) {
            throw new LinearFormatException("Pack members add up to " + at + " bytes, header says " + raw);
        }
        return new Decoded(timestamp, indices, starts, lengths, out);
    }

    /** The decompressed chunks of one pack. Immutable: {@link #member} hands out copies. */
    public record Decoded(long timestamp, int[] indices, int[] starts, int[] lengths, byte[] raw) {
        public int count() {
            return this.indices.length;
        }

        /** The NBT of the chunk with this local index; an error when the pack does not hold it. */
        public byte[] member(int localIndex) throws LinearFormatException {
            final int i = Arrays.binarySearch(this.indices, localIndex);
            if (i < 0) {
                throw new LinearFormatException("Index points at a pack that does not hold chunk " + localIndex);
            }
            return Arrays.copyOfRange(this.raw, this.starts[i], this.starts[i] + this.lengths[i]);
        }
    }
}
