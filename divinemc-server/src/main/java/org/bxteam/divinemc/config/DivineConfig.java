package org.bxteam.divinemc.config;

import com.google.common.base.Throwables;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bxteam.divinemc.entity.pathfinding.PathfindTaskRejectPolicy;
import org.bxteam.divinemc.region.LinearImplementation;
import org.bxteam.divinemc.server.chunk.ChunkSystemAlgorithms;
import org.bxteam.divinemc.server.chunk.ChunkTaskPriority;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.comments.CommentType;
import org.simpleyaml.configuration.file.YamlFile;
import org.simpleyaml.exceptions.InvalidConfigurationException;
import org.bxteam.divinemc.region.RegionFileFormat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressWarnings({"unused", "SameParameterValue"})
public class DivineConfig {
    private static final String HEADER = """
        This is the main configuration file for DivineMC.
        If you need help with the configuration or have any questions related to DivineMC,
        join us in our Discord server.

        Discord: https://discord.gg/qNyybSSPm5
        Docs: https://bxteam.org/docs/divinemc
        Downloads: https://github.com/BX-Team/DivineMC/releases""";

    public static final Logger LOGGER = LogManager.getLogger(DivineConfig.class.getSimpleName());
    public static final int CONFIG_VERSION = 6;

    private static File configFile;
    public static final YamlFile config = new YamlFile();

	private static ConfigurationSection convertToBukkit(org.simpleyaml.configuration.ConfigurationSection section) {
		ConfigurationSection newSection = new MemoryConfiguration();
		for (String key : section.getKeys(false)) {
			if (section.isConfigurationSection(key)) {
				newSection.set(key, convertToBukkit(section.getConfigurationSection(key)));
			} else {
				newSection.set(key, section.get(key));
			}
		}
		return newSection;
	}

	public static ConfigurationSection getConfigCopy() {
		return convertToBukkit(config);
	}

	public static void init(File configFile) throws IOException {
        DivineConfig.configFile = configFile;
		if (configFile.exists()) {
			try {
				config.load(configFile);
			} catch (InvalidConfigurationException e) {
				throw new IOException(e);
			}
		}

		getInt("version", CONFIG_VERSION);
        config.options().header(HEADER);

        readConfig(DivineConfig.class, null);
	}

