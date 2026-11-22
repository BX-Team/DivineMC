package org.bxteam.divinemc.region.format;

import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The zstd dictionaries a Linear file may reference by ID (SPEC.md, "Dictionaries"). */
@NullMarked
public final class LinearDictionaries {
    public static final String RESOURCE_DIR = "linear/dicts/";
    private static final Pattern FILE_NAME = Pattern.compile("^([0-9]+)-([a-z_]+)-([^/]+)\\.zdict$");
    private static final int ZSTD_DICT_MAGIC = 0xEC30A437;
    private static @Nullable LinearDictionaries shipped;

    private final Map<Integer, byte[]> dictionaries;
    private final Map<String, Integer> newestByType;
    private final Map<Long, ZstdDictCompress> compressors = new ConcurrentHashMap<>();
    private final Map<Integer, ZstdDictDecompress> decompressors = new ConcurrentHashMap<>();

    public LinearDictionaries(Map<Integer, byte[]> dictionaries, Map<String, Integer> newestByType) {
        for (Map.Entry<Integer, byte[]> e : dictionaries.entrySet()) {
            if (e.getKey() == 0 || dictIdOf(e.getValue()) != e.getKey()) {
                throw new IllegalArgumentException("dictionary " + e.getKey() + " carries ID " + dictIdOf(e.getValue()));
            }
        }
        this.dictionaries = Map.copyOf(dictionaries);
        this.newestByType = Map.copyOf(newestByType);
    }

    public static LinearDictionaries none() {
        return new LinearDictionaries(Map.of(), Map.of());
    }

    /** The dictionaries in the server jar, each checked against linear/dicts/SHA256SUMS. */
    public static synchronized LinearDictionaries shipped() {
        if (shipped == null) {
            try {
                shipped = loadShipped();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load the Linear dictionaries", e);
            }
        }
        return shipped;
    }

    private static LinearDictionaries loadShipped() throws IOException {
        final ClassLoader loader = LinearDictionaries.class.getClassLoader();
        final Map<Integer, byte[]> dicts = new HashMap<>();
        final Map<String, Integer> newest = new HashMap<>();
        try (InputStream sums = loader.getResourceAsStream(RESOURCE_DIR + "SHA256SUMS")) {
            if (sums == null) {
                return none();
            }
            final BufferedReader reader = new BufferedReader(new InputStreamReader(sums, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                final String[] parts = line.split("\\s+");
                final Matcher m = FILE_NAME.matcher(parts[1]);
                if (!m.matches()) {
                    throw new IOException("Unexpected dictionary file name " + parts[1]);
                }
                final byte[] bytes;
                try (InputStream in = loader.getResourceAsStream(RESOURCE_DIR + parts[1])) {
                    if (in == null) {
                        throw new IOException("Dictionary " + parts[1] + " is listed but missing");
                    }
                    bytes = in.readAllBytes();
                }
                final String actual = sha256(bytes);
                if (!actual.equalsIgnoreCase(parts[0])) {
                    throw new IOException("Dictionary " + parts[1] + " has SHA-256 " + actual + ", expected " + parts[0]);
                }
                final int id = Integer.parseInt(m.group(1));
                dicts.put(id, bytes);
                newest.merge(m.group(2), id, Math::max);
            }
        }
        return new LinearDictionaries(dicts, newest);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int dictIdOf(byte[] dictionary) {
        if (dictionary.length < 8) return 0;
        final ByteBuffer buf = ByteBuffer.wrap(dictionary).order(ByteOrder.LITTLE_ENDIAN);
        return buf.getInt(0) == ZSTD_DICT_MAGIC ? buf.getInt(4) : 0;
    }

    /** The dictionary new chunks of a data type (the region folder name) are written with, or 0. */
    public int newestFor(String type) {
        return this.newestByType.getOrDefault(type, 0);
    }

    /** Every dictionary ID this build has, ascending. */
    public int[] ids() {
        return this.dictionaries.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    public boolean has(int id) {
        return this.dictionaries.containsKey(id);
    }

    ZstdDictCompress compressor(int id, int level) {
        return this.compressors.computeIfAbsent(((long) id << 32) | (level & 0xFFFFFFFFL), k -> new ZstdDictCompress(this.dictionaries.get(id), level));
    }

    @Nullable ZstdDictDecompress decompressor(int id) {
        final byte[] dict = this.dictionaries.get(id);
        if (dict == null) return null;
        return this.decompressors.computeIfAbsent(id, k -> new ZstdDictDecompress(dict));
    }
}
