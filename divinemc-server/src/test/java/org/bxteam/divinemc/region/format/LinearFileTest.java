package org.bxteam.divinemc.region.format;

import com.github.luben.zstd.ZstdDictTrainer;
import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class LinearFileTest {
    private static final LinearDictionaries NONE = LinearDictionaries.none();

    @TempDir
    Path dir;

    static byte[] payload(Random random, int size) {
        // NBT-like: repetitive with some noise, so it compresses like chunk data
        final byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (random.nextInt(8) == 0 ? random.nextInt(256) : (i % 61));
        }
        return out;
    }

    static byte[] record(int index, byte[] raw, long timestamp, int dictId, LinearDictionaries dicts) throws IOException {
        return ChunkRecord.encode(index, raw, 0, raw.length, timestamp, dictId, 3, dicts);
    }

    private static void publish(LinearFile file, Map<Integer, byte @Nullable []> changes, LinearDictionaries dicts) throws IOException {
        final byte[][] records = new byte[LinearFormat.CHUNKS][];
        final boolean[] deleted = new boolean[LinearFormat.CHUNKS];
        for (Map.Entry<Integer, byte @Nullable []> e : changes.entrySet()) {
            if (e.getValue() == null) {
                deleted[e.getKey()] = true;
            } else {
                records[e.getKey()] = record(e.getKey(), e.getValue(), 1_000L + e.getKey(), 0, dicts);
            }
        }
        file.publish(records, deleted);
    }

    @Test
    void roundTripsAcrossReopenDeleteAndCompaction() throws IOException {
        final Path path = this.dir.resolve("r.1.-2.linear");
        final Random random = new Random(1);
        final byte[] a = payload(random, 5000), b = payload(random, 70000), c = payload(random, 10);
        try (LinearFile file = LinearFile.open(path, 1, -2, NONE)) {
            assertNull(file.read(0));
            publish(file, Map.of(0, a, 1023, b), NONE);
            publish(file, Map.of(0, c), NONE);
            assertArrayEquals(c, file.read(0));
        }
        try (LinearFile file = LinearFile.open(path, 1, -2, NONE)) {
            assertTrue(file.takeWarnings().isEmpty());
            assertArrayEquals(c, file.read(0));
            assertArrayEquals(b, file.read(1023));
            assertEquals(1023 + 1_000L, file.index().timestamp(1023));
            final java.util.HashMap<Integer, byte @Nullable []> delete = new java.util.HashMap<>();
            delete.put(1023, null);
            publish(file, delete, NONE);
            assertNull(file.read(1023));
            assertTrue(file.garbageBytes() > 0);
            assertTrue(file.compact());
            assertEquals(0, file.garbageBytes());
            assertEquals(LinearFormat.LOG_START + file.liveBytes() + (file.fileBytes() - LinearFormat.LOG_START - file.liveBytes()), file.fileBytes());
        }
        try (LinearFile file = LinearFile.open(path, 1, -2, NONE)) {
            assertArrayEquals(c, file.read(0));
            assertNull(file.read(1023));
            assertFalse(Files.exists(path.resolveSibling(path.getFileName() + ".compact")));
        }
    }

    @Test
    void chunksOverAnvilSectorLimitAreOrdinaryRecords() throws IOException {
        final Path path = this.dir.resolve("r.0.0.linear");
        final byte[] big = payload(new Random(2), 3 * 1024 * 1024);
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            publish(file, Map.of(5, big), NONE);
        }
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            assertArrayEquals(big, file.read(5));
        }
    }

    @Test
    void refusesLegacyAndForeignFiles() throws IOException {
        final Path legacy = this.dir.resolve("r.0.0.linear");
        Files.write(legacy, ByteBuffer.allocate(200).putLong(LinearFormat.LEGACY_MAGIC).array());
        assertThrows(LinearFormatException.Legacy.class, () -> LinearFile.open(legacy, 0, 0, NONE));

        final Path foreign = this.dir.resolve("r.0.1.linear");
        Files.write(foreign, new byte[200]);
        assertThrows(LinearFormatException.class, () -> LinearFile.open(foreign, 0, 1, NONE));

        final Path future = this.dir.resolve("r.0.2.linear");
        final byte[] head = LinearFormat.header(0, 2);
        head[9] = 2;
        final byte[] rehashed = Arrays.copyOf(head, LinearFormat.LOG_START);
        ByteBuffer.wrap(rehashed).putLong(24, LinearFormat.xxh64(rehashed, 0, 24));
        Files.write(future, rehashed);
        final LinearFormatException e = assertThrows(LinearFormatException.class, () -> LinearFile.open(future, 0, 2, NONE));
        assertTrue(e.getMessage().contains("format 2"), e.getMessage());
    }

    @Test
    void fileCutOffWhileCreatedIsEmptyAndRewritten() throws IOException {
        final Path path = this.dir.resolve("r.0.0.linear");
        Files.write(path, Arrays.copyOf(LinearFormat.header(0, 0), 40));
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            assertNull(file.read(0));
            publish(file, Map.of(0, new byte[]{1, 2, 3}), NONE);
        }
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            assertArrayEquals(new byte[]{1, 2, 3}, file.read(0));
        }
    }

    @Test
    void corruptRecordIsAnErrorNotAMissingChunk() throws IOException {
        final Path path = this.dir.resolve("r.0.0.linear");
        final Random random = new Random(3);
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            publish(file, Map.of(0, payload(random, 3000), 1, payload(random, 3000)), NONE);
        }
        final byte[] bytes = Files.readAllBytes(path);
        bytes[LinearFormat.LOG_START + LinearFormat.RECORD_HEADER_BYTES + 10] ^= 0x40;
        Files.write(path, bytes);
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            assertThrows(LinearFormatException.class, () -> file.read(0));
            assertTrue(file.index().exists(1));
            file.read(1);
        }
    }

    @Test
    void dictionaryRecordsNeedTheirDictionary() throws IOException {
        final Random random = new Random(4);
        final ZstdDictTrainer trainer = new ZstdDictTrainer(1 << 20, 4096);
        for (int i = 0; i < 200; i++) trainer.addSample(payload(random, 2000));
        final byte[] dict = withId(trainer.trainSamples(), 7);
        final LinearDictionaries dicts = new LinearDictionaries(Map.of(7, dict), Map.of("region", 7));
        assertEquals(7, dicts.newestFor("region"));
        assertEquals(0, dicts.newestFor("poi"));

        final Path path = this.dir.resolve("r.0.0.linear");
        final byte[] raw = payload(random, 2000);
        try (LinearFile file = LinearFile.open(path, 0, 0, dicts)) {
            final byte[][] records = new byte[LinearFormat.CHUNKS][];
            records[3] = record(3, raw, 1, 7, dicts);
            file.publish(records, new boolean[LinearFormat.CHUNKS]);
        }
        try (LinearFile file = LinearFile.open(path, 0, 0, dicts)) {
            assertArrayEquals(raw, file.read(3));
        }
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            final LinearFormatException e = assertThrows(LinearFormatException.class, () -> file.read(3));
            assertTrue(e.getMessage().contains("dictionary 7"), e.getMessage());
        }
    }

    @Test
    void lostSlotsAreSalvagedFromTheLog() throws IOException {
        final Path path = this.dir.resolve("r.0.0.linear");
        final Random random = new Random(6);
        final byte[] a1 = payload(random, 2000), a2 = payload(random, 2100), b = payload(random, 900);
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            final byte[][] records = new byte[LinearFormat.CHUNKS][];
            records[0] = record(0, a1, 10, 0, NONE);
            records[9] = record(9, b, 10, 0, NONE);
            file.publish(records, new boolean[LinearFormat.CHUNKS]);
            final byte[][] second = new byte[LinearFormat.CHUNKS][];
            second[0] = record(0, a2, 20, 0, NONE);
            file.publish(second, new boolean[LinearFormat.CHUNKS]);
        }
        final byte[] bytes = Files.readAllBytes(path);
        Arrays.fill(bytes, LinearFormat.SLOT_A_OFFSET + 24, LinearFormat.SLOT_A_OFFSET + 32, (byte) 1);
        Arrays.fill(bytes, LinearFormat.SLOT_B_OFFSET + 24, LinearFormat.SLOT_B_OFFSET + 32, (byte) 1);
        Files.write(path, bytes);
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            assertEquals(LinearFile.Salvage.DAMAGED_SLOTS, file.salvage());
            assertArrayEquals(a2, file.read(0), "the newest record wins");
            assertArrayEquals(b, file.read(9));
            file.publishSalvaged();
        }
        assertTrue(Files.exists(path.resolveSibling("r.0.0.linear.corrupt")));
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            assertEquals(LinearFile.Salvage.NONE, file.salvage());
            assertArrayEquals(a2, file.read(0));
            assertArrayEquals(b, file.read(9));
        }

        // cut during the first flush: no slot was ever written, nothing is kept aside
        final Path fresh = this.dir.resolve("r.1.0.linear");
        try (LinearFile file = LinearFile.open(fresh, 1, 0, NONE)) {
            final byte[][] records = new byte[LinearFormat.CHUNKS][];
            records[4] = record(4, b, 10, 0, NONE);
            file.publish(records, new boolean[LinearFormat.CHUNKS]);
        }
        final byte[] cut = Files.readAllBytes(fresh);
        Arrays.fill(cut, LinearFormat.SLOT_A_OFFSET, LinearFormat.LOG_START, (byte) 0);
        Files.write(fresh, cut);
        try (LinearFile file = LinearFile.open(fresh, 1, 0, NONE)) {
            assertEquals(LinearFile.Salvage.FIRST_FLUSH_CUT, file.salvage());
            assertArrayEquals(b, file.read(4));
            file.publishSalvaged();
        }
        assertFalse(Files.exists(fresh.resolveSibling("r.1.0.linear.corrupt")));
    }

    static byte[] withId(byte[] dictionary, int id) {
        final byte[] out = dictionary.clone();
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, id);
        return out;
    }

    /**
     * Random publishes, each followed by a simulated crash somewhere inside it: the appended bytes cut at a random
     * length, and the slot either not written, written, or torn. Reopening must always give the state of the last
     * publish whose slot reached the disk intact, never a mix and never an error.
     */
    @Test
    void everyCrashPointReopensToAPublishedState() throws IOException {
        final Random random = new Random(5);
        final Path path = this.dir.resolve("r.0.0.linear");
        byte[][] durable = new byte[LinearFormat.CHUNKS][];
        for (int step = 0; step < 300; step++) {
            final byte[] before = Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
            final byte[][] next = durable.clone();
            final java.util.HashMap<Integer, byte @Nullable []> changes = new java.util.HashMap<>();
            for (int n = random.nextInt(6) + 1; n > 0; n--) {
                final int index = random.nextInt(40);
                final byte @Nullable [] value = random.nextInt(5) == 0 ? null : payload(random, 1 + random.nextInt(3000));
                changes.put(index, value);
                next[index] = value;
            }
            boolean compacted = false;
            try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
                assertState(durable, file);
                publish(file, changes, NONE);
                if (random.nextInt(25) == 0) compacted = file.compact();
            }
            final byte[] after = Files.readAllBytes(path);
            if (before.length < LinearFormat.LOG_START || compacted) {
                durable = next;
                continue;
            }
            final int cut = before.length + random.nextInt(after.length - before.length + 1);
            final byte[] crashed = Arrays.copyOf(after, cut);
            final int outcome = cut < after.length ? 0 : random.nextInt(3);
            final int changedSlot = Arrays.equals(before, LinearFormat.SLOT_A_OFFSET, LinearFormat.SLOT_B_OFFSET, after, LinearFormat.SLOT_A_OFFSET, LinearFormat.SLOT_B_OFFSET)
                ? LinearFormat.SLOT_B_OFFSET : LinearFormat.SLOT_A_OFFSET;
            if (outcome != 1) {
                System.arraycopy(before, changedSlot, crashed, changedSlot, LinearFormat.SLOT_BYTES);
            }
            if (outcome == 2) {
                final int tear = 1 + random.nextInt(LinearFormat.SLOT_BYTES - 1);
                System.arraycopy(after, changedSlot, crashed, changedSlot, tear);
            }
            Files.write(path, crashed);
            if (outcome == 1) durable = next;
        }
        try (LinearFile file = LinearFile.open(path, 0, 0, NONE)) {
            assertState(durable, file);
        }
    }

    private static void assertState(byte[][] expected, LinearFile file) throws IOException {
        for (int i = 0; i < LinearFormat.CHUNKS; i++) {
            final byte[] actual = file.read(i);
            if (expected[i] == null) {
                assertNull(actual, "chunk " + i);
            } else {
                assertArrayEquals(expected[i], actual, "chunk " + i);
            }
        }
    }
}