    static void readConfig(Class<?> clazz, Object instance) throws IOException {
        for (Method method : clazz.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers()) && 
                method.getParameterTypes().length == 0 && 
                method.getReturnType() == Void.TYPE) {
                try {
                    method.setAccessible(true);
                    method.invoke(instance);
                } catch (InvocationTargetException ex) {
                    throw Throwables.propagate(ex.getCause());
                } catch (Exception ex) {
                    LOGGER.error("Error invoking {}", method, ex);
                }
            }
        }

        for (Class<?> innerClass : clazz.getDeclaredClasses()) {
            if (Modifier.isStatic(innerClass.getModifiers())) {
                try {
                    Object innerInstance = null;
                    
                    Method loadMethod = null;
                    try {
                        loadMethod = innerClass.getDeclaredMethod("load");
                    } catch (NoSuchMethodException ignored) {
                        readConfig(innerClass, null);
                        continue;
                    }

                    if (loadMethod != null) {
                        try {
                            innerInstance = innerClass.getDeclaredConstructor().newInstance();
                        } catch (NoSuchMethodException e) {
                            innerInstance = null;
                        }
                        
                        loadMethod.setAccessible(true);
                        loadMethod.invoke(innerInstance);
                    }
                } catch (Exception ex) {
                    LOGGER.error("Error processing inner class {}", innerClass.getName(), ex);
                }
            }
        }

        config.save(configFile);
    }

	private static void setComment(String key, String... comment) {
		if (config.contains(key)) {
			config.setComment(key, String.join("\n", comment), CommentType.BLOCK);
		}
	}

    private static void ensureDefault(String key, Object defaultValue, String... comment) {
        if (!config.contains(key)) config.set(key, defaultValue);
        if (comment.length > 0) config.setComment(key, String.join("\n", comment), CommentType.BLOCK);
    }

	private static boolean getBoolean(String key, boolean defaultValue, String... comment) {
		return getBoolean(key, null, defaultValue, comment);
	}

	private static boolean getBoolean(String key, @Nullable String oldKey, boolean defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getBoolean(key, defaultValue);
	}

	private static int getInt(String key, int defaultValue, String... comment) {
		return getInt(key, null, defaultValue, comment);
	}

	private static int getInt(String key, @Nullable String oldKey, int defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getInt(key, defaultValue);
	}

	private static double getDouble(String key, double defaultValue, String... comment) {
		return getDouble(key, null, defaultValue, comment);
	}

	private static double getDouble(String key, @Nullable String oldKey, double defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getDouble(key, defaultValue);
	}

    private static long getLong(String key, long defaultValue, String... comment) {
        return getLong(key, null, defaultValue, comment);
    }

    private static long getLong(String key, @Nullable String oldKey, long defaultValue, String... comment) {
        ensureDefault(key, defaultValue, comment);
        return config.getLong(key, defaultValue);
    }

	private static String getString(String key, String defaultValue, String... comment) {
		return getOldString(key, null, defaultValue, comment);
	}

	private static String getOldString(String key, @Nullable String oldKey, String defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getString(key, defaultValue);
	}

	private static List<String> getStringList(String key, List<String> defaultValue, String... comment) {
		return getStringList(key, null, defaultValue, comment);
	}

	private static List<String> getStringList(String key, @Nullable String oldKey, List<String> defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getStringList(key);
	}

    public static class AsyncCategory {
        // Parallel world ticking settings
        public static boolean enableParallelWorldTicking = false;
        public static int parallelThreadCount = 4;
        public static boolean logContainerCreationStacktraces = false;
        public static boolean disableHardThrow = false;
        public static boolean pwtCompatabilityMode = false;
        public static boolean usePerWorldTpsBar = true;

        // Regionized chunk ticking
        public static boolean enableRegionizedChunkTicking = false;
        public static int regionizedChunkTickingExecutorThreadCount = 4;
        public static int regionizedChunkTickingExecutorThreadPriority = Thread.NORM_PRIORITY + 2;

        // Async pathfinding settings
        public static boolean asyncPathfinding = true;
        public static int asyncPathfindingMaxThreads = 2;
        public static int asyncPathfindingKeepalive = 60;
        public static int asyncPathfindingQueueSize = 0;
        public static PathfindTaskRejectPolicy asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.FLUSH_ALL;

        // Multithreaded tracker settings
        public static boolean multithreadedEnabled = true;
        public static boolean multithreadedCompatModeEnabled = false;
        public static int asyncEntityTrackerMaxThreads = 1;
        public static int asyncEntityTrackerKeepalive = 60;
        public static int asyncEntityTrackerQueueSize = 0;

        // Async chunk sending settings
        public static boolean asyncChunkSendingEnabled = true;

        // Async mob spawning settings
        public static boolean enableAsyncSpawning = true;

        public static void load() {
            parallelWorldTicking();
            regionizedChunkTicking();
            asyncPathfinding();
            multithreadedTracker();
            asyncChunkSending();
            asyncMobSpawning();
        }

        private static void parallelWorldTicking() {
            enableParallelWorldTicking = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.enable"), enableParallelWorldTicking,
                "Enables Parallel World Ticking, which executes each world's tick in a separate thread while ensuring that all worlds complete their tick before the next cycle begins.",
                "",
                "Read more info about this feature at https://bxteam.org/docs/divinemc/features/parallel-world-ticking");
            parallelThreadCount = getInt(ConfigCategory.ASYNC.key("parallel-world-ticking.thread-count"), parallelThreadCount);
            logContainerCreationStacktraces = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.log-container-creation-stacktraces"), logContainerCreationStacktraces);
            disableHardThrow = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.disable-hard-throw"), disableHardThrow,
                "Disables annoying 'not on main thread' throws. But, THIS IS NOT RECOMMENDED because you SHOULD FIX THE ISSUES THEMSELVES instead of RISKING DATA CORRUPTION! If you lose something, take the blame on yourself.");
            pwtCompatabilityMode = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.compatability-mode"), pwtCompatabilityMode,
                "Enables compatibility mode for plugins that are not compatible with Parallel World Ticking. This makes all async tasks run synchronously.");
            usePerWorldTpsBar = getBoolean(ConfigCategory.ASYNC.key("parallel-world-ticking.use-per-world-tps-bar"), usePerWorldTpsBar,
                "Enables per-world TPS bar, which shows the TPS of the world the player is currently in. TPS bar can be turned on/off with /tpsbar command.");
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

            if (regionizedChunkTickingExecutorThreadCount < 1 || regionizedChunkTickingExecutorThreadCount > 10) {
                LOGGER.warn("Invalid regionized chunk ticking thread count: {}, resetting to default (5)", regionizedChunkTickingExecutorThreadCount);
                regionizedChunkTickingExecutorThreadCount = 5;
            }

            if (enableRegionizedChunkTicking) LOGGER.warn("You have enabled Regionized Chunk Ticking. This feature is an experimental, and may not work as expected. Please report any issues you encounter to the BX Team Discord server");
        }

        private static void asyncPathfinding() {
            asyncPathfinding = getBoolean(ConfigCategory.ASYNC.key("pathfinding.enable"), asyncPathfinding);
            asyncPathfindingMaxThreads = getInt(ConfigCategory.ASYNC.key("pathfinding.max-threads"), asyncPathfindingMaxThreads);
            asyncPathfindingKeepalive = getInt(ConfigCategory.ASYNC.key("pathfinding.keepalive"), asyncPathfindingKeepalive);
            asyncPathfindingQueueSize = getInt(ConfigCategory.ASYNC.key("pathfinding.queue-size"), asyncPathfindingQueueSize);

            final int maxThreads = Runtime.getRuntime().availableProcessors();
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

            asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.fromString(getString(ConfigCategory.ASYNC.key("pathfinding.reject-policy"), maxThreads >= 12 && asyncPathfindingQueueSize < 512 ? PathfindTaskRejectPolicy.FLUSH_ALL.toString() : PathfindTaskRejectPolicy.CALLER_RUNS.toString(),
                "The policy to use when the queue is full and a new task is submitted.",
                "FLUSH_ALL: All pending tasks will be run on server thread.",
                "CALLER_RUNS: Newly submitted task will be run on server thread."));
        }

        private static void multithreadedTracker() {
            multithreadedEnabled = getBoolean(ConfigCategory.ASYNC.key("multithreaded-tracker.enable"), multithreadedEnabled,
                "Make entity tracking saving asynchronously, can improve performance significantly,",
                "especially in some massive entities in small area situations.");
            multithreadedCompatModeEnabled = getBoolean(ConfigCategory.ASYNC.key("multithreaded-tracker.compat-mode"), multithreadedCompatModeEnabled,
                "Enable compat mode ONLY if Citizens or NPC plugins using real entity has installed.",
                "Compat mode fixes visible issues with player type NPCs of Citizens.",
                "But we recommend to use packet based / virtual entity NPC plugin, e.g. ZNPC Plus, Adyeshach, Fancy NPC and etc.");

            asyncEntityTrackerMaxThreads = getInt(ConfigCategory.ASYNC.key("multithreaded-tracker.max-threads"), asyncEntityTrackerMaxThreads);
            asyncEntityTrackerKeepalive = getInt(ConfigCategory.ASYNC.key("multithreaded-tracker.keepalive"), asyncEntityTrackerKeepalive);
            asyncEntityTrackerQueueSize = getInt(ConfigCategory.ASYNC.key("multithreaded-tracker.queue-size"), asyncEntityTrackerQueueSize);

            if (asyncEntityTrackerMaxThreads < 0) {
                asyncEntityTrackerMaxThreads = Math.max(Runtime.getRuntime().availableProcessors() + asyncEntityTrackerMaxThreads, 1);
            } else if (asyncEntityTrackerMaxThreads == 0) {
                asyncEntityTrackerMaxThreads = Math.max(Runtime.getRuntime().availableProcessors() / 4, 1);
            }

            if (!multithreadedEnabled) {
                asyncEntityTrackerMaxThreads = 0;
            } else {
                LOGGER.info("Using {} threads for Async Entity Tracker", asyncEntityTrackerMaxThreads);
            }

            if (asyncEntityTrackerQueueSize <= 0) asyncEntityTrackerQueueSize = asyncEntityTrackerMaxThreads * 384;
        }

        private static void asyncChunkSending() {
            asyncChunkSendingEnabled = getBoolean(ConfigCategory.ASYNC.key("chunk-sending.enable"), asyncChunkSendingEnabled,
                "Makes chunk sending asynchronous, which can significantly reduce main thread load when many players are loading chunks.");
        }

        private static void asyncMobSpawning() {
            enableAsyncSpawning = getBoolean(ConfigCategory.ASYNC.key("mob-spawning.enable"), enableAsyncSpawning,
                "Enables optimization that will offload much of the computational effort involved with spawning new mobs to a different thread.");
        }
    }

    public static class PerformanceCategory {
        // Chunk settings
        public static long chunkDataCacheSoftLimit = 8192L;
        public static long chunkDataCacheLimit = 32678L;
        public static int maxViewDistance = 32;
        public static int playerNearChunkDetectionRange = 128;
        public static ChunkSystemAlgorithms chunkWorkerAlgorithm = ChunkSystemAlgorithms.C2ME;
        public static ChunkTaskPriority chunkTaskPriority = ChunkTaskPriority.EUCLIDEAN_CIRCLE_PATTERN;
        public static int threadPoolPriority = Thread.NORM_PRIORITY + 1;
        public static boolean smoothBedrockLayer = false;
        public static boolean enableDensityFunctionCompiler = false;
        public static boolean enableStructureLayoutOptimizer = true;
        public static boolean deduplicateShuffledTemplatePoolElementList = false;

        // TNT optimization
        public static boolean enableFasterTntOptimization = true;
        public static boolean explosionNoBlockDamage = false;
        public static double tntRandomRange = -1;

        // General optimizations
        public static boolean skipUselessSecondaryPoiSensor = true;
        public static boolean clumpOrbs = true;
        public static boolean enableSuffocationOptimization = true;
        public static boolean useCompactBitStorage = false;
        public static boolean commandBlockParseResultsCaching = true;
        public static boolean sheepOptimization = true;
        public static boolean optimizedDragonRespawn = false;
        public static boolean reduceChuckLoadAndLookup = true;
        public static boolean hopperThrottleWhenFull = false;
        public static int hopperThrottleSkipTicks = 0;

        // DAB settings
        public static boolean dabEnabled = true;
        public static int dabStartDistance = 12;
        public static int dabStartDistanceSquared;
        public static int dabMaximumActivationFrequency = 20;
        public static int dabActivationDistanceMod = 8;
        public static boolean dabDontEnableIfInWater = false;
        public static List<String> dabBlackedEntities = new ArrayList<>();

        // Virtual threads
        public static boolean virtualThreadsEnabled = false;
        public static boolean virtualBukkitScheduler = false;
        public static boolean virtualChatScheduler = false;
        public static boolean virtualAuthenticatorScheduler = false;
        public static boolean virtualTabCompleteScheduler = false;
        public static boolean virtualAsyncExecutor = false;
        public static boolean virtualCommandBuilderScheduler = false;
        public static boolean virtualProfileLookupPool = false;
        public static boolean virtualServerTextFilterPool = false;

        public static void load() {
            chunkSettings();
            tntOptimization();
            optimizationSettings();
            dab();
            virtualThreads();
        }

        private static void chunkSettings() {
            chunkDataCacheSoftLimit = getLong(ConfigCategory.PERFORMANCE.key("chunks.chunk-data-cache-soft-limit"), chunkDataCacheSoftLimit);
            chunkDataCacheLimit = getLong(ConfigCategory.PERFORMANCE.key("chunks.chunk-data-cache-limit"), chunkDataCacheLimit);
            maxViewDistance = getInt(ConfigCategory.PERFORMANCE.key("chunks.max-view-distance"), maxViewDistance,
                "Changes the maximum view distance for the server, allowing clients to have render distances higher than 32");
            playerNearChunkDetectionRange = getInt(ConfigCategory.PERFORMANCE.key("chunks.player-near-chunk-detection-range"), playerNearChunkDetectionRange,
                "In certain checks, like if a player is near a chunk(primarily used for spawning), it checks if the player is within a certain",
                "circular range of the chunk. This configuration allows configurability of the distance(in blocks) the player must be to pass the check.",
                "",
                "This value is used in the calculation 'range/16' to get the distance in chunks any player must be to allow the check to pass.",
                "By default, this range is computed to 8, meaning a player must be within an 8 chunk radius of a chunk position to pass.",
                "Keep in mind the result is rounded to the nearest whole number.");

            if (playerNearChunkDetectionRange < 0) {
                LOGGER.warn("Invalid player near chunk detection range: {}, resetting to default (128)", playerNearChunkDetectionRange);
                playerNearChunkDetectionRange = 128;
            }

            chunkWorkerAlgorithm = ChunkSystemAlgorithms.valueOf(getString(ConfigCategory.PERFORMANCE.key("chunks.chunk-worker-algorithm"), chunkWorkerAlgorithm.name(),
                "Modifies what algorithm the chunk system will use to define thread counts.",
                "Valid values:",
                " - MOONRISE: Default algorithm, used by default in Paper",
                " - C2ME: Algorithm used by C2ME (old)",
                " - C2ME_NEW: Modern algorithm used by C2ME"));
            chunkTaskPriority = ChunkTaskPriority.valueOf(getString(ConfigCategory.PERFORMANCE.key("chunks.chunk-task-priority"), chunkTaskPriority.name(),
                "Sets the algorithm for determining chunk task priorities (generation, loading and etc.).",
                "Valid values:",
                " - EUCLIDEAN_CIRCLE_PATTERN: Euclidean distance squared algorithm, chunk priorities will be ordered in a circle pattern",
                " - DEFAULT_DIAMOND_PATTERN: Default one, chunk priorities will be ordered in a diamond pattern"));
            threadPoolPriority = getInt(ConfigCategory.PERFORMANCE.key("chunks.thread-pool-priority"), threadPoolPriority,
                "Sets the priority of the thread pool used for chunk generation");

            smoothBedrockLayer = getBoolean(ConfigCategory.PERFORMANCE.key("chunks.smooth-bedrock-layer"), smoothBedrockLayer,
                "Smoothens the bedrock layer at the bottom of overworld, and on the top of nether during the world generation.");

            enableDensityFunctionCompiler = getBoolean(ConfigCategory.PERFORMANCE.key("chunks.experimental.enable-density-function-compiler"), enableDensityFunctionCompiler,
                "Whether to use density function compiler to accelerate world generation",
                "",
                "Density function: https://minecraft.wiki/w/Density_function",
                "",
                "This functionality compiles density functions from world generation",
                "datapacks (including vanilla generation) to JVM bytecode to increase",
                "performance by allowing JVM JIT to better optimize the code.",
                "All functions provided by vanilla are implemented.");
            enableStructureLayoutOptimizer = getBoolean(ConfigCategory.PERFORMANCE.key("chunks.experimental.enable-structure-layout-optimizer"), enableStructureLayoutOptimizer,
                "Enables a port of the mod StructureLayoutOptimizer, which optimizes general Jigsaw structure generation");
            deduplicateShuffledTemplatePoolElementList = getBoolean(ConfigCategory.PERFORMANCE.key("chunks.experimental.deduplicate-shuffled-template-pool-element-list"), deduplicateShuffledTemplatePoolElementList,
                "Whether to use an alternative strategy to make structure layouts generate slightly even faster than",
                "the default optimization this mod has for template pool weights. This alternative strategy works by",
                "changing the list of pieces that structures collect from the template pool to not have duplicate entries.",
                "",
                "This will not break the structure generation, but it will make the structure layout different than",
                "if this config was off (breaking vanilla seed parity). The cost of speed may be worth it in large",
                "modpacks where many structure mods are using very high weight values in their template pools.");
        }

        private static void tntOptimization() {
            enableFasterTntOptimization = getBoolean(ConfigCategory.PERFORMANCE.key("tnt-optimization.enable-faster-tnt-optimization"), enableFasterTntOptimization);
            explosionNoBlockDamage = getBoolean(ConfigCategory.PERFORMANCE.key("tnt-optimization.explosion-no-block-damage"), explosionNoBlockDamage);
            tntRandomRange = getDouble(ConfigCategory.PERFORMANCE.key("tnt-optimization.tnt-random-range"), tntRandomRange);
        }

        private static void optimizationSettings() {
            skipUselessSecondaryPoiSensor = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.skip-useless-secondary-poi-sensor"), skipUselessSecondaryPoiSensor);
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
            optimizedDragonRespawn = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.optimized-dragon-respawn"), optimizedDragonRespawn,
                "When enabled, improving performance and reducing lag during the dragon’s resurrection event.");
            reduceChuckLoadAndLookup = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.reduce-chunk-load-and-lookup"), reduceChuckLoadAndLookup,
                "If enabled, optimizes chunk loading and block state lookups by reducing the number of chunk accesses required during operations such as Enderman teleportation.");

            hopperThrottleWhenFull = getBoolean(ConfigCategory.PERFORMANCE.key("optimizations.hopper-throttle-when-full.enabled"), hopperThrottleWhenFull,
                "When enabled, hoppers will throttle if target container is full.");
            hopperThrottleSkipTicks = getInt(ConfigCategory.PERFORMANCE.key("optimizations.hopper-throttle-when-full.skip-ticks"), hopperThrottleSkipTicks,
                "The amount of ticks to skip when the hopper is throttled.");
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

            final String DEFAULT_PREFIX = ResourceLocation.DEFAULT_NAMESPACE + ResourceLocation.NAMESPACE_SEPARATOR;
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
            virtualAuthenticatorScheduler = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.authenticator-scheduler"), virtualAuthenticatorScheduler,
                "Uses virtual threads for the Authenticator scheduler.");
            virtualTabCompleteScheduler = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.tab-complete-scheduler"), virtualTabCompleteScheduler,
                "Uses virtual threads for the Tab Complete scheduler.");
            virtualAsyncExecutor = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.async-executor"), virtualAsyncExecutor,
                "Uses virtual threads for the MCUtil async executor.");
            virtualCommandBuilderScheduler = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.command-builder-scheduler"), virtualCommandBuilderScheduler,
                "Uses virtual threads for the Async Command Builder Thread Pool.");
            virtualProfileLookupPool = getBoolean(ConfigCategory.PERFORMANCE.key("virtual-threads.profile-lookup-pool"), virtualProfileLookupPool,
                "Uses virtual threads for the Profile Lookup Pool, that is used for fetching player profiles.");
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

        // Bug fixes (MC-*)
        public static boolean slopesVisualFix = false;

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
            slopesVisualFix = getBoolean(ConfigCategory.FIXES.key("bug.fix-mc-258859"), slopesVisualFix,
                "Fixes MC-258859, fixing slopes visual bug in biomes like Snowy Slopes, Frozen Peaks, Jagged Peaks, and including Terralith.");
        }
    }

    public static class MiscCategory {
        // Secure seed
        public static boolean enableSecureSeed = false;

        // Lag compensation
        public static boolean lagCompensationEnabled = true;
        public static boolean blockEntityAcceleration = false;
        public static boolean blockBreakingAcceleration = true;
        public static boolean eatingAcceleration = true;
        public static boolean potionEffectAcceleration = true;
        public static boolean fluidAcceleration = true;
        public static boolean pickupAcceleration = true;
        public static boolean portalAcceleration = true;
        public static boolean timeAcceleration = true;
        public static boolean randomTickSpeedAcceleration = true;

        // Region format
        public static RegionFileFormat regionFormatTypeName = RegionFileFormat.ANVIL;
        public static LinearImplementation linearImplementation = LinearImplementation.V2;
        public static int linearFlushMaxThreads = 4;
        public static int linearFlushDelay = 100;
        public static boolean linearUseVirtualThread = false;
        public static int linearCompressionLevel = 1;

        // Sentry
        public static String sentryDsn = "";
        public static String logLevel = "WARN";
        public static boolean onlyLogThrown = true;

        public static void load() {
            secureSeed();
            lagCompensation();
            linearRegionFormat();
            sentrySettings();
        }

        private static void secureSeed() {
            enableSecureSeed = getBoolean(ConfigCategory.MISC.key("secure-seed.enable"), enableSecureSeed,
                "This feature is based on Secure Seed mod by Earthcomputer.",
                "",
                "Terrain and biome generation remains the same, but all the ores and structures are generated with 1024-bit seed, instead of the usual 64-bit seed.",
                "This seed is almost impossible to crack, and there are no weird links between structures.");
        }

        private static void lagCompensation() {
            lagCompensationEnabled = getBoolean(ConfigCategory.MISC.key("lag-compensation.enabled"), lagCompensationEnabled, 
                "Improves the player experience when TPS is low");
            blockEntityAcceleration = getBoolean(ConfigCategory.MISC.key("lag-compensation.block-entity-acceleration"), blockEntityAcceleration);
            blockBreakingAcceleration = getBoolean(ConfigCategory.MISC.key("lag-compensation.block-breaking-acceleration"), blockBreakingAcceleration);
            eatingAcceleration = getBoolean(ConfigCategory.MISC.key("lag-compensation.eating-acceleration"), eatingAcceleration);
            potionEffectAcceleration = getBoolean(ConfigCategory.MISC.key("lag-compensation.potion-effect-acceleration"), potionEffectAcceleration);
            fluidAcceleration = getBoolean(ConfigCategory.MISC.key("lag-compensation.fluid-acceleration"), fluidAcceleration);
            pickupAcceleration = getBoolean(ConfigCategory.MISC.key("lag-compensation.pickup-acceleration"), pickupAcceleration);
            portalAcceleration = getBoolean(ConfigCategory.MISC.key("lag-compensation.portal-acceleration"), portalAcceleration);
            timeAcceleration = getBoolean(ConfigCategory.MISC.key("lag-compensation.time-acceleration"), timeAcceleration);
            randomTickSpeedAcceleration = getBoolean(ConfigCategory.MISC.key("lag-compensation.random-tick-speed-acceleration"), randomTickSpeedAcceleration);
        }

        private static void linearRegionFormat() {
            regionFormatTypeName = RegionFileFormat.fromName(getString(ConfigCategory.MISC.key("region-format.type"), regionFormatTypeName.name(),
                "The type of region file format to use for storing chunk data.",
                "Valid values:",
                " - LINEAR: Linear region file format",
                " - ANVIL: Anvil region file format (default)"));
            linearImplementation = LinearImplementation.valueOf(getString(ConfigCategory.MISC.key("region-format.implementation"), linearImplementation.name(),
                "The implementation of the linear region file format to use.",
                "Valid values:",
                " - V1: Basic and default linear implementation",
                " - V2: Introduces a grid-based compression scheme for better data management and flexibility (default)"));

            linearFlushMaxThreads = getInt(ConfigCategory.MISC.key("region-format.flush-max-threads"), linearFlushMaxThreads,
                "The maximum number of threads to use for flushing linear region files.",
                "If this value is less than or equal to 0, it will be set to the number of available processors + this value.");
            linearFlushDelay = getInt(ConfigCategory.MISC.key("region-format.flush-delay"), linearFlushDelay,
                "The delay in milliseconds to wait before flushing next files.");
            linearUseVirtualThread = getBoolean(ConfigCategory.MISC.key("region-format.use-virtual-thread"), linearUseVirtualThread,
                "Whether to use virtual threads for flushing.");
            linearCompressionLevel = getInt(ConfigCategory.MISC.key("region-format.compression-level"), linearCompressionLevel,
                "The compression level to use for the linear region file format.");

            setComment(ConfigCategory.MISC.key("region-format"),
                "The linear region file format is a custom region file format that is designed to be more efficient than the ANVIL format.",
                "It uses uses ZSTD compression instead of ZLIB. This format saves about 50% of disk space.",
                "Read more information about linear region format at https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools",
                "WARNING: If you are want to use this format, make sure to create backup of your world before switching to it, there is potential risk to lose chunk data.");

            if (regionFormatTypeName == RegionFileFormat.UNKNOWN) {
                LOGGER.error("Unknown region file type: {}, falling back to ANVIL format.", regionFormatTypeName);
                regionFormatTypeName = RegionFileFormat.ANVIL;
            }

            if (linearFlushMaxThreads <= 0) {
                linearFlushMaxThreads = Math.max(Runtime.getRuntime().availableProcessors() + linearFlushMaxThreads, 1);
            }

            if (linearCompressionLevel > 22 || linearCompressionLevel < 1) {
                LOGGER.warn("Invalid linear compression level: {}, resetting to default (1)", linearCompressionLevel);
                linearCompressionLevel = 1;
            }
        }

        private static void sentrySettings() {
            sentryDsn = getString(ConfigCategory.MISC.key("sentry.dsn"), sentryDsn,
                "The DSN for Sentry, a service that provides real-time crash reporting that helps you monitor and fix crashes in real time. Leave blank to disable. Obtain link at https://sentry.io");
            logLevel = getString(ConfigCategory.MISC.key("sentry.log-level"), logLevel,
                "Logs with a level higher than or equal to this level will be recorded.");
            onlyLogThrown = getBoolean(ConfigCategory.MISC.key("sentry.only-log-thrown"), onlyLogThrown,
                "Only log Throwable exceptions to Sentry.");

            if (sentryDsn != null && !sentryDsn.isBlank()) gg.pufferfish.pufferfish.sentry.SentryManager.init(Level.getLevel(logLevel));
        }
    }

    public static class NetworkCategory {
        // General network settings
        public static boolean disableDisconnectSpam = false;
        public static boolean gracefulTeleportHandling = false;
        public static boolean dontRespondPingBeforeStart = true;
        public static boolean playerProfileResultCachingEnabled = true;
        public static int playerProfileResultCachingTimeout = 1440;

        // No chat reports
        public static boolean noChatReportsEnabled = false;
        public static boolean noChatReportsAddQueryData = true;
        public static boolean noChatReportsConvertToGameMessage = true;
        public static boolean noChatReportsDebugLog = false;
        public static boolean noChatReportsDemandOnClient = false;
        public static String noChatReportsDisconnectDemandOnClientMessage = "You do not have No Chat Reports, and this server is configured to require it on client!";

        public static void load() {
            networkSettings();
            noChatReports();
        }

        private static void networkSettings() {
            disableDisconnectSpam = getBoolean(ConfigCategory.NETWORK.key("general.disable-disconnect-spam"), disableDisconnectSpam,
                "Prevents players being disconnected by 'disconnect.spam' when sending too many chat packets");
            gracefulTeleportHandling = getBoolean(ConfigCategory.NETWORK.key("general.graceful-teleport-handling"), gracefulTeleportHandling,
                "Disables being disconnected from 'multiplayer.disconnect.invalid_player_movement' (also declines the packet handling).");
            dontRespondPingBeforeStart = getBoolean(ConfigCategory.NETWORK.key("general.dont-respond-ping-before-start"), dontRespondPingBeforeStart,
                "Prevents the server from responding to pings before the server is fully booted.");

            playerProfileResultCachingEnabled = getBoolean(ConfigCategory.NETWORK.key("player-profile-result-caching.enabled"), playerProfileResultCachingEnabled,
                "Enables caching of player profile results on first join.");
            playerProfileResultCachingTimeout = getInt(ConfigCategory.NETWORK.key("player-profile-result-caching.timeout"), playerProfileResultCachingTimeout,
                "The amount of time in minutes to cache player profile results.");
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
    }
}
