package org.bxteam.divinemc.region.format;

import com.github.luben.zstd.Zstd;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** One published state of a region: where each chunk's current record is (SPEC.md, "Index"). Immutable. */
@NullMarked
public final class LinearIndex {
    public static final LinearIndex EMPTY = new LinearIndex(0, new long[LinearFormat.CHUNKS], new int[LinearFormat.CHUNKS], new long[LinearFormat.CHUNKS]);

    final long generation;
    final long[] offsets;
    final int[] lengths;
    final long[] timestamps;
    final long liveBytes;
    // how many chunks point at the same record as chunk i: 1 for a record of its own, more for a pack
    final int[] sharing;
    final int smallSingles;

    LinearIndex(long generation, long[] offsets, int[] lengths, long[] timestamps) {
        this.generation = generation;
        this.offsets = offsets;
        this.lengths = lengths;
        this.timestamps = timestamps;
        final Map<Long, Integer> counts = new HashMap<>();
        long live = 0;
        for (int i = 0; i < LinearFormat.CHUNKS; i++) {
            if (offsets[i] == 0) continue;
            if (counts.merge(offsets[i], 1, Integer::sum) == 1) live += lengths[i];
        }
        this.liveBytes = live;
        this.sharing = new int[LinearFormat.CHUNKS];
        int small = 0;
        for (int i = 0; i < LinearFormat.CHUNKS; i++) {
            if (offsets[i] == 0) continue;
            this.sharing[i] = counts.get(offsets[i]);
            if (this.sharing[i] == 1 && lengths[i] - LinearFormat.RECORD_HEADER_BYTES <= LinearFormat.PACK_MAX_PAYLOAD) small++;
        }
        this.smallSingles = small;
    }

    /** True when chunk i shares its record with other chunks, that is, lives in a pack. */
    public boolean inPack(int localIndex) {
        return this.sharing[localIndex] > 1;
    }

    public long generation() {
        return this.generation;
    }

    public boolean exists(int localIndex) {
        return this.offsets[localIndex] != 0;
    }

    public long timestamp(int localIndex) {
        return this.timestamps[localIndex];
    }

    public int chunkCount() {
        int n = 0;
        for (long offset : this.offsets) {
            if (offset != 0) n++;
        }
        return n;
    }

    public long liveBytes() {
        return this.liveBytes;
    }

    byte[] encode() {
        final ByteBuffer body = ByteBuffer.allocate(LinearFormat.INDEX_BODY_BYTES);
        for (int i = 0; i < LinearFormat.CHUNKS; i++) {
            body.putLong(this.offsets[i]).putInt(this.offsets[i] == 0 ? 0 : this.lengths[i]).putLong(this.offsets[i] == 0 ? 0 : this.timestamps[i]);
        }
        final byte[] compressed = Zstd.compress(body.array(), LinearFormat.INDEX_COMPRESSION_LEVEL);
        final ByteBuffer out = ByteBuffer.allocate(LinearFormat.INDEX_HEADER_BYTES + compressed.length);
        out.putInt(LinearFormat.INDEX_MAGIC).putLong(this.generation).putInt(LinearFormat.INDEX_BODY_BYTES).putInt(compressed.length)
            .putLong(LinearFormat.xxh64(compressed, 0, compressed.length)).put(compressed);
        return out.array();
    }

    /** Parses an index read from [indexOffset, indexOffset + bytes.length) of a file; null when it is not valid. */
    static @Nullable LinearIndex decode(byte[] bytes, long expectedGeneration, long indexOffset) {
        if (bytes.length < LinearFormat.INDEX_HEADER_BYTES) return null;
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        if (buf.getInt() != LinearFormat.INDEX_MAGIC) return null;
        final long generation = buf.getLong();
        final int rawLength = buf.getInt();
        final int compressedLength = buf.getInt();
        final long checksum = buf.getLong();
        if (generation != expectedGeneration || rawLength != LinearFormat.INDEX_BODY_BYTES || compressedLength != bytes.length - LinearFormat.INDEX_HEADER_BYTES) return null;
        if (LinearFormat.xxh64(bytes, LinearFormat.INDEX_HEADER_BYTES, compressedLength) != checksum) return null;
        final byte[] body = new byte[LinearFormat.INDEX_BODY_BYTES];
        final long result = Zstd.decompressByteArray(body, 0, body.length, bytes, LinearFormat.INDEX_HEADER_BYTES, compressedLength);
        if (Zstd.isError(result) || result != body.length) return null;
        final ByteBuffer entries = ByteBuffer.wrap(body);
        final long[] offsets = new long[LinearFormat.CHUNKS];
        final int[] lengths = new int[LinearFormat.CHUNKS];
        final long[] timestamps = new long[LinearFormat.CHUNKS];
        final Map<Long, Integer> lengthAt = new HashMap<>();
        for (int i = 0; i < LinearFormat.CHUNKS; i++) {
            offsets[i] = entries.getLong();
            lengths[i] = entries.getInt();
            timestamps[i] = entries.getLong();
            if (offsets[i] == 0) continue;
            if (offsets[i] < LinearFormat.LOG_START || offsets[i] > indexOffset || lengths[i] < LinearFormat.RECORD_HEADER_BYTES
                || lengths[i] > indexOffset - offsets[i]) return null;
            // chunks of one pack point at the same record and must agree on its length
            final Integer known = lengthAt.putIfAbsent(offsets[i], lengths[i]);
            if (known != null && known != lengths[i]) return null;
        }
        return new LinearIndex(generation, offsets, lengths, timestamps);
    }
}
