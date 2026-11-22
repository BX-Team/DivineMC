package org.bxteam.divinemc.region.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The golden files in linear-format/golden must read to what manifest.json says. Every implementation runs the same
 * check; this one is the reference. The environment variable {@code LINEAR_GOLDEN_REGENERATE} rewrites the files and the manifest.
 */
@Normal
@NullMarked
class LinearGoldenFilesTest {
    private static final long TIME = 1_763_683_200_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static Path goldenDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("linear-format"))) dir = dir.getParent();
        assertNotNull(dir, "linear-format directory not found above the working directory");
        return dir.resolve("linear-format/golden");
    }

    @Test
    void goldenFilesReadAsTheManifestSays() throws IOException {
        final Path dir = goldenDir();
        if (System.getenv("LINEAR_GOLDEN_REGENERATE") != null) {
            regenerate(dir);
        }
        final LinearDictionaries dicts = LinearDictionaries.shipped();
        final JsonObject manifest = GSON.fromJson(Files.readString(dir.resolve("manifest.json")), JsonObject.class);
        for (var element : manifest.getAsJsonArray("files")) {
            final JsonObject spec = element.getAsJsonObject();
            final String name = spec.get("file").getAsString();
            final Path path = dir.resolve(name);
            final String open = spec.get("open").getAsString();
            switch (open) {
                case "legacy" -> assertThrows(LinearFormatException.Legacy.class, () -> LinearFile.openReadOnly(path, dicts), name);
                case "error" -> assertThrows(LinearFormatException.class, () -> LinearFile.openReadOnly(path, dicts), name);
                case "ok" -> {
                    try (LinearFile file = LinearFile.openReadOnly(path, dicts)) {
                        final Map<Integer, JsonObject> chunks = new TreeMap<>();
                        for (var c : spec.getAsJsonArray("chunks")) chunks.put(c.getAsJsonObject().get("index").getAsInt(), c.getAsJsonObject());
                        for (int i = 0; i < LinearFormat.CHUNKS; i++) {
                            final JsonObject expected = chunks.get(i);
                            if (expected == null) {
                                assertNull(file.read(i), name + " chunk " + i);
                                continue;
                            }
                            assertEquals(expected.get("timestamp").getAsLong(), file.index().timestamp(i), name + " chunk " + i);
                            if (expected.has("error")) {
                                final int index = i;
                                assertThrows(LinearFormatException.class, () -> file.read(index), name + " chunk " + i);
                                continue;
                            }
                            final int index = i;
                            final byte[] raw = assertDoesNotThrow(() -> file.read(index), name + " chunk " + i);
                            assertNotNull(raw, name + " chunk " + i);
                            assertEquals(expected.get("length").getAsInt(), raw.length, name + " chunk " + i);
                            assertEquals(expected.get("sha256").getAsString(), LinearDictionaries.sha256(raw), name + " chunk " + i);
                        }
                    }
                }
                default -> fail("unknown expectation " + open);
            }
        }
    }

    // ---- generation ----

    private static byte[] payload(long seed, int size) {
        return LinearFileTest.payload(new Random(seed), size);
    }

    private record Expect(int index, byte @Nullable [] raw, long timestamp, boolean error, int length, @Nullable String sha256) {
        Expect(int index, byte[] raw, long timestamp, boolean error) {
            this(index, raw, timestamp, error, raw.length, LinearDictionaries.sha256(raw));
        }
    }

    private static void regenerate(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (var old = Files.list(dir)) {
            for (Path p : old.toList()) {
                // files of older format versions cannot be written any more; they are kept as they are
                if (!p.getFileName().toString().startsWith("format1-")) Files.delete(p);
            }
        }
        final JsonArray files = new JsonArray();
        final LinearDictionaries none = LinearDictionaries.none();

        // written by Linear 1.0 from history(): readers of later versions must still read it
        files.add(entry("format1-history.linear", "ok", expected(history(), history().size())));

        write(dir, "empty.linear", none, List.of(), files);

        final Path shortFile = dir.resolve("cut-off-at-creation.linear");
        Files.write(shortFile, Arrays.copyOf(LinearFormat.header(0, 0), 40));
        files.add(entry("cut-off-at-creation.linear", "ok", List.of()));

        write(dir, "sparse.linear", none, List.of(Map.of(0, payload(1, 3000), 33, payload(2, 10), 1023, payload(3, 40000))), files);

        final Map<Integer, byte @Nullable []> full = new TreeMap<>();
        for (int i = 0; i < LinearFormat.CHUNKS; i++) full.put(i, payload(100 + i, 50 + i));
        write(dir, "full.linear", none, List.of(full), files);

        write(dir, "oversized.linear", none, List.of(Map.of(7, payload(4, 2 * 1024 * 1024 + 17))), files);

        final List<Map<Integer, byte @Nullable []>> history = history();
        write(dir, "history.linear", none, history, files);

        final byte[] historyBytes = Files.readAllBytes(dir.resolve("history.linear"));
        final byte[] tornTail = Arrays.copyOf(historyBytes, historyBytes.length + 150);
        final byte[] partial = ChunkRecord.encode(5, payload(9, 5000), 0, 5000, TIME, 0, 3, none);
        System.arraycopy(partial, 0, tornTail, historyBytes.length, 150);
        Files.write(dir.resolve("torn-tail.linear"), tornTail);
        files.add(entry("torn-tail.linear", "ok", expected(history, history.size())));

        // the newest slot is half written: the previous generation is the state
        final byte[] tornSlot = historyBytes.clone();
        final int newest = newestSlotOffset(tornSlot);
        Arrays.fill(tornSlot, newest + 8, newest + LinearFormat.SLOT_BYTES, (byte) 0x5A);
        Files.write(dir.resolve("torn-slot.linear"), tornSlot);
        files.add(entry("torn-slot.linear", "ok", expected(history, history.size() - 1)));

        // both slots damaged: salvage takes the newest record of every chunk, so deleted chunks come back
        final byte[] damaged = historyBytes.clone();
        Arrays.fill(damaged, LinearFormat.SLOT_A_OFFSET + 8, LinearFormat.SLOT_A_OFFSET + 16, (byte) 0x33);
        Arrays.fill(damaged, LinearFormat.SLOT_B_OFFSET + 8, LinearFormat.SLOT_B_OFFSET + 16, (byte) 0x44);
        Files.write(dir.resolve("damaged-slots.linear"), damaged);
        files.add(entry("damaged-slots.linear", "ok", salvagedTimestamps(dir.resolve("damaged-slots.linear"), salvaged(history))));

        // the first flush of a new file was cut before its slot: the records are in the log, both slots are empty
        final byte[] firstFlush = Files.readAllBytes(dir.resolve("sparse.linear"));
        Arrays.fill(firstFlush, LinearFormat.SLOT_A_OFFSET, LinearFormat.LOG_START, (byte) 0);
        Files.write(dir.resolve("first-flush-cut.linear"), firstFlush);
        files.add(entry("first-flush-cut.linear", "ok", salvagedTimestamps(dir.resolve("first-flush-cut.linear"), expectedOf(dir.resolve("sparse.linear"), files))));

        final Path compacted = dir.resolve("compacted.linear");
        Files.write(compacted, historyBytes);
        try (LinearFile file = LinearFile.open(compacted, 0, 0, none)) {
            assertTrue(file.compact());
        }
        files.add(entry("compacted.linear", "ok", expected(history, history.size())));

        // many small chunks, as in void worlds: packs, plus one chunk too large for a pack
        final Map<Integer, byte @Nullable []> small = new TreeMap<>();
        for (int i = 0; i < 300; i++) small.put(i * 3, voidLike(i));
        small.put(1000, payload(5, 60000));
        write(dir, "packed.linear", none, List.of(small), files);

        // a pack whose payload is damaged: every chunk in it is an error, the others read
        {
            final Path path = dir.resolve("bad-pack.linear");
            final byte[][] records = new byte[LinearFormat.CHUNKS][];
            final List<Expect> expect = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                final byte[] raw = voidLike(i);
                records[i] = ChunkRecord.encode(i, raw, 0, raw.length, TIME, 0, 3, none);
                expect.add(new Expect(i, raw, TIME, true));
            }
            final byte[] good = payload(500, 30000);
            records[20] = ChunkRecord.encode(20, good, 0, good.length, TIME + 1, 0, 3, none);
            expect.add(new Expect(20, good, TIME + 1, false));
            final int packAt;
            try (LinearFile file = LinearFile.open(path, 0, 0, none)) {
                file.publish(records, new boolean[LinearFormat.CHUNKS]);
                packAt = (int) file.index().offsets[0];
            }
            final byte[] bytes = Files.readAllBytes(path);
            assertEquals(LinearFormat.PACK_MAGIC, ByteBuffer.wrap(bytes).getInt(packAt));
            bytes[packAt + LinearFormat.RECORD_HEADER_BYTES + 3] ^= 0x10;
            Files.write(path, bytes);
            files.add(entry("bad-pack.linear", "ok", expect));
        }

        // one file per shipped dictionary, so a dictionary stays covered after newer ones are added
        final LinearDictionaries shipped = LinearDictionaries.shipped();
        for (int dictId : shipped.ids()) {
            final String name = "dictionary-" + dictId + ".linear";
            final byte[][] records = new byte[LinearFormat.CHUNKS][];
            final List<Expect> expect = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final byte[] raw = payload(200L * dictId + i, 4000);
                records[i] = ChunkRecord.encode(i, raw, 0, raw.length, TIME + i, dictId, 6, shipped);
                expect.add(new Expect(i, raw, TIME + i, false));
            }
            try (LinearFile file = LinearFile.open(dir.resolve(name), 0, 0, shipped)) {
                file.publish(records, new boolean[LinearFormat.CHUNKS]);
            }
            files.add(entry(name, "ok", expect));
        }

        // a record naming a dictionary nobody has: that chunk is an error, the rest reads
        {
            final Path path = dir.resolve("unknown-dictionary.linear");
            final byte[][] records = new byte[LinearFormat.CHUNKS][];
            final byte[] good = payload(300, 1000), bad = payload(301, 1000);
            records[0] = ChunkRecord.encode(0, good, 0, good.length, TIME, 0, 3, none);
            records[1] = ChunkRecord.encode(1, bad, 0, bad.length, TIME + 1, 0, 3, none);
            ByteBuffer.wrap(records[1]).putInt(8, 0x7FFFFFF0);
            try (LinearFile file = LinearFile.open(path, 0, 0, none)) {
                file.publish(records, new boolean[LinearFormat.CHUNKS]);
            }
            files.add(entry("unknown-dictionary.linear", "ok", List.of(new Expect(0, good, TIME, false), new Expect(1, bad, TIME + 1, true))));
        }

        // a flipped payload bit: that chunk is an error, the rest reads
        {
            final Path path = dir.resolve("bad-checksum.linear");
            final byte[][] records = new byte[LinearFormat.CHUNKS][];
            // large enough to stay records of their own; bad-pack.linear covers a damaged pack
            final byte[] good = payload(400, 30000), bad = payload(401, 30000);
            records[0] = ChunkRecord.encode(0, bad, 0, bad.length, TIME, 0, 3, none);
            records[1] = ChunkRecord.encode(1, good, 0, good.length, TIME + 1, 0, 3, none);
            try (LinearFile file = LinearFile.open(path, 0, 0, none)) {
                file.publish(records, new boolean[LinearFormat.CHUNKS]);
            }
            final byte[] bytes = Files.readAllBytes(path);
            bytes[LinearFormat.LOG_START + LinearFormat.RECORD_HEADER_BYTES + 20] ^= 0x10;
            Files.write(path, bytes);
            files.add(entry("bad-checksum.linear", "ok", List.of(new Expect(0, bad, TIME, true), new Expect(1, good, TIME + 1, false))));
        }

        Files.write(dir.resolve("legacy.linear"), ByteBuffer.allocate(256).putLong(LinearFormat.LEGACY_MAGIC).put((byte) 3).array());
        files.add(entry("legacy.linear", "legacy", List.of()));

        final byte[] future = Arrays.copyOf(LinearFormat.header(0, 0), LinearFormat.LOG_START);
        future[9] = (byte) (LinearFormat.MAJOR_VERSION + 1);
        ByteBuffer.wrap(future).putLong(24, LinearFormat.xxh64(future, 0, 24));
        Files.write(dir.resolve("future-major.linear"), future);
        files.add(entry("future-major.linear", "error", List.of()));

        final JsonObject manifest = new JsonObject();
        manifest.addProperty("format", "Linear " + LinearFormat.MAJOR_VERSION + "." + LinearFormat.MINOR_VERSION);
        manifest.addProperty("note", "open: ok | legacy (must be refused as a legacy layout) | error (must be refused). Chunks not listed must not exist; a chunk with error must exist (its timestamp is in the index) but fail to read.");
        manifest.add("files", files);
        Files.writeString(dir.resolve("manifest.json"), GSON.toJson(manifest) + "\n");
    }

    /** About the size and shape of a chunk of a void world: thousands of bytes that compress to a few hundred. */
    private static byte[] voidLike(int seed) {
        final byte[] out = new byte[4000];
        for (int i = 0; i < out.length; i++) out[i] = (byte) (i % 97 == 0 ? 1 : 0);
        ByteBuffer.wrap(out).putInt(0, seed).putInt(8, seed * 31);
        return out;
    }

    private static List<Map<Integer, byte @Nullable []>> history() {
        final List<Map<Integer, byte @Nullable []>> steps = new ArrayList<>();
        for (int step = 0; step < 5; step++) {
            final Map<Integer, byte @Nullable []> changes = new TreeMap<>();
            for (int i = 0; i < 8; i++) {
                final int index = (step * 3 + i * 7) % 20;
                changes.put(index, (step + i) % 6 == 5 ? null : payload(1000L * step + i, 200 + 300 * i));
            }
            steps.add(changes);
        }
        return steps;
    }

    private static List<Expect> expected(List<Map<Integer, byte @Nullable []>> steps, int count) {
        final Map<Integer, Expect> state = new TreeMap<>();
        for (int step = 0; step < count; step++) {
            for (Map.Entry<Integer, byte @Nullable []> e : steps.get(step).entrySet()) {
                if (e.getValue() == null) state.remove(e.getKey());
                else state.put(e.getKey(), new Expect(e.getKey(), e.getValue(), TIME + step, false));
            }
        }
        return new ArrayList<>(state.values());
    }

    private static List<Expect> salvaged(List<Map<Integer, byte @Nullable []>> steps) {
        final Map<Integer, Expect> state = new TreeMap<>();
        for (int step = 0; step < steps.size(); step++) {
            for (Map.Entry<Integer, byte @Nullable []> e : steps.get(step).entrySet()) {
                if (e.getValue() != null) state.put(e.getKey(), new Expect(e.getKey(), e.getValue(), TIME + step, false));
            }
        }
        return new ArrayList<>(state.values());
    }

    /** Salvage gives a chunk in a pack the pack's timestamp, the newest of its chunks when it was written. */
    private static List<Expect> salvagedTimestamps(Path file, List<Expect> expect) throws IOException {
        final List<Expect> out = new ArrayList<>();
        try (LinearFile linear = LinearFile.openReadOnly(file, LinearDictionaries.none())) {
            for (Expect e : expect) {
                final long timestamp = linear.index().timestamp(e.index);
                assertTrue(timestamp >= e.timestamp, file + " chunk " + e.index);
                out.add(new Expect(e.index, e.raw, timestamp, e.error, e.length, e.sha256));
            }
        }
        return out;
    }

    private static List<Expect> expectedOf(Path written, JsonArray files) {
        for (var element : files) {
            final JsonObject o = element.getAsJsonObject();
            if (o.get("file").getAsString().equals(written.getFileName().toString())) {
                final List<Expect> out = new ArrayList<>();
                for (var c : o.getAsJsonArray("chunks")) {
                    final JsonObject chunk = c.getAsJsonObject();
                    out.add(new Expect(chunk.get("index").getAsInt(), null, chunk.get("timestamp").getAsLong(), false, chunk.get("length").getAsInt(), chunk.get("sha256").getAsString()));
                }
                return out;
            }
        }
        throw new IllegalStateException(written + " is not in the manifest yet");
    }

    private static void write(Path dir, String name, LinearDictionaries dicts, List<Map<Integer, byte @Nullable []>> steps, JsonArray files) throws IOException {
        final Path path = dir.resolve(name);
        try (LinearFile file = LinearFile.open(path, 0, 0, dicts)) {
            if (steps.isEmpty()) {
                // an empty file as a writer creates it: header and two empty slots
                final byte[] head = Arrays.copyOf(LinearFormat.header(0, 0), LinearFormat.LOG_START);
                Files.write(path, head);
            }
            for (int step = 0; step < steps.size(); step++) {
                final byte[][] records = new byte[LinearFormat.CHUNKS][];
                final boolean[] deleted = new boolean[LinearFormat.CHUNKS];
                for (Map.Entry<Integer, byte @Nullable []> e : steps.get(step).entrySet()) {
                    if (e.getValue() == null) deleted[e.getKey()] = true;
                    else records[e.getKey()] = ChunkRecord.encode(e.getKey(), e.getValue(), 0, e.getValue().length, TIME + step, 0, 3, dicts);
                }
                file.publish(records, deleted);
            }
        }
        files.add(entry(name, "ok", expected(steps, steps.size())));
    }

    private static int newestSlotOffset(byte[] file) {
        final long a = ByteBuffer.wrap(file).getLong(LinearFormat.SLOT_A_OFFSET);
        final long b = ByteBuffer.wrap(file).getLong(LinearFormat.SLOT_B_OFFSET);
        return a > b ? LinearFormat.SLOT_A_OFFSET : LinearFormat.SLOT_B_OFFSET;
    }

    private static JsonObject entry(String file, String open, List<Expect> chunks) {
        final JsonObject o = new JsonObject();
        o.addProperty("file", file);
        o.addProperty("open", open);
        final JsonArray list = new JsonArray();
        for (Expect e : chunks) {
            final JsonObject c = new JsonObject();
            c.addProperty("index", e.index);
            c.addProperty("timestamp", e.timestamp);
            if (e.error) {
                c.addProperty("error", true);
            } else {
                c.addProperty("length", e.length);
                c.addProperty("sha256", e.sha256);
            }
            list.add(c);
        }
        o.add("chunks", list);
        return o;
    }
}
