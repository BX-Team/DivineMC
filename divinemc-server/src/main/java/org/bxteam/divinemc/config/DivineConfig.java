package org.bxteam.divinemc.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.async.pathfinding.PathfindTaskRejectPolicy;
import org.bxteam.divinemc.config.annotations.Experimental;
import org.bxteam.divinemc.region.RegionFormat;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.comments.CommentType;
import org.simpleyaml.configuration.file.YamlFile;
import su.plo.matter.HashingVersion;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

@NullMarked
@SuppressWarnings({"SameParameterValue"})
public class DivineConfig {
    private static final String HEADER = """
        This is the main configuration file for DivineMC.
        If you need help with the configuration or have any questions related to DivineMC,
        join us in our Discord server.
        
        Discord: https://discord.gg/qNyybSSPm5
        Docs: https://bxteam.org/docs/divinemc
        Downloads: https://bxteam.org/downloads/divinemc""";

    public static final Logger LOGGER = LogManager.getLogger(DivineConfig.class.getSimpleName());
    public static final int CONFIG_VERSION = 9;
    public static int configVersion;

    private static final int maxThreads = Runtime.getRuntime().availableProcessors();

    private static File configFile;
    static YamlFile config = new YamlFile();
    private static boolean loaded;
    private static boolean readingRestartOnly;
    private static final Map<String, Object> restartOnlyValues = new LinkedHashMap<>();

    public static void init(File configFile) {
        if (loaded) {
            reload();
            return;
        }

        long begin = System.nanoTime();
        LOGGER.info("Loading config...");
        DivineConfig.configFile = configFile;
        try {
            config = read(configFile);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load " + configFile.getName() + ", please correct your syntax errors", e);
        }
        load();
        save();
        LOGGER.info("Config loaded in {}ms", (System.nanoTime() - begin) / 1_000_000);
    }

    public static boolean reload() {
        final YamlFile reloaded;
        try {
            reloaded = read(configFile);
        } catch (IOException e) {
            LOGGER.error("Could not reload {}, keeping the current settings", configFile.getName(), e);
            return false;
        }
        config = reloaded;
        load();
        save();
        return true;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    private static YamlFile read(File file) throws IOException {
        final YamlFile yaml = new YamlFile();
        if (file.exists()) {
            yaml.load(file);
        }
        yaml.options().header(HEADER);
        return yaml;
    }

    private static void load() {
        configVersion = config.getInt("version", CONFIG_VERSION);
        removedOptions();
        AsyncCategory.load();
        PerformanceCategory.load();
        FixesCategory.load();
        NetworkCategory.load();
        MiscCategory.load();
        warnChangedRestartOnly();
        config.set("version", CONFIG_VERSION);
        loaded = true;
        checkExperimentalFeatures();
    }

    static void save() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            LOGGER.error("Failed to save {}", configFile.getName(), e);
        }
    }

    private static void restartOnly(Runnable reader) {
        if (loaded) {
            return;
        }
        readingRestartOnly = true;
        try {
            reader.run();
        } finally {
            readingRestartOnly = false;
        }
    }

    private static void warnChangedRestartOnly() {
        if (!loaded) {
            return;
        }
        final List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> entry : restartOnlyValues.entrySet()) {
            final Object current = config.get(entry.getKey(), null);
            if (current == null) {
                config.set(entry.getKey(), entry.getValue());
            } else if (!String.valueOf(current).equals(String.valueOf(entry.getValue()))) {
                changed.add(entry.getKey());
            }
        }
        if (!changed.isEmpty()) {
            LOGGER.warn("These options only change on restart: {}", String.join(", ", changed));
        }
    }

    private static void setComment(String key, String... comment) {
        if (config.contains(key)) {
            config.setComment(key, String.join("\n", comment), CommentType.BLOCK);
        }
    }

    private static void ensureDefault(String key, Object defaultValue, String... comment) {
        ensureDefault(key, null, defaultValue, comment);
    }

    private static void ensureDefault(String key, @Nullable String oldKey, Object defaultValue, String... comment) {
        if (oldKey != null && config.contains(oldKey)) {
            if (!config.contains(key)) {
                config.set(key, config.get(oldKey));
                LOGGER.info("Migrated config option {} -> {}", oldKey, key);
            } else {
                LOGGER.info("Removed obsolete config option {}", oldKey);
            }
            removeKey(oldKey);
        }
        if (!config.contains(key)) config.set(key, defaultValue);
        if (comment.length > 0) config.setComment(key, String.join("\n", comment), CommentType.BLOCK);
        if (readingRestartOnly) restartOnlyValues.put(key, config.get(key));
    }

    private static void removedOptions() {
        for (String typeKey : new String[]{"region-settings.type", ConfigCategory.MISC.key("region-settings.type")}) {
            if ("B_LINEAR".equalsIgnoreCase(config.getString(typeKey, ""))) {
                LOGGER.warn("{} B_LINEAR has been replaced by LINEAR; worlds stored as .b_linear must be converted with linear-converter", typeKey);
                config.set(typeKey, "LINEAR");
            }
        }
        if (config.getBoolean(ConfigCategory.MISC.key("lag-compensation.enabled"), false)) {
            LOGGER.warn("Lag compensation has been removed from DivineMC; misc.lag-compensation is ignored and will be deleted from the config.");
        }
        if (config.getBoolean(ConfigCategory.NETWORK.key("player-profile-result-caching.enabled"), false)) {
            LOGGER.warn("Player profile result caching has been removed from DivineMC: it let anyone join under a cached player name without a Mojang session check. network.player-profile-result-caching is ignored and will be deleted from the config.");
        }
        removeLegacy(
            ConfigCategory.ASYNC.key("parallel-world-ticking.log-container-creation-stacktraces"),
            ConfigCategory.ASYNC.key("parallel-world-ticking.disable-hard-throw"),
            ConfigCategory.ASYNC.key("multithreaded-tracker.compat-mode"),
            ConfigCategory.ASYNC.key("multithreaded-tracker.queue-size"),
            ConfigCategory.ASYNC.key("mob-spawning.async-natural-spawn"),
            ConfigCategory.MISC.key("lag-compensation.enabled"),
            ConfigCategory.MISC.key("lag-compensation.block-entity-acceleration"),
            ConfigCategory.MISC.key("lag-compensation.block-breaking-acceleration"),
            ConfigCategory.MISC.key("lag-compensation.eating-acceleration"),
            ConfigCategory.MISC.key("lag-compensation.potion-effect-acceleration"),
            ConfigCategory.MISC.key("lag-compensation.fluid-acceleration"),
            ConfigCategory.MISC.key("lag-compensation.pickup-acceleration"),
            ConfigCategory.MISC.key("lag-compensation.portal-acceleration"),
            ConfigCategory.MISC.key("lag-compensation.random-tick-speed-acceleration"),
            ConfigCategory.PERFORMANCE.key("chunks.experimental.enable-structure-layout-optimizer"),
            ConfigCategory.PERFORMANCE.key("chunks.experimental.deduplicate-shuffled-template-pool-element-list"),
            ConfigCategory.PERFORMANCE.key("chunks.chunk-worker-algorithm"),
            ConfigCategory.NETWORK.key("player-profile-result-caching.enabled"),
            ConfigCategory.NETWORK.key("player-profile-result-caching.timeout"),
            ConfigCategory.ASYNC.key("chunk-sending.enable"),
            ConfigCategory.ASYNC.key("chunk-sending.max-threads"),
            ConfigCategory.ASYNC.key("guru-mode.async-chunk-sending-affinity"),
            ConfigCategory.PERFORMANCE.key("chunks.experimental.enable-density-function-compiler"),
            ConfigCategory.PERFORMANCE.key("chunks.smooth-bedrock-layer"),
            ConfigCategory.PERFORMANCE.key("optimizations.skip-useless-secondary-poi-sensor"),
            "region-settings.linear.implementation",
            "region-settings.b-linear.check-interval-ms",
            "region-settings.b-linear.flush-of-write-timeout-ms",
            "region-settings.b-linear.max-sync-delay-ms"
        );
    }

    private static void removeLegacy(String... keys) {
        for (String key : keys) {
            if (config.contains(key)) {
                removeKey(key);
                LOGGER.info("Removed obsolete config option {}", key);
            }
        }
    }

    private static void removeKey(String key) {
        config.set(key, null);

        int idx = key.lastIndexOf('.');
        while (idx > 0) {
            String parent = key.substring(0, idx);
            ConfigurationSection section = config.getConfigurationSection(parent);
            if (section == null || !section.getKeys(false).isEmpty()) break;

            config.set(parent, null);
            key = parent;
            idx = key.lastIndexOf('.');
        }
    }

    private static boolean getBoolean(String key, boolean defaultValue, String... comment) {
        return getBoolean(key, null, defaultValue, comment);
    }

    private static boolean getBoolean(String key, @Nullable String oldKey, boolean defaultValue, String... comment) {
        ensureDefault(key, oldKey, defaultValue, comment);
        return config.getBoolean(key, defaultValue);
    }

    private static int getInt(String key, int defaultValue, String... comment) {
        return getInt(key, null, defaultValue, comment);
    }

    private static int getInt(String key, @Nullable String oldKey, int defaultValue, String... comment) {
        ensureDefault(key, oldKey, defaultValue, comment);
        return config.getInt(key, defaultValue);
    }

    private static double getDouble(String key, double defaultValue, String... comment) {
        return getDouble(key, null, defaultValue, comment);
    }

    private static double getDouble(String key, @Nullable String oldKey, double defaultValue, String... comment) {
        ensureDefault(key, oldKey, defaultValue, comment);
        return config.getDouble(key, defaultValue);
    }

    private static long getLong(String key, long defaultValue, String... comment) {
        return getLong(key, null, defaultValue, comment);
    }

    private static long getLong(String key, @Nullable String oldKey, long defaultValue, String... comment) {
        ensureDefault(key, oldKey, defaultValue, comment);
        return config.getLong(key, defaultValue);
    }

    private static String getString(String key, String defaultValue, String... comment) {
        ensureDefault(key, defaultValue, comment);
        return config.getString(key, defaultValue);
    }

    private static List<String> getStringList(String key, List<String> defaultValue, String... comment) {
        return getStringList(key, null, defaultValue, comment);
    }

    private static List<String> getStringList(String key, @Nullable String oldKey, List<String> defaultValue, String... comment) {
        ensureDefault(key, oldKey, defaultValue, comment);
        return config.getStringList(key);
    }

    private static int[] getIntArray(String key, int[] defaultValue, String... comment) {
        return getIntArray(key, null, defaultValue, comment);
    }

    private static int[] getIntArray(String key, @Nullable String oldKey, int[] defaultValue, String... comment) {
        ensureDefault(key, oldKey, Arrays.stream(defaultValue).boxed().toList(), comment);
        return config.getIntegerList(key).stream().mapToInt(Integer::intValue).toArray();
    }

    public static class AsyncCategory {
        private static boolean enableAutoAllocation = false;

        // Parallel world ticking settings
        @Experimental("Parallel World Ticking")
        public static boolean enableParallelWorldTicking = false;
        public static int maxConcurrentWorlds = 4;
        public static boolean usePerWorldTpsBar = true;
        public static boolean showTPSOfServerInsteadOfWorld = true;
        public static boolean serializePluginListeners = false;

        // Regionized chunk ticking
        @Experimental("Regionized Chunk Ticking")
        public static boolean enableRegionizedChunkTicking = false;
        public static int regionizedChunkTickingExecutorThreadCount = 4;
        public static int regionizedChunkTickingExecutorThreadPriority = Thread.NORM_PRIORITY + 2;

        // Async pathfinding settings
        public static boolean asyncPathfinding = true;
        public static int asyncPathfindingMaxThreads = 1;
        public static int asyncPathfindingKeepalive = 60;
        public static int asyncPathfindingQueueSize = 0;
        public static PathfindTaskRejectPolicy asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.CALLER_RUNS;

        // Parallel entity tracker settings
        public static boolean parallelTrackerEnabled = true;
        public static int parallelTrackerThreads = 0;
        public static int parallelTrackerKeepalive = 60;

        // Async mob spawning settings
        public static boolean enableAsyncSpawning = false;

        // Portal search prefetch settings
        public static boolean portalSearchPrefetchEnabled = true;

        // Player data writer settings
        public static boolean playerDataWriterEnabled = true;

        // Parallel sensor phase settings
        @Experimental("Parallel Sensor Phase")
        public static boolean parallelSensorsEnabled = false;
        public static int parallelSensorsMaxThreads = 0;

        // Guru mode settings
        public static boolean guruMode = false;
        public static int[] regionizedChunkTickingAffinity = new int[0];
        public static int[] asyncPathfindingAffinity = new int[0];
        public static int[] parallelSensorsAffinity = new int[0];
        public static int[] parallelEntityTrackerAffinity = new int[0];

        public static void load() {
            restartOnly(AsyncCategory::threadedFeatures);
            tpsBar();
            pluginListeners();
            portalSearchPrefetch();
            playerDataWriter();
        }

        private static void threadedFeatures() {
            enableAutoAllocation();
            parallelWorldTicking();
            regionizedChunkTicking();
            asyncPathfinding();
            parallelEntityTracker();
            asyncMobSpawning();
            parallelSensors();
            autoAllocation();
            guruMode();
        }

        private static void enableAutoAllocation() {
            enableAutoAllocation = getBoolean(ConfigCategory.ASYNC.key("auto-thread-allocation"), enableAutoAllocation,
                "Enables optimal thread allocation for Parallel World Ticking and Regionized Chunk Ticking.",
                "By enabling this, both Parallel World Ticking and Regionized Chunk Ticking will get enabled and",
                "thread count in config will get ignored and automatically allocated based on your system's CPU cores."
            );
            if (enableAutoAllocation) {
                if (maxThreads < 6) {
                    LOGGER.warn("Minimum thread count for auto-thread-allocation is 6, disabling auto-thread-allocation.");
                    enableAutoAllocation = false;
                } else {
                    LOGGER.info("Detected {} CPU threads", maxThreads);
                }
            }
        }

        private static void autoAllocation() {
            if (!enableAutoAllocation) {
                return;
            }

            enableParallelWorldTicking = true;
            enableRegionizedChunkTicking = true;

            assert maxThreads >= 6;
            int max = maxThreads - 1;
            switch (maxThreads) {
                case 6, 7, 8 -> {
                    maxConcurrentWorlds = 2;
                    max -= 2;
                    regionizedChunkTickingExecutorThreadCount = max; // 3 - 5
                }
                case 9, 10, 11 -> {
                    maxConcurrentWorlds = 3;
                    max -= 4;
                    regionizedChunkTickingExecutorThreadCount = max; // 4 - 6
                }
                case 12, 13, 14, 15 -> {
                    maxConcurrentWorlds = 4;
                    max -= 5;
                    regionizedChunkTickingExecutorThreadCount = max; // 6 - 9
                }
                default -> {
                    maxConcurrentWorlds = 5;
                    max -= 5;
                    regionizedChunkTickingExecutorThreadCount = Math.min(max, 16); // 10 - 16
                }
            }

            LOGGER.info("Auto-thread-allocation enabled, ticking up to {} worlds at once and using {} threads for regionized chunk ticking.", maxConcurrentWorlds, regionizedChunkTickingExecutorThreadCount);
        }

        private static void playerDataWriter() {
            playerDataWriterEnabled = getBoolean(ConfigCategory.ASYNC.key("player-data-writer.enable"), playerDataWriterEnabled,
                "Writes player data, statistics and advancements files on a separate thread instead of the tick thread.",
                "Every player file is written with a synchronous disk write, so saving many players in one tick stalls it.",
                "Files on disk are the same; anything reading a player file waits for its pending write first.");
        }

        private static void parallelWorldTicking() {
            enableParallelWorldTicking = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.enable"), enableParallelWorldTicking,
                "Enables Parallel World Ticking, which executes each world's tick in a separate thread while ensuring that all worlds complete their tick before the next cycle begins.",
                "",
                "Read more info about this feature at https://bxteam.org/docs/divinemc/features/parallel-world-ticking");
            maxConcurrentWorlds = getInt(
                ConfigCategory.ASYNC.key("parallel-world-ticking.max-concurrent-worlds"),
                ConfigCategory.ASYNC.key("parallel-world-ticking.thread-count"),
                maxConcurrentWorlds,
                "How many worlds may tick at the same time. Every world has its own tick thread; this limits how many",
                "of them run at once. A world that waits for another world during its tick keeps its slot while waiting.");
        }

        private static void tpsBar() {
            usePerWorldTpsBar = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.use-per-world-tps-bar"), usePerWorldTpsBar,
                "Enables per-world TPS bar, which shows the TPS of the world the player is currently in. TPS bar can be turned on/off with /tpsbar command.");
            showTPSOfServerInsteadOfWorld = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.show-tps-of-server-instead-of-world"), showTPSOfServerInsteadOfWorld,
                "Enables showing the TPS of the entire server instead of the world in the TPS bar.");
        }

        private static void pluginListeners() {
            serializePluginListeners = getBoolean(ConfigCategory.ASYNC.key("serialize-plugin-listeners"), serializePluginListeners,
                "With parallel world ticking or regionized chunk ticking, event listeners run on several threads at once.",
                "Most plugins keep their own state in plain collections that are not safe for that. When enabled, listeners of",
                "plugins that do not declare 'folia-supported: true' run one at a time across all threads, which protects such",
                "plugins at the cost of waiting between threads whenever their listeners are called.");
        }

        private static void regionizedChunkTicking() {
            enableRegionizedChunkTicking = getBoolean(ConfigCategory.ASYNC.key("regionized-chunk-ticking.enable"), enableRegionizedChunkTicking,
                "Enables regionized chunk ticking, similar to like Folia works.",
                "",
                "Read more info about this feature at https://bxteam.org/docs/divinemc/features/regionized-chunk-ticking");

            regionizedChunkTickingExecutorThreadCount = getInt(ConfigCategory.ASYNC.key("regionized-chunk-ticking.executor-thread-count"), regionizedChunkTickingExecutorThreadCount,
                "The amount of threads to allocate to regionized chunk ticking.");
            regionizedChunkTickingExecutorThreadPriority = getInt(ConfigCategory.ASYNC.key("regionized-chunk-ticking.executor-thread-priority"), regionizedChunkTickingExecutorThreadPriority,
                "Configures the thread priority of the executor");

            if (!enableAutoAllocation && (regionizedChunkTickingExecutorThreadCount <= 1 || regionizedChunkTickingExecutorThreadCount > maxThreads)) {
                LOGGER.warn("Invalid regionized chunk ticking thread count: {}, resetting to default (4)", regionizedChunkTickingExecutorThreadCount);
                regionizedChunkTickingExecutorThreadCount = 4;
            }
        }

        private static void asyncPathfinding() {
            asyncPathfinding = getBoolean(ConfigCategory.ASYNC.key("pathfinding.enable"), asyncPathfinding);
            asyncPathfindingMaxThreads = getInt(ConfigCategory.ASYNC.key("pathfinding.max-threads"), asyncPathfindingMaxThreads);
            asyncPathfindingKeepalive = getInt(ConfigCategory.ASYNC.key("pathfinding.keepalive"), asyncPathfindingKeepalive);
            asyncPathfindingQueueSize = getInt(ConfigCategory.ASYNC.key("pathfinding.queue-size"), asyncPathfindingQueueSize);

            if (asyncPathfindingMaxThreads < 0) {
                asyncPathfindingMaxThreads = Math.max(maxThreads + asyncPathfindingMaxThreads, 1);
            } else if (asyncPathfindingMaxThreads == 0) {
                asyncPathfindingMaxThreads = Math.max(maxThreads / 4, 1);
            }

            if (!asyncPathfinding) {
                asyncPathfindingMaxThreads = 0;
            } else {
                LOGGER.info("Using {} threads for Async Pathfinding", asyncPathfindingMaxThreads);
            }

            if (asyncPathfindingQueueSize <= 0) asyncPathfindingQueueSize = asyncPathfindingMaxThreads * 256;

            try {
                asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.valueOf(getString(ConfigCategory.ASYNC.key("pathfinding.reject-policy"),
                    maxThreads >= 12 && asyncPathfindingQueueSize < 512
                        ? PathfindTaskRejectPolicy.FLUSH_ALL.toString()
                        : PathfindTaskRejectPolicy.CALLER_RUNS.toString(),
                    "The policy to use when the queue is full and a new task is submitted.",
                    "FLUSH_ALL: All pending tasks will be run on server thread.",
                    "CALLER_RUNS: Newly submitted task will be run on server thread."));
            } catch (IllegalArgumentException ignore) {
                LOGGER.warn("Invalid async pathfinding reject policy, using default CALLER_RUNS");
                asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.CALLER_RUNS;
            }
        }

        private static void parallelEntityTracker() {
            parallelTrackerEnabled = getBoolean(
                ConfigCategory.ASYNC.key("parallel-entity-tracker.enable"),
                ConfigCategory.ASYNC.key("multithreaded-tracker.enable"),
                parallelTrackerEnabled,
                "Runs the entity tracker across a thread pool: the read-only visibility scan and the",
                "per-entity packet diffs run in parallel, while pairing changes and Bukkit events are",
                "applied on the tick thread between them. Each phase is joined before the tick continues,",
                "so tracking keeps vanilla ordering and works with entity-based NPC plugins (Citizens).");

            parallelTrackerThreads = getInt(
                ConfigCategory.ASYNC.key("parallel-entity-tracker.threads"),
                ConfigCategory.ASYNC.key("multithreaded-tracker.max-threads"),
                parallelTrackerThreads,
                "Worker threads used by the tracker.",
                "0 = a quarter of available cores; negative = all cores minus that many.",
                "At least 1 thread is always used.");

            parallelTrackerKeepalive = getInt(
                ConfigCategory.ASYNC.key("parallel-entity-tracker.keepalive"),
                ConfigCategory.ASYNC.key("multithreaded-tracker.keepalive"),
                parallelTrackerKeepalive,
                "Seconds an idle worker thread is kept alive before being released.");

            if (parallelTrackerThreads <= 0) {
                parallelTrackerThreads = Math.max(parallelTrackerThreads == 0
                    ? maxThreads / 4
                    : maxThreads + parallelTrackerThreads, 1);
            }

            if (parallelTrackerEnabled) {
                LOGGER.info("Using {} threads for the Parallel Entity Tracker", parallelTrackerThreads);
            }
        }

        private static void asyncMobSpawning() {
            enableAsyncSpawning = getBoolean(ConfigCategory.ASYNC.key("mob-spawning.enable"), enableAsyncSpawning,
                "Enables optimization that will offload much of the computational effort involved with spawning new mobs to a different thread.");
        }

        private static void portalSearchPrefetch() {
            portalSearchPrefetchEnabled = getBoolean(ConfigCategory.ASYNC.key("portal-search-prefetch.enable"), portalSearchPrefetchEnabled,
                "Async pre-loads the exit portal search area (chunk and POI data) when an entity enters a nether",
                "portal, so the destination search at teleport time hits warm caches instead of reading from disk.");
        }

        private static void parallelSensors() {
            parallelSensorsEnabled = getBoolean(ConfigCategory.ASYNC.key("parallel-sensors.enable"), parallelSensorsEnabled,
                "Runs the read-only part of brain AI (sensors: nearest-entity scans, line-of-sight raycasts)",
                "on a worker thread pool right before the entity ticking phase, instead of on the main thread",
                "during each mob's tick. Sensors are a stable top entry in profiler output on servers with",
                "many villagers/piglins, and they only read world state, which makes them safe to batch.");
            parallelSensorsMaxThreads = getInt(ConfigCategory.ASYNC.key("parallel-sensors.max-threads"), parallelSensorsMaxThreads,
                "The amount of threads to use for the parallel sensor phase.",
                "Values <= 0 mean 1/4 of available cores (at least 1).");

            if (parallelSensorsMaxThreads < 0) {
                parallelSensorsMaxThreads = Math.max(maxThreads + parallelSensorsMaxThreads, 1);
            } else if (parallelSensorsMaxThreads == 0) {
                parallelSensorsMaxThreads = Math.max(maxThreads / 4, 1);
            }

            if (parallelSensorsEnabled) {
                LOGGER.info("Using {} threads for Parallel Sensor Phase", parallelSensorsMaxThreads);
            }
        }

        private static void guruMode() {
            guruMode = getBoolean(ConfigCategory.ASYNC.key("guru-mode.enabled"), ConfigCategory.ASYNC.key("guru-mode-enable"), guruMode,
                "Allows pinning the threads of async features to CPU cores (thread affinity). Only enable this when you know exactly what you are doing.",
                "NO SUPPORT WILL BE GIVEN IF THIS IS ENABLED",
                "To enable this you need also to set system property 'divinemc.async.guru' to true.",
                "The affinity options below are added once this is enabled.")
                && Boolean.getBoolean("divinemc.async.guru");
            if (!guruMode) {
                return;
            }

            regionizedChunkTickingAffinity = getIntArray(ConfigCategory.ASYNC.key("guru-mode.regionized-chunk-ticking-affinity"), regionizedChunkTickingAffinity,
                "CPU cores the regionized chunk ticking threads are pinned to, e.g. [2, 3, 4, 5]. Empty leaves them unpinned.");
            asyncPathfindingAffinity = getIntArray(ConfigCategory.ASYNC.key("guru-mode.async-pathfinding-affinity"), asyncPathfindingAffinity,
                "CPU cores the async pathfinding threads are pinned to. Empty leaves them unpinned.");
            parallelSensorsAffinity = getIntArray(ConfigCategory.ASYNC.key("guru-mode.parallel-sensors-affinity"), parallelSensorsAffinity,
                "CPU cores the parallel sensor phase threads are pinned to. Empty leaves them unpinned.");
            parallelEntityTrackerAffinity = getIntArray(ConfigCategory.ASYNC.key("guru-mode.parallel-entity-tracker-affinity"), parallelEntityTrackerAffinity,
                "CPU cores the parallel entity tracker threads are pinned to. Empty leaves them unpinned.");
        }
    }

    public static class PerformanceCategory {
        // Chunk settings
        public static boolean nativeAccelerationEnabled = false;
        public static boolean allowAVX512 = false;
        public static int isaTargetLevelOverride = -1;
        public static long chunkDataCacheSoftLimit = 8192L;
        public static long chunkDataCacheLimit = 32678L;
        public static int maxViewDistance = 32;
        public static int playerNearChunkDetectionRange = 128;
        public static boolean endBiomeCacheEnabled = false;
        public static int endBiomeCacheCapacity = 1024;

        // General optimizations
        public static boolean disableMethodProfiler = true;
        public static boolean clumpOrbs = false;
        public static boolean enableSuffocationOptimization = true;
        public static boolean useCompactBitStorage = false;
        public static boolean commandBlockParseResultsCaching = true;
        public static boolean sheepOptimization = true;
        public static boolean reduceChuckLoadAndLookup = true;
        public static boolean createSnapshotOnRetrievingBlockState = true;
        public static boolean sleepingBlockEntity = false;
        public static boolean equipmentTracking = true;
        public static boolean hopperThrottleWhenFull = false;
        public static int hopperThrottleSkipTicks = 0;
        public static boolean skipFullHopperMinecart = false;

        // DAB settings
        public static boolean dabEnabled = false;
        public static int dabStartDistance = 12;
        public static int dabStartDistanceSquared;
        public static int dabMaximumActivationFrequency = 20;
        public static int dabActivationDistanceMod = 8;
        public static boolean dabDontEnableIfInWater = false;
        public static List<String> dabBlackedEntities = new ArrayList<>(Arrays.asList(
            "villager",
            "axolotl",
            "hoglin",
            "zombified_piglin",
            "goat"
        ));

        // Virtual threads
        public static boolean virtualThreadsEnabled = true;
        public static boolean virtualBukkitScheduler = true;
        public static boolean virtualChatScheduler = true;
        public static boolean virtualTabCompleteScheduler = true;
        public static boolean virtualAsyncExecutor = true;
        public static boolean virtualCommandBuilderScheduler = true;
        public static boolean virtualServerTextFilterPool = true;

        public static void load() {
            chunkSettings();
            restartOnly(PerformanceCategory::viewDistanceLimit);
            optimizationSettings();
            dab();
            virtualThreads();
        }

        private static void chunkSettings() {
            nativeAccelerationEnabled = getBoolean(ConfigCategory.PERFORMANCE.key("chunks.native-acceleration.enabled"), nativeAccelerationEnabled,
                "Enables native (C2ME) acceleration of world generation math.",
                "",
                "WARNING: native floating-point math may not be bit-identical to Java's, so worlds",
                "generated with this enabled can differ block-by-block from vanilla generation and",
                "between machines with different CPU ISA levels. Seed-parity is NOT guaranteed.");

            allowAVX512 = getBoolean(ConfigCategory.PERFORMANCE.key("chunks.native-acceleration.allow-avx512"), allowAVX512,
                "Enables AVX512 support for natives-math optimizations",
                "",
                "Read more about AVX512: https://en.wikipedia.org/wiki/AVX-512");
            isaTargetLevelOverride = getInt(ConfigCategory.PERFORMANCE.key("chunks.native-acceleration.isa-target-level-override"), isaTargetLevelOverride,
                "Overrides the ISA target located by the native loader, which allows forcing AVX512 (x86_64: 8-10 for AVX512, 12-13 for AVX10.2).",
                "Value must be between 0-13, and -1 to disable override");

            if (isaTargetLevelOverride < -1 || isaTargetLevelOverride > 13) {
                LOGGER.warn("Invalid ISA target level override: {}, resetting to -1", isaTargetLevelOverride);
                isaTargetLevelOverride = -1;
            }

            if (nativeAccelerationEnabled) {
                try {
                    if (org.bxteam.divinemc.math.NativeLoader.currentMachineTarget == null) {
                        nativeAccelerationEnabled = false;
                    }
                } catch (Throwable t) {
                    LOGGER.error("Failed to initialize native math acceleration, continuing without it", t);
                    nativeAccelerationEnabled = false;
                }
            }

            chunkDataCacheSoftLimit = getLong(ConfigCategory.PERFORMANCE.key("chunks.chunk-data-cache-soft-limit"), chunkDataCacheSoftLimit);
            chunkDataCacheLimit = getLong(ConfigCategory.PERFORMANCE.key("chunks.chunk-data-cache-limit"), chunkDataCacheLimit);
            playerNearChunkDetectionRange = getInt(ConfigCategory.PERFORMANCE.key("chunks.player-near-chunk-detection-range"), playerNearChunkDetectionRange,
                "In certain checks, like if a player is near a chunk(primarily used for spawning), it checks if the player is within a certain",
                "circular range of the chunk. This configuration allows configurability of the distance(in blocks) the player must be to pass the check.",
                "",
                "This value is used in the calculation 'range/16' to get the distance in chunks any player must be to allow the check to pass.",
                "By default, this range is computed to 8, meaning a player must be within an 8 chunk radius of a chunk position to pass.",
                "Keep in mind the result is rounded to the nearest whole number.");
            endBiomeCacheEnabled = getBoolean(ConfigCategory.PERFORMANCE.key("chunks.end-biome-cache-enabled"), endBiomeCacheEnabled,
                "Enables the end biome cache, which can accelerate The End worldgen.");
            endBiomeCacheCapacity = getInt(ConfigCategory.PERFORMANCE.key("chunks.end-biome-cache-capacity"), endBiomeCacheCapacity,
                "The cache capacity for the end biome cache. Only used if end-biome-cache-enabled is true.");

            if (playerNearChunkDetectionRange < 0) {
                LOGGER.warn("Invalid player near chunk detection range: {}, resetting to default (128)", playerNearChunkDetectionRange);
                playerNearChunkDetectionRange = 128;
            }
        }

        private static void viewDistanceLimit() {
            maxViewDistance = getInt(ConfigCategory.PERFORMANCE.key("chunks.max-view-distance"), maxViewDistance,
                "Changes the maximum view distance for the server, allowing clients to have render distances higher than 32");
        }

        private static void optimizationSettings() {
            disableMethodProfiler = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.disable-method-profiler"), disableMethodProfiler,
                "Disables the method profiler to save some performance. Mainly used for debugging purposes.");
            clumpOrbs = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.clump-orbs"), clumpOrbs,
                "Clumps experience orbs together to reduce entity count");
            enableSuffocationOptimization = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.enable-suffocation-optimization"), enableSuffocationOptimization,
                "Optimizes the suffocation check by selectively skipping the check in a way that still appears vanilla.",
                "This option should be left enabled on most servers, but is provided as a configuration option if the vanilla deviation is undesirable.");
            useCompactBitStorage = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.use-compact-bit-storage"), useCompactBitStorage,
                "Fixes memory waste caused by sending empty chunks as if they contain blocks. Can significantly reduce memory usage.");
            commandBlockParseResultsCaching = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.command-block-parse-results-caching"), commandBlockParseResultsCaching,
                "Caches the parse results of command blocks, can significantly reduce performance impact.");
            sheepOptimization = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.sheep-optimization"), sheepOptimization,
                "Enables optimization from Carpet Fixes mod, using a prebaked list of all the possible colors and combinations for sheep.");
            reduceChuckLoadAndLookup = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.reduce-chunk-load-and-lookup"), reduceChuckLoadAndLookup,
                "If enabled, optimizes chunk loading and block state lookups by reducing the number of chunk accesses required during operations such as Enderman teleportation.");
            createSnapshotOnRetrievingBlockState = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.create-snapshot-on-retrieving-block-state"), createSnapshotOnRetrievingBlockState,
                "Whether to create a snapshot (copy) of BlockState data when plugins retrieve them.",
                "If false, plugins get direct BlockState access for better performance but risk data corruption from poor plugin design.");
            sleepingBlockEntity = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.sleeping-block-entity"), sleepingBlockEntity,
                "When enabled, block entities will enter a sleep state when they are inactive.");
            equipmentTracking = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.equipment-tracking"), equipmentTracking,
                "When enabled, skips repeated checks whether the equipment of an entity changed.");

            hopperThrottleWhenFull = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.hopper-throttle-when-full.enabled"), hopperThrottleWhenFull,
                "When enabled, hoppers will throttle if target container is full.");
            hopperThrottleSkipTicks = getInt(ConfigCategory.PERFORMANCE.key("optimizations.hopper-throttle-when-full.skip-ticks"), hopperThrottleSkipTicks,
                "The amount of ticks to skip when the hopper is throttled.");

            skipFullHopperMinecart = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.skip-full-hopper-minecart"), skipFullHopperMinecart,
                "When enabled, a hopper minecart with every slot full no longer tries to pull items. InventoryMoveItemEvent, PrepareHopperTransferEvent and InventoryPickupItemEvent are not fired for it.");
        }

        private static void dab() {
            dabEnabled = getBoolean(ConfigCategory.PERFORMANCE.key("dab.enabled"), dabEnabled,
                "Enables DAB feature");
            dabStartDistance = getInt(ConfigCategory.PERFORMANCE.key("dab.start-distance"), dabStartDistance,
                "This value determines how far away an entity has to be");
            dabStartDistanceSquared = dabStartDistance * dabStartDistance;
            dabMaximumActivationFrequency = getInt(ConfigCategory.PERFORMANCE.key("dab.maximum-activation-frequency"), dabMaximumActivationFrequency,
                "How often in ticks, the furthest entity will get their pathfinders and behaviors ticked.");
            dabActivationDistanceMod = getInt(ConfigCategory.PERFORMANCE.key("dab.activation-distance-mod"), dabActivationDistanceMod,
                "Modifies an entity's tick frequency.",
                "The exact calculation to obtain the tick frequency for an entity is: freq = (distanceToPlayer^2) / (2^value), where value is this configuration setting.",
                "Large servers may want to reduce the value to 7, but this value should never be reduced below 6. If you want further away entities to tick more often, set the value to 9");
            dabDontEnableIfInWater = getBoolean(ConfigCategory.PERFORMANCE.key("dab.dont-enable-if-in-water"), dabDontEnableIfInWater,
                "When this is enabled, non-aquatic entities in the water will not be affected by DAB.");
            dabBlackedEntities = getStringList(ConfigCategory.PERFORMANCE.key("dab.blacked-entities"), dabBlackedEntities,
                "Use this configuration option to specify that certain entities should not be impacted by DAB.");

            setComment(ConfigCategory.PERFORMANCE.key("dab"),
                "DAB is an optimization that reduces the frequency of brain ticks. Brain ticks are very intensive, which is why they",
                "are limited. DAB can be tuned to meet your preferred performance-experience tradeoff. The farther away entities",
                "are from players, the less frequently their brains will be ticked. While DAB does impact the AI goal selector",
                "behavior of all entities, the only entities who's brain ticks are limited are: Villager, Axolotl, Hoglin, Zombified Piglin and Goat");

            for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
                entityType.dabEnabled = true;
            }

            final String DEFAULT_PREFIX = Identifier.DEFAULT_NAMESPACE + Identifier.NAMESPACE_SEPARATOR;
            for (String name : dabBlackedEntities) {
                String lowerName = name.toLowerCase(Locale.ROOT);
                String typeId = lowerName.startsWith(DEFAULT_PREFIX) ? lowerName : DEFAULT_PREFIX + lowerName;

                EntityType.byString(typeId).ifPresentOrElse(entityType -> entityType.dabEnabled = false, () -> LOGGER.warn("Unknown entity {}, in {}", name, ConfigCategory.PERFORMANCE.key("dab.blacked-entities")));
            }
        }

        private static void virtualThreads() {
            virtualThreadsEnabled = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.enabled"), virtualThreadsEnabled,
                "Enables use of virtual threads that was added in Java 21");

            virtualBukkitScheduler = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.bukkit-scheduler"), virtualBukkitScheduler,
                "Uses virtual threads for the Bukkit scheduler.");
            virtualChatScheduler = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.chat-scheduler"), virtualChatScheduler,
                "Uses virtual threads for the Chat scheduler.");
            virtualTabCompleteScheduler = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.tab-complete-scheduler"), virtualTabCompleteScheduler,
                "Uses virtual threads for the Tab Complete scheduler.");
            virtualAsyncExecutor = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.async-executor"), virtualAsyncExecutor,
                "Uses virtual threads for the MCUtil async executor.");
            virtualCommandBuilderScheduler = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.command-builder-scheduler"), virtualCommandBuilderScheduler,
                "Uses virtual threads for the Async Command Builder Thread Pool.");
            virtualServerTextFilterPool = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.server-text-filter-pool"), virtualServerTextFilterPool,
                "Uses virtual threads for the server text filter pool.");
        }
    }

    public static class FixesCategory {
        // Gameplay fixes
        public static boolean fixIncorrectBounceLogic = false;
        public static boolean updateSuppressionCrashFix = true;
        public static boolean ignoreMovedTooQuicklyWhenLagging = true;
        public static boolean alwaysAllowWeirdMovement = true;

        // Miscellaneous fixes
        public static boolean forceMinecraftCommand = false;
        public static boolean disableLeafDecay = false;

        // MC Bug fixes
        public static boolean fixMc258859 = false;
        public static boolean fixMc200418 = false;
        public static boolean fixMc2025 = false;
        public static boolean fixMc94054 = false;
        public static boolean fixMc183990 = false;
        public static boolean fixMc118740 = false;
        public static boolean fixMc28289 = false;
        public static boolean fixMc73719 = false;
        public static boolean fixMc65198 = false;
        public static boolean fixMc270656 = false;
        public static boolean fixMc299642 = false;

        public static void load() {
            gameplayFixes();
            miscFixes();
            bugFixes();
        }

        private static void gameplayFixes() {
            fixIncorrectBounceLogic = getBoolean(ConfigCategory.FIXES.key("gameplay.fix-incorrect-bounce-logic"), fixIncorrectBounceLogic,
                "Fixes incorrect bounce logic in SlimeBlock.");
            updateSuppressionCrashFix = getBoolean(ConfigCategory.FIXES.key("gameplay.update-suppression-crash-fix"), updateSuppressionCrashFix);
            ignoreMovedTooQuicklyWhenLagging = getBoolean(ConfigCategory.FIXES.key("gameplay.ignore-moved-too-quickly-when-lagging"), ignoreMovedTooQuicklyWhenLagging,
                "Improves general gameplay experience of the player when the server is lagging, as they won't get lagged back (message 'moved too quickly')");
            alwaysAllowWeirdMovement = getBoolean(ConfigCategory.FIXES.key("gameplay.always-allow-weird-movement"), alwaysAllowWeirdMovement,
                "Means ignoring messages like 'moved too quickly' and 'moved wrongly'");
        }

        private static void miscFixes() {
            forceMinecraftCommand = getBoolean(ConfigCategory.FIXES.key("misc.force-minecraft-command"), forceMinecraftCommand,
                "Whether to force the use of vanilla commands over plugin commands.");
            disableLeafDecay = getBoolean(ConfigCategory.FIXES.key("misc.disable-leaf-decay"), disableLeafDecay,
                "Disables leaf block decay.");
        }

        private static void bugFixes() {
            fixMc258859 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-258859"), fixMc258859,
                "Fixes MC-258859: https://bugs.mojang.com/browse/MC-258859",
                "Fixes slopes visual bug in biomes like Snowy Slopes, Frozen Peaks, Jagged Peaks, and including Terralith.");
            fixMc200418 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-200418"), fixMc200418,
                "Fixes MC-200418: https://bugs.mojang.com/browse/MC-200418",
                "Baby zombie villagers stay as jockey variant.");
            fixMc2025 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-2025"), fixMc2025,
                "Fixes MC-2025: https://bugs.mojang.com/browse/MC-2025",
                "Mobs going out of fenced areas/suffocate in blocks when loading chunks.");
            fixMc94054 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-94054"), fixMc94054,
                "Fixes MC-94054: https://bugs.mojang.com/browse/MC-94054",
                "Cave spiders spin around when walking.");
            fixMc183990 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-183990"), fixMc183990,
                "Fixes MC-183990: https://bugs.mojang.com/browse/MC-183990",
                "AI of some mobs breaks when their target dies.");
            fixMc118740 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-118740"), fixMc118740,
                "Fixes MC-118740: https://bugs.mojang.com/browse/MC-118740",
                "Any right click resets attack cooldown.");
            fixMc28289 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-28289"), fixMc28289,
                "Fixes MC-28289: https://bugs.mojang.com/browse/MC-28289",
                "Switching items at the same time as attacking carries over the attributes and enchantments of the previously held item.");
            fixMc73719 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-73719"), fixMc73719,
                "Fixes MC-73719: https://bugs.mojang.com/browse/MC-73719",
                "Ender dragons with NoAI don't visually move on the client and keep their hitboxes at the old position.");
            fixMc65198 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-65198"), fixMc65198,
                "Fixes MC-65198: https://bugs.mojang.com/browse/MC-65198",
                "The crafted item statistic counts too few items when the result is dropped, split or shift-clicked out of a stonecutter or smithing table.");
            fixMc270656 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-270656"), fixMc270656,
                "Fixes MC-270656: https://bugs.mojang.com/browse/MC-270656",
                "Teleporting, flying, swimming or climbing upward after an explosion counts toward the \"Who Needs Rockets?\" height.");
            fixMc299642 = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-299642"), fixMc299642,
                "Fixes MC-299642: https://bugs.mojang.com/browse/MC-299642",
                "Turtles with NoGravity sink when they move out of water.");
        }
    }

    public static class MiscCategory {
        // Secure seed
        public static boolean enableSecureSeed = false;
        public static HashingVersion secureSeedHashingVersion = HashingVersion.BLAKE2B;

        // Sentry
        public static String sentryDsn = "";
        public static String logLevel = "WARN";
        public static boolean onlyLogThrown = true;

        // Region format
        public static RegionFormat regionFormat = RegionFormat.MCA;
        public static int linearCompressionLevel = 6;
        public static int linearFlushThreads = 2;
        public static int linearFlushDelayMs = 10000;
        public static int linearMaxPendingMb = 256;

        // Old features
        public static boolean copperBulb1gt = false;
        public static boolean crafter1gt = false;

        public static void load() {
            restartOnly(MiscCategory::regionSettings);
            restartOnly(MiscCategory::secureSeed);
            sentrySettings();
            oldFeatures();
        }

        private static void regionSettings() {
            ensureDefault(ConfigCategory.MISC.key("region-settings.type"), "region-settings.type", (Object) regionFormat.name(),
                "The format of region, entities and poi files.",
                " - MCA: Minecraft's Anvil format",
                " - LINEAR: DivineMC's Linear format (zstd, append-only log; see linear-format/SPEC.md in the repository)",
                "Worlds are not converted automatically: convert them with linear-converter (https://github.com/BX-Team/linear-converter) while the server is stopped.");
            final String type = config.getString(ConfigCategory.MISC.key("region-settings.type"), regionFormat.name());
            final RegionFormat parsed = RegionFormat.parse(type);
            if (parsed == null) {
                LOGGER.warn("Invalid {}: {}, using MCA", ConfigCategory.MISC.key("region-settings.type"), type);
                regionFormat = RegionFormat.MCA;
            } else {
                regionFormat = parsed;
            }

            linearCompressionLevel = getInt(ConfigCategory.MISC.key("region-settings.linear.compression-level"), "region-settings.compression-level", linearCompressionLevel,
                "zstd level Linear compresses chunks with (1-22). Higher is smaller and slower; files stay readable at any level.");
            if (linearCompressionLevel < 1 || linearCompressionLevel > 22) {
                LOGGER.warn("Invalid Linear compression level {}, using 6", linearCompressionLevel);
                linearCompressionLevel = 6;
            }
            linearFlushThreads = getInt(ConfigCategory.MISC.key("region-settings.linear.flush-threads"), "region-settings.thread-count", linearFlushThreads,
                "Threads that write Linear regions to disk.");
            if (linearFlushThreads < 1) {
                LOGGER.warn("Invalid Linear flush thread count {}, using 2", linearFlushThreads);
                linearFlushThreads = 2;
            }
            linearFlushDelayMs = getInt(ConfigCategory.MISC.key("region-settings.linear.flush-delay-ms"), "region-settings.linear.io-flush-delay-ms", linearFlushDelayMs,
                "How long written chunks wait in memory before they are flushed to disk.",
                "Chunks written within this window are lost if the server crashes.");
            if (linearFlushDelayMs < 1) {
                LOGGER.warn("Invalid Linear flush delay {}, using 10000", linearFlushDelayMs);
                linearFlushDelayMs = 10000;
            }
            linearMaxPendingMb = getInt(ConfigCategory.MISC.key("region-settings.linear.max-pending-mb"), "region-settings.linear.max-pending-mb", linearMaxPendingMb,
                "Megabytes of written chunks that may wait for a flush before chunk saving waits for the disk.");
            if (linearMaxPendingMb < 1) {
                LOGGER.warn("Invalid Linear max pending {} MB, using 256", linearMaxPendingMb);
                linearMaxPendingMb = 256;
            }
            if (config.contains("region-settings") && config.getConfigurationSection("region-settings") != null
                && config.getConfigurationSection("region-settings").getKeys(true).isEmpty()) {
                removeKey("region-settings");
            }
        }

        private static void secureSeed() {
            enableSecureSeed = getBoolean(ConfigCategory.MISC.key("secure-seed.enable"), enableSecureSeed,
                "This feature is based on Secure Seed mod by Earthcomputer.",
                "",
                "Terrain and biome generation remains the same, but all the ores and structures are generated with 1024-bit seed, instead of the usual 64-bit seed.",
                "This seed is almost impossible to crack, and there are no weird links between structures.");

            final String hashingVersion = getString(ConfigCategory.MISC.key("secure-seed.hashing-version"), secureSeedHashingVersion.toString(),
                "Type of hashing algorithm to use for secure seed.",
                "Valid values:",
                " - BLAKE2B: Cryptographically secure, well-tested, and reliable (default)",
                " - BLAKE3: Faster than BLAKE2b with better security guarantees. Requires jdk.incubator.vector module");
            try {
                secureSeedHashingVersion = HashingVersion.valueOf(hashingVersion);
            } catch (IllegalArgumentException ignore) {
                LOGGER.warn("Invalid secure seed hashing version: {}, resetting to default (BLAKE2B)", hashingVersion);
                secureSeedHashingVersion = HashingVersion.BLAKE2B;
            }
        }

        private static void sentrySettings() {
            sentryDsn = getString(ConfigCategory.MISC.key("sentry.dsn"), sentryDsn,
                "The DSN for Sentry, a service that provides real-time crash reporting that helps you monitor and fix crashes in real time. Leave blank to disable. Obtain link at https://sentry.io");
            logLevel = getString(ConfigCategory.MISC.key("sentry.log-level"), logLevel,
                "Logs with a level higher than or equal to this level will be recorded.");
            onlyLogThrown = getBoolean(ConfigCategory.MISC.key("sentry.only-log-thrown"), onlyLogThrown,
                "Only log Throwable exceptions to Sentry.");

            if (sentryDsn != null && !sentryDsn.isBlank())
                gg.pufferfish.pufferfish.sentry.SentryManager.init(Level.getLevel(logLevel));
        }

        private static void oldFeatures() {
            copperBulb1gt = getBoolean(ConfigCategory.MISC.key("old-features.copper-bulb-1gt"), copperBulb1gt,
                "Whether to delay the copper lamp by 1 tick when the redstone signal changes.");
            crafter1gt = getBoolean(ConfigCategory.MISC.key("old-features.crafter-1gt"), crafter1gt,
                "Whether to reduce the frequency of the crafter outputting items to 1 tick.");
        }
    }

    public static class NetworkCategory {
        // General network settings
        public static boolean optimizeNonFlushPacketSending = false;
        public static boolean disableDisconnectSpam = false;
        public static boolean dontRespondPingBeforeStart = true;
        public static boolean sendSpectatorChangePacket = true;

        // No chat reports
        public static boolean noChatReportsEnabled = false;
        public static boolean noChatReportsAddQueryData = true;
        public static boolean noChatReportsConvertToGameMessage = true;
        public static boolean noChatReportsDebugLog = false;
        public static boolean noChatReportsDemandOnClient = false;
        public static String noChatReportsDisconnectDemandOnClientMessage = "You do not have No Chat Reports, and this server is configured to require it on client!";

        // Protocols
        public static boolean protocolsStrictMode = false;
        public static boolean protocolsAppleSkinEnabled = false;
        public static int protocolsAppleSkinSyncTickInterval = 20;
        public static boolean protocolsJadeEnabled = false;
        public static boolean protocolsMapsXaeroMapEnabled = false;
        public static int protocolsMapsXaeroMapServerId = new Random().nextInt();
        public static boolean protocolsSyncMaticaEnabled = false;
        public static boolean protocolsSyncMaticaQuota = false;
        public static int protocolsSyncMaticaQuotaLimit = 40000000;

        // Raytrace entity culling
        @Experimental("Raytrace Entity Culling")
        public static boolean raytraceCullingEnabled = false;
        public static int raytraceCullingThreads = 0;
        public static int raytraceCullingCheckIntervalMs = 50;
        public static int raytraceCullingMaxTraceDistance = 64;
        public static double raytraceCullingForceVisibleRadius = 4.0D;
        public static long raytraceCullingVisibilityTimeoutMs = 1000L;
        public static double raytraceCullingAabbExpansion = 0.5D;
        public static int raytraceCullingBoundingBoxLimit = 20;
        public static boolean raytraceCullingCullPlayers = true;
        public static boolean raytraceCullingHideCulledPlayersOnLocatorBar = false;
        public static boolean raytraceCullingSkipMarkerArmorStands = true;
        public static List<String> raytraceCullingSkippedEntityTypes = new ArrayList<>();

        public static void load() {
            networkSettings();
            noChatReports();
            protocols();
            raytraceCulling();
        }

        private static void networkSettings() {
            optimizeNonFlushPacketSending = getBoolean(ConfigCategory.NETWORK.key("general.optimize-non-flush-packet-sending"), optimizeNonFlushPacketSending,
                "Optimizes non-flush packet sending by using Netty's lazyExecute method to avoid expensive thread wakeup calls when scheduling packet operations.",
                "",
                "NOTE: This option is NOT compatible with ProtocolLib and may cause issues with other plugins that modify packet handling!");
            disableDisconnectSpam = getBoolean(ConfigCategory.NETWORK.key("general.disable-disconnect-spam"), disableDisconnectSpam,
                "Prevents players being disconnected by 'disconnect.spam' when sending too many chat packets");
            dontRespondPingBeforeStart = getBoolean(ConfigCategory.NETWORK.key("general.dont-respond-ping-before-start"), dontRespondPingBeforeStart,
                "Prevents the server from responding to pings before the server is fully booted.");
            sendSpectatorChangePacket = getBoolean(ConfigCategory.NETWORK.key("general.send-spectator-change-packet"), sendSpectatorChangePacket,
                "When disabled, tab list will not show that the player have entered the spectator mode. Otherwise, it will act as normal spectator change packet.");
        }

        private static void noChatReports() {
            noChatReportsEnabled = getBoolean(ConfigCategory.NETWORK.key("no-chat-reports.enabled"), noChatReportsEnabled,
                "Enables or disables the No Chat Reports feature");
            noChatReportsAddQueryData = getBoolean(ConfigCategory.NETWORK.key("no-chat-reports.add-query-data"), noChatReportsAddQueryData,
                "Should server include extra query data to help clients know that your server is secure");
            noChatReportsConvertToGameMessage = getBoolean(ConfigCategory.NETWORK.key("no-chat-reports.convert-to-game-message"), noChatReportsConvertToGameMessage,
                "Should the server convert all player messages to system messages");
            noChatReportsDebugLog = getBoolean(ConfigCategory.NETWORK.key("no-chat-reports.debug-log"), noChatReportsDebugLog);
            noChatReportsDemandOnClient = getBoolean(ConfigCategory.NETWORK.key("no-chat-reports.demand-on-client"), noChatReportsDemandOnClient,
                "Should the server require No Chat Reports on the client side");
            noChatReportsDisconnectDemandOnClientMessage = getString(ConfigCategory.NETWORK.key("no-chat-reports.disconnect-demand-on-client-message"), noChatReportsDisconnectDemandOnClientMessage,
                "Message to send to the client when they are disconnected for not having No Chat Reports");
        }

        private static void protocols() {
            protocolsStrictMode = getBoolean(ConfigCategory.NETWORK.key("protocols.strict-mode"), protocolsStrictMode);

            // AppleSkin
            protocolsAppleSkinEnabled = getBoolean(ConfigCategory.NETWORK.key("protocols.appleskin.appleskin-enable"), protocolsAppleSkinEnabled,
                "Enables AppleSkin protocol support");
            protocolsAppleSkinSyncTickInterval = getInt(ConfigCategory.NETWORK.key("protocols.appleskin.sync-tick-interval"), protocolsAppleSkinSyncTickInterval,
                "Sync tick interval for AppleSkin protocol");

            // Jade
            protocolsJadeEnabled = getBoolean(ConfigCategory.NETWORK.key("protocols.jade.jade-enable"), protocolsJadeEnabled,
                "Enables Jade protocol support");

            // Xaero's Map
            protocolsMapsXaeroMapEnabled = getBoolean(ConfigCategory.NETWORK.key("protocols.xaeromap.xaeromap-enable"), protocolsMapsXaeroMapEnabled,
                "Enables Xaero's Map protocol support");
            protocolsMapsXaeroMapServerId = getInt(ConfigCategory.NETWORK.key("protocols.xaeromap.xaero-map-server-id"), protocolsMapsXaeroMapServerId,
                "Server ID for Xaero's Map protocol");

            // Syncmatica
            protocolsSyncMaticaEnabled = getBoolean(ConfigCategory.NETWORK.key("protocols.syncmatica.syncmatica-enable"), protocolsSyncMaticaEnabled,
                "Enables SyncMatica protocol support");
            protocolsSyncMaticaQuota = getBoolean(ConfigCategory.NETWORK.key("protocols.syncmatica.quota"), protocolsSyncMaticaQuota,
                "Enables quota system for SyncMatica");
            protocolsSyncMaticaQuotaLimit = getInt(ConfigCategory.NETWORK.key("protocols.syncmatica.quota-limit"), protocolsSyncMaticaQuotaLimit,
                "Quota limit for SyncMatica protocol");
        }

        private static void raytraceCulling() {
            raytraceCullingEnabled = getBoolean(ConfigCategory.NETWORK.key("raytrace-entity-culling.enabled"), raytraceCullingEnabled,
                "Enables Raytrace Entity Culling, a server-side port of tr7zw's EntityCulling mod.",
                "Entities that a player provably cannot see (fully hidden behind opaque blocks)",
                "are removed from that player's entity tracker. This reduces network bandwidth,",
                "improves client FPS and blinds entity-ESP cheats (e.g. freecam/x-ray style hacks",
                "cannot see mobs, players or items through walls anymore).",
                "",
                "Visibility is checked asynchronously via raytracing; when a hidden entity comes",
                "into view it is re-tracked within one check interval plus one tracker tick.",
                "Players with very high ping may notice entities appearing slightly late when",
                "peeking around corners.");
            raytraceCullingThreads = getInt(ConfigCategory.NETWORK.key("raytrace-entity-culling.threads"), raytraceCullingThreads,
                "The number of threads used for visibility raytracing. All players share this pool.",
                "Set to 0 to determine automatically based on CPU core count.");
            raytraceCullingCheckIntervalMs = getInt(ConfigCategory.NETWORK.key("raytrace-entity-culling.check-interval-ms"), raytraceCullingCheckIntervalMs,
                "The interval in milliseconds between visibility passes for each player.",
                "Lower values make hidden entities appear faster when they come into view,",
                "at the cost of more raytracing work.");
            raytraceCullingMaxTraceDistance = getInt(ConfigCategory.NETWORK.key("raytrace-entity-culling.max-trace-distance"), raytraceCullingMaxTraceDistance,
                "The maximum distance in blocks at which entities are raytraced.",
                "Entities beyond this distance are always visible (never culled).",
                "NOTE: each player allocates an occlusion cache of (2*distance)^3 / 4 bytes",
                "(~512KB at 64), so keep this value reasonable.");
            raytraceCullingMaxTraceDistance = Math.clamp(raytraceCullingMaxTraceDistance, 16, 160);
            raytraceCullingForceVisibleRadius = getDouble(ConfigCategory.NETWORK.key("raytrace-entity-culling.force-visible-radius"), raytraceCullingForceVisibleRadius,
                "Entities within this radius of the player are always visible, no raytracing done.");
            raytraceCullingVisibilityTimeoutMs = getLong(ConfigCategory.NETWORK.key("raytrace-entity-culling.visibility-timeout-ms"), raytraceCullingVisibilityTimeoutMs,
                "Once an entity is proven visible it stays visible for at least this many",
                "milliseconds before it can be culled again. Prevents track/untrack packet",
                "flicker for entities on the edge of visibility.");
            raytraceCullingAabbExpansion = getDouble(ConfigCategory.NETWORK.key("raytrace-entity-culling.aabb-expansion"), raytraceCullingAabbExpansion,
                "How much the entity bounding box is expanded before raytracing. Larger values",
                "make entities visible slightly earlier around corners (smoother but less strict).");
            raytraceCullingBoundingBoxLimit = getInt(ConfigCategory.NETWORK.key("raytrace-entity-culling.bounding-box-limit"), raytraceCullingBoundingBoxLimit,
                "Entities with a bounding box larger than this on any axis are never culled.");
            raytraceCullingCullPlayers = getBoolean(ConfigCategory.NETWORK.key("raytrace-entity-culling.cull-players"), raytraceCullingCullPlayers,
                "Whether player entities may be culled too. This is the main anti-ESP protection,",
                "but on PvP servers with high-ping players it can give a slight disadvantage when",
                "peeking around corners - disable it if that matters more to you.");
            raytraceCullingHideCulledPlayersOnLocatorBar = getBoolean(ConfigCategory.NETWORK.key("raytrace-entity-culling.hide-culled-players-on-locator-bar"), raytraceCullingHideCulledPlayersOnLocatorBar,
                "Whether a culled player also disappears from the locator bar of the player who",
                "cannot see them. Off (default) keeps vanilla behaviour: the locator bar shows every",
                "player in range, including ones hidden behind walls. On hides their direction too.");
            raytraceCullingSkipMarkerArmorStands = getBoolean(ConfigCategory.NETWORK.key("raytrace-entity-culling.skip-marker-armor-stands"), raytraceCullingSkipMarkerArmorStands,
                "Whether invisible armor stands (often used as plugin markers/holograms) are",
                "always visible and never raytraced.");
            raytraceCullingSkippedEntityTypes = getStringList(ConfigCategory.NETWORK.key("raytrace-entity-culling.skipped-entity-types"), raytraceCullingSkippedEntityTypes,
                "Entity types that are never culled, e.g. [\"minecraft:item_frame\", \"minecraft:villager\"].",
                "Glowing entities, entities with always-visible custom names, display entities and",
                "fishing bobbers are always skipped regardless of this list.");

            for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
                entityType.raytraceCullingSkip = false;
            }

            final String DEFAULT_PREFIX = Identifier.DEFAULT_NAMESPACE + Identifier.NAMESPACE_SEPARATOR;
            for (String name : raytraceCullingSkippedEntityTypes) {
                String lowerName = name.toLowerCase(Locale.ROOT);
                String typeId = lowerName.startsWith(DEFAULT_PREFIX) ? lowerName : DEFAULT_PREFIX + lowerName;

                EntityType.byString(typeId).ifPresentOrElse(entityType -> entityType.raytraceCullingSkip = true, () -> LOGGER.warn("Unknown entity {}, in {}", name, ConfigCategory.NETWORK.key("raytrace-entity-culling.skipped-entity-types")));
            }
        }
    }

    private static void checkExperimentalFeatures() {
        List<String> enabledExperimentalFeatures = new ArrayList<>();

        Class<?>[] innerClasses = DivineConfig.class.getDeclaredClasses();
        for (Class<?> innerClass : innerClasses) {
            if (Modifier.isStatic(innerClass.getModifiers())) {
                Field[] fields = innerClass.getDeclaredFields();
                for (Field field : fields) {
                    if (field.isAnnotationPresent(Experimental.class) &&
                        field.getType() == boolean.class &&
                        Modifier.isStatic(field.getModifiers()) &&
                        Modifier.isPublic(field.getModifiers())) {
                        try {
                            field.setAccessible(true);
                            boolean value = field.getBoolean(null);
                            if (value) {
                                Experimental annotation = field.getAnnotation(Experimental.class);
                                String featureName = annotation.value();
                                enabledExperimentalFeatures.add(featureName);
                            }
                        } catch (IllegalAccessException e) {
                            LOGGER.debug("Failed to access field {}", field.getName(), e);
                        }
                    }
                }
            }
        }

        if (!enabledExperimentalFeatures.isEmpty()) {
            LOGGER.warn("You have the following experimental features enabled: [{}]. Please proceed with caution!", String.join(", ", enabledExperimentalFeatures));
        }
    }
}
