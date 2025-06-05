package org.bxteam.divinemc;

import com.google.common.base.Throwables;
import gg.pufferfish.pufferfish.simd.SIMDDetection;
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
    public static final int CONFIG_VERSION = 5;

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
            if (Modifier.isPrivate(method.getModifiers())) {
                if (method.getParameterTypes().length == 0 && method.getReturnType() == Void.TYPE) {
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

    public static boolean enableParallelWorldTicking = false;
    public static int parallelThreadCount = 4;
    public static boolean logContainerCreationStacktraces = false;
    public static boolean disableHardThrow = false;
    public static boolean pwtCompatabilityMode = false;
    private static void parallelWorldTicking() {
        enableParallelWorldTicking = getBoolean("settings.parallel-world-ticking.enable", enableParallelWorldTicking,
            "Enables Parallel World Ticking, which executes each world’s tick in a separate thread while ensuring that all worlds complete their tick before the next cycle begins.",
            "",
            "Read more info about this feature at https://bxteam.org/docs/divinemc/features/parallel-world-ticking");
        parallelThreadCount = getInt("settings.parallel-world-ticking.thread-count", parallelThreadCount);
        logContainerCreationStacktraces = getBoolean("settings.parallel-world-ticking.log-container-creation-stacktraces", logContainerCreationStacktraces);
        disableHardThrow = getBoolean("settings.parallel-world-ticking.disable-hard-throw", disableHardThrow,
            "Disables annoying 'not on main thread' throws. But, THIS IS NOT RECOMMENDED because you SHOULD FIX THE ISSUES THEMSELVES instead of RISKING DATA CORRUPTION! If you lose something, take the blame on yourself.");
        pwtCompatabilityMode = getBoolean("settings.parallel-world-ticking.compatability-mode", pwtCompatabilityMode,
            "Enables compatibility mode for plugins that are not compatible with Parallel World Ticking. This makes all async tasks run synchronously.");
    }

    public static long chunkDataCacheSoftLimit = 8192L;
    public static long chunkDataCacheLimit = 32678L;
    public static int maxViewDistance = 32;
    public static int playerNearChunkDetectionRange = 128;
    public static ChunkSystemAlgorithms chunkWorkerAlgorithm = ChunkSystemAlgorithms.C2ME;
    public static ChunkTaskPriority chunkTaskPriority = ChunkTaskPriority.EUCLIDEAN_CIRCLE_PATTERN;
    public static int threadPoolPriority = Thread.NORM_PRIORITY + 1;
    public static boolean asyncChunkSendingEnabled = true;
    public static boolean enableSecureSeed = false;
    public static boolean smoothBedrockLayer = false;
    public static boolean slopesVisualFix = false;
    public static boolean enableDensityFunctionCompiler = false;
    public static boolean enableStructureLayoutOptimizer = true;
    public static boolean deduplicateShuffledTemplatePoolElementList = false;
    private static void chunkSettings() {
        chunkDataCacheSoftLimit = getLong("settings.chunks.chunk-data-cache-soft-limit", chunkDataCacheSoftLimit);
        chunkDataCacheLimit = getLong("settings.chunks.chunk-data-cache-limit", chunkDataCacheLimit);
        maxViewDistance = getInt("settings.chunks.max-view-distance", maxViewDistance,
            "Changes the maximum view distance for the server, allowing clients to have render distances higher than 32");
        playerNearChunkDetectionRange = getInt("settings.chunks.player-near-chunk-detection-range", playerNearChunkDetectionRange,
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

        chunkWorkerAlgorithm = ChunkSystemAlgorithms.valueOf(getString("settings.chunks.chunk-worker-algorithm", chunkWorkerAlgorithm.name(),
            "Modifies what algorithm the chunk system will use to define thread counts.",
            "Valid values:",
            " - MOONRISE: Default algorithm, used by default in Paper",
            " - C2ME: Algorithm used by C2ME (old)",
            " - C2ME_NEW: Modern algorithm used by C2ME"));
        chunkTaskPriority = ChunkTaskPriority.valueOf(getString("settings.chunks.chunk-task-priority", chunkTaskPriority.name(),
            "Sets the algorithm for determining chunk task priorities (generation, loading and etc.).",
            "Valid values:",
            " - EUCLIDEAN_CIRCLE_PATTERN: Euclidean distance squared algorithm, chunk priorities will be ordered in a circle pattern",
            " - DEFAULT_DIAMOND_PATTERN: Default one, chunk priorities will be ordered in a diamond pattern"));
        threadPoolPriority = getInt("settings.chunks.thread-pool-priority", threadPoolPriority,
            "Sets the priority of the thread pool used for chunk generation");

        enableSecureSeed = getBoolean("settings.chunks.enable-secure-seed", enableSecureSeed,
            "This feature is based on Secure Seed mod by Earthcomputer.",
            "",
            "Terrain and biome generation remains the same, but all the ores and structures are generated with 1024-bit seed, instead of the usual 64-bit seed.",
            "This seed is almost impossible to crack, and there are no weird links between structures.");
        asyncChunkSendingEnabled = getBoolean("settings.chunks.enable-async-chunk-sending", asyncChunkSendingEnabled,
            "Makes chunk sending asynchronous, which can significantly reduce main thread load when many players are loading chunks.");

        smoothBedrockLayer = getBoolean("settings.chunks.smooth-bedrock-layer", smoothBedrockLayer,
            "Smoothens the bedrock layer at the bottom of overworld, and on the top of nether during the world generation.");
        slopesVisualFix = getBoolean("settings.chunks.slopes-visual-fix", slopesVisualFix,
            "Fixes MC-258859, fixing slopes visual bug in biomes like Snowy Slopes, Frozen Peaks, Jagged Peaks, and including Terralith.");

        enableDensityFunctionCompiler = getBoolean("settings.chunks.experimental.enable-density-function-compiler", enableDensityFunctionCompiler,
            "Whether to use density function compiler to accelerate world generation",
            "",
            "Density function: https://minecraft.wiki/w/Density_function",
            "",
            "This functionality compiles density functions from world generation",
            "datapacks (including vanilla generation) to JVM bytecode to increase",
            "performance by allowing JVM JIT to better optimize the code.",
            "All functions provided by vanilla are implemented.");
        enableStructureLayoutOptimizer = getBoolean("settings.chunks.experimental.enable-structure-layout-optimizer", enableStructureLayoutOptimizer,
            "Enables a port of the mod StructureLayoutOptimizer, which optimizes general Jigsaw structure generation");
        deduplicateShuffledTemplatePoolElementList = getBoolean("settings.chunks.experimental.deduplicate-shuffled-template-pool-element-list", deduplicateShuffledTemplatePoolElementList,
            "Whether to use an alternative strategy to make structure layouts generate slightly even faster than",
            "the default optimization this mod has for template pool weights. This alternative strategy works by",
            "changing the list of pieces that structures collect from the template pool to not have duplicate entries.",
            "",
            "This will not break the structure generation, but it will make the structure layout different than",
            "if this config was off (breaking vanilla seed parity). The cost of speed may be worth it in large",
            "modpacks where many structure mods are using very high weight values in their template pools.");
    }

    public static boolean skipUselessSecondaryPoiSensor = true;
    public static boolean clumpOrbs = true;
    public static boolean ignoreMovedTooQuicklyWhenLagging = true;
    public static boolean alwaysAllowWeirdMovement = true;
    public static boolean updateSuppressionCrashFix = true;
    public static boolean useCompactBitStorage = false;
    public static boolean fixIncorrectBounceLogic = false;
    public static boolean forceMinecraftCommand = false;
    public static boolean disableLeafDecay = false;
    public static boolean commandBlockParseResultsCaching = true;
    public static boolean enableAsyncSpawning = true;
    public static boolean sheepOptimization = true;
    public static boolean hopperThrottleWhenFull = false;
    public static int hopperThrottleSkipTicks = 0;
    private static void miscSettings() {
        skipUselessSecondaryPoiSensor = getBoolean("settings.misc.skip-useless-secondary-poi-sensor", skipUselessSecondaryPoiSensor);
        clumpOrbs = getBoolean("settings.misc.clump-orbs", clumpOrbs,
            "Clumps experience orbs together to reduce entity count");
        ignoreMovedTooQuicklyWhenLagging = getBoolean("settings.misc.ignore-moved-too-quickly-when-lagging", ignoreMovedTooQuicklyWhenLagging,
            "Improves general gameplay experience of the player when the server is lagging, as they won't get lagged back (message 'moved too quickly')");
        alwaysAllowWeirdMovement = getBoolean("settings.misc.always-allow-weird-movement", alwaysAllowWeirdMovement,
            "Means ignoring messages like 'moved too quickly' and 'moved wrongly'");
        updateSuppressionCrashFix = getBoolean("settings.misc.update-suppression-crash-fix", updateSuppressionCrashFix);
        useCompactBitStorage = getBoolean("settings.misc.use-compact-bit-storage", useCompactBitStorage,
            "Fixes memory waste caused by sending empty chunks as if they contain blocks. Can significantly reduce memory usage.");
        fixIncorrectBounceLogic = getBoolean("settings.misc.fix-incorrect-bounce-logic", fixIncorrectBounceLogic,
            "Fixes incorrect bounce logic in SlimeBlock.");
        forceMinecraftCommand = getBoolean("settings.misc.force-minecraft-command", forceMinecraftCommand,
            "Whether to force the use of vanilla commands over plugin commands.");
        disableLeafDecay = getBoolean("settings.misc.disable-leaf-decay", disableLeafDecay,
            "Disables leaf block decay.");
        commandBlockParseResultsCaching = getBoolean("settings.misc.command-block-parse-results-caching", commandBlockParseResultsCaching,
            "Caches the parse results of command blocks, can significantly reduce performance impact.");
        enableAsyncSpawning = getBoolean("settings.misc.enable-async-spawning", enableAsyncSpawning,
            "Enables optimization that will offload much of the computational effort involved with spawning new mobs to a different thread.");
        sheepOptimization = getBoolean("settings.misc.sheep-optimization", sheepOptimization,
            "Enables optimization from Carpet Fixes mod, using a prebaked list of all the possible colors and combinations for sheep.");

        hopperThrottleWhenFull = getBoolean("settings.misc.hopper-throttle-when-full.enabled", hopperThrottleWhenFull,
            "When enabled, hoppers will throttle if target container is full.");
        hopperThrottleSkipTicks = getInt("settings.misc.hopper-throttle-when-full.skip-ticks", hopperThrottleSkipTicks,
            "The amount of ticks to skip when the hopper is throttled.");
    }

    public static String sentryDsn = "";
    public static String logLevel = "WARN";
    public static boolean onlyLogThrown = true;
    private static void sentrySettings() {
        sentryDsn = getString("settings.sentry.dsn", sentryDsn,
            "The DSN for Sentry, a service that provides real-time crash reporting that helps you monitor and fix crashes in real time. Leave blank to disable. Obtain link at https://sentry.io");
        logLevel = getString("settings.sentry.log-level", logLevel,
            "Logs with a level higher than or equal to this level will be recorded.");
        onlyLogThrown = getBoolean("settings.sentry.only-log-thrown", onlyLogThrown,
            "Only log Throwable exceptions to Sentry.");

        if (sentryDsn != null && !sentryDsn.isBlank()) gg.pufferfish.pufferfish.sentry.SentryManager.init(Level.getLevel(logLevel));
    }

    public static boolean disableDisconnectSpam = false;
    public static boolean gracefulTeleportHandling  = false;
    public static boolean dontRespondPingBeforeStart = true;
    public static boolean playerProfileResultCachingEnabled = true;
    public static int playerProfileResultCachingTimeout = 1440;
    private static void networkSettings() {
        disableDisconnectSpam = getBoolean("settings.network.disable-disconnect-spam", disableDisconnectSpam,
            "Prevents players being disconnected by 'disconnect.spam' when sending too many chat packets");
        gracefulTeleportHandling  = getBoolean("settings.network.graceful-teleport-handling", gracefulTeleportHandling ,
            "Disables being disconnected from 'multiplayer.disconnect.invalid_player_movement' (also declines the packet handling).");
        dontRespondPingBeforeStart = getBoolean("settings.network.dont-respond-ping-before-start", dontRespondPingBeforeStart,
            "Prevents the server from responding to pings before the server is fully booted.");

        playerProfileResultCachingEnabled = getBoolean("settings.network.player-profile-result-caching.enabled", playerProfileResultCachingEnabled,
            "Enables caching of player profile results on first join.");
        playerProfileResultCachingTimeout = getInt("settings.network.player-profile-result-caching.timeout", playerProfileResultCachingTimeout,
            "The amount of time in minutes to cache player profile results.");
    }

    public static boolean enableFasterTntOptimization = true;
    public static boolean explosionNoBlockDamage = false;
    public static double tntRandomRange = -1;
    private static void tntOptimization() {
        enableFasterTntOptimization = getBoolean("settings.tnt-optimization.enable-faster-tnt-optimization", enableFasterTntOptimization);
        explosionNoBlockDamage = getBoolean("settings.tnt-optimization.explosion-no-block-damage", explosionNoBlockDamage);
        tntRandomRange = getDouble("settings.tnt-optimization.tnt-random-range", tntRandomRange);
    }

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
    private static void lagCompensation() {
        lagCompensationEnabled = getBoolean("settings.lag-compensation.enabled", lagCompensationEnabled, "Improves the player experience when TPS is low");
        blockEntityAcceleration = getBoolean("settings.lag-compensation.block-entity-acceleration", blockEntityAcceleration);
        blockBreakingAcceleration = getBoolean("settings.lag-compensation.block-breaking-acceleration", blockBreakingAcceleration);
        eatingAcceleration = getBoolean("settings.lag-compensation.eating-acceleration", eatingAcceleration);
        potionEffectAcceleration = getBoolean("settings.lag-compensation.potion-effect-acceleration", potionEffectAcceleration);
        fluidAcceleration = getBoolean("settings.lag-compensation.fluid-acceleration", fluidAcceleration);
        pickupAcceleration = getBoolean("settings.lag-compensation.pickup-acceleration", pickupAcceleration);
        portalAcceleration = getBoolean("settings.lag-compensation.portal-acceleration", portalAcceleration);
        timeAcceleration = getBoolean("settings.lag-compensation.time-acceleration", timeAcceleration);
        randomTickSpeedAcceleration = getBoolean("settings.lag-compensation.random-tick-speed-acceleration", randomTickSpeedAcceleration);
    }

    public static boolean noChatReportsEnabled = false;
    public static boolean noChatReportsAddQueryData = true;
    public static boolean noChatReportsConvertToGameMessage = true;
    public static boolean noChatReportsDebugLog = false;
    public static boolean noChatReportsDemandOnClient = false;
    public static String noChatReportsDisconnectDemandOnClientMessage = "You do not have No Chat Reports, and this server is configured to require it on client!";
    private static void noChatReports() {
        noChatReportsEnabled = getBoolean("settings.no-chat-reports.enabled", noChatReportsEnabled,
            "Enables or disables the No Chat Reports feature");
        noChatReportsAddQueryData = getBoolean("settings.no-chat-reports.add-query-data", noChatReportsAddQueryData,
            "Should server include extra query data to help clients know that your server is secure");
        noChatReportsConvertToGameMessage = getBoolean("settings.no-chat-reports.convert-to-game-message", noChatReportsConvertToGameMessage,
            "Should the server convert all player messages to system messages");
        noChatReportsDebugLog = getBoolean("settings.no-chat-reports.debug-log", noChatReportsDebugLog);
        noChatReportsDemandOnClient = getBoolean("settings.no-chat-reports.demand-on-client", noChatReportsDemandOnClient,
            "Should the server require No Chat Reports on the client side");
        noChatReportsDisconnectDemandOnClientMessage = getString("settings.no-chat-reports.disconnect-demand-on-client-message", noChatReportsDisconnectDemandOnClientMessage,
            "Message to send to the client when they are disconnected for not having No Chat Reports");
    }

    public static boolean virtualThreadsEnabled = false;
    public static boolean virtualBukkitScheduler = false;
    public static boolean virtualChatScheduler = false;
    public static boolean virtualAuthenticatorScheduler = false;
    public static boolean virtualTabCompleteScheduler = false;
    public static boolean virtualAsyncExecutor = false;
    public static boolean virtualCommandBuilderScheduler = false;
    public static boolean virtualProfileLookupPool = false;
    public static boolean virtualServerTextFilterPool = false;
    private static void virtualThreads() {
        virtualThreadsEnabled = getBoolean("settings.virtual-threads.enabled", virtualThreadsEnabled,
            "Enables use of virtual threads that was added in Java 21");

        virtualBukkitScheduler = getBoolean("settings.virtual-threads.bukkit-scheduler", virtualBukkitScheduler,
            "Uses virtual threads for the Bukkit scheduler.");
        virtualChatScheduler = getBoolean("settings.virtual-threads.chat-scheduler", virtualChatScheduler,
            "Uses virtual threads for the Chat scheduler.");
        virtualAuthenticatorScheduler = getBoolean("settings.virtual-threads.authenticator-scheduler", virtualAuthenticatorScheduler,
            "Uses virtual threads for the Authenticator scheduler.");
        virtualTabCompleteScheduler = getBoolean("settings.virtual-threads.tab-complete-scheduler", virtualTabCompleteScheduler,
            "Uses virtual threads for the Tab Complete scheduler.");
        virtualAsyncExecutor = getBoolean("settings.virtual-threads.async-executor", virtualAsyncExecutor,
            "Uses virtual threads for the MCUtil async executor.");
        virtualCommandBuilderScheduler = getBoolean("settings.virtual-threads.command-builder-scheduler", virtualCommandBuilderScheduler,
            "Uses virtual threads for the Async Command Builder Thread Pool.");
        virtualProfileLookupPool = getBoolean("settings.virtual-threads.profile-lookup-pool", virtualProfileLookupPool,
            "Uses virtual threads for the Profile Lookup Pool, that is used for fetching player profiles.");
        virtualServerTextFilterPool = getBoolean("settings.virtual-threads.server-text-filter-pool", virtualServerTextFilterPool,
            "Uses virtual threads for the server text filter pool.");
    }

    public static boolean dabEnabled = true;
    public static int dabStartDistance = 12;
    public static int dabStartDistanceSquared;
    public static int dabMaximumActivationFrequency = 20;
    public static int dabActivationDistanceMod = 8;
    public static boolean dabDontEnableIfInWater = false;
    public static List<String> dabBlackedEntities = new ArrayList<>();
    private static void dab() {
        dabEnabled = getBoolean("settings.dab.enabled", dabEnabled,
            "Enables DAB feature");
        dabStartDistance = getInt("settings.dab.start-distance", dabStartDistance,
            "This value determines how far away an entity has to be");
        dabStartDistanceSquared = dabStartDistance * dabStartDistance;
        dabMaximumActivationFrequency = getInt("settings.dab.maximum-activation-frequency", dabMaximumActivationFrequency,
            "How often in ticks, the furthest entity will get their pathfinders and behaviors ticked.");
        dabActivationDistanceMod = getInt("settings.dab.activation-distance-mod", dabActivationDistanceMod,
            "Modifies an entity's tick frequency.",
            "The exact calculation to obtain the tick frequency for an entity is: freq = (distanceToPlayer^2) / (2^value), where value is this configuration setting.",
            "Large servers may want to reduce the value to 7, but this value should never be reduced below 6. If you want further away entities to tick more often, set the value to 9");
        dabDontEnableIfInWater = getBoolean("settings.dab.dont-enable-if-in-water", dabDontEnableIfInWater,
            "When this is enabled, non-aquatic entities in the water will not be affected by DAB.");
        dabBlackedEntities = getStringList("settings.dab.blacked-entities", dabBlackedEntities,
            "Use this configuration option to specify that certain entities should not be impacted by DAB.");

        setComment("settings.dab",
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

            EntityType.byString(typeId).ifPresentOrElse(entityType -> entityType.dabEnabled = false, () -> LOGGER.warn("Unknown entity {}, in {}", name, "settings.dab.blacked-entities"));
        }
    }

    public static boolean asyncPathfinding = true;
    public static int asyncPathfindingMaxThreads = 2;
    public static int asyncPathfindingKeepalive = 60;
    public static int asyncPathfindingQueueSize = 0;
    public static PathfindTaskRejectPolicy asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.FLUSH_ALL;
    private static void asyncPathfinding() {
        asyncPathfinding = getBoolean("settings.async-pathfinding.enable", asyncPathfinding);
        asyncPathfindingMaxThreads = getInt("settings.async-pathfinding.max-threads", asyncPathfindingMaxThreads);
        asyncPathfindingKeepalive = getInt("settings.async-pathfinding.keepalive", asyncPathfindingKeepalive);
        asyncPathfindingQueueSize = getInt("settings.async-pathfinding.queue-size", asyncPathfindingQueueSize);

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

        asyncPathfindingRejectPolicy = PathfindTaskRejectPolicy.fromString(getString("settings.async-pathfinding.reject-policy", maxThreads >= 12 && asyncPathfindingQueueSize < 512 ? PathfindTaskRejectPolicy.FLUSH_ALL.toString() : PathfindTaskRejectPolicy.CALLER_RUNS.toString(),
            "The policy to use when the queue is full and a new task is submitted.",
            "FLUSH_ALL: All pending tasks will be run on server thread.",
            "CALLER_RUNS: Newly submitted task will be run on server thread."));
    }

    public static boolean multithreadedEnabled = true;
    public static boolean multithreadedCompatModeEnabled = false;
    public static int asyncEntityTrackerMaxThreads = 1;
    public static int asyncEntityTrackerKeepalive = 60;
    public static int asyncEntityTrackerQueueSize = 0;
    private static void multithreadedTracker() {
        multithreadedEnabled = getBoolean("settings.multithreaded-tracker.enable", multithreadedEnabled,
            "Make entity tracking saving asynchronously, can improve performance significantly,",
            "especially in some massive entities in small area situations.");
        multithreadedCompatModeEnabled = getBoolean("settings.multithreaded-tracker.compat-mode", multithreadedCompatModeEnabled,
            "Enable compat mode ONLY if Citizens or NPC plugins using real entity has installed.",
            "Compat mode fixes visible issues with player type NPCs of Citizens.",
            "But we recommend to use packet based / virtual entity NPC plugin, e.g. ZNPC Plus, Adyeshach, Fancy NPC and etc.");

        asyncEntityTrackerMaxThreads = getInt("settings.multithreaded-tracker.max-threads", asyncEntityTrackerMaxThreads);
        asyncEntityTrackerKeepalive = getInt("settings.multithreaded-tracker.keepalive", asyncEntityTrackerKeepalive);
        asyncEntityTrackerQueueSize = getInt("settings.multithreaded-tracker.queue-size", asyncEntityTrackerQueueSize);

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

    public static RegionFileFormat regionFormatTypeName = RegionFileFormat.ANVIL;
    public static LinearImplementation linearImplementation = LinearImplementation.V2;
    public static int linearFlushMaxThreads = 4;
    public static int linearFlushDelay = 100;
    public static boolean linearUseVirtualThread = false;
    public static int linearCompressionLevel = 1;
    private static void linearRegionFormat() {
        regionFormatTypeName = RegionFileFormat.fromName(getString("settings.linear-region-format.type", regionFormatTypeName.name(),
            "The type of region file format to use for storing chunk data.",
            "Valid values:",
            " - LINEAR: Linear region file format",
            " - ANVIL: Anvil region file format (default)"));
        linearImplementation = LinearImplementation.valueOf(getString("settings.linear-region-format.implementation", linearImplementation.name(),
            "The implementation of the linear region file format to use.",
            "Valid values:",
            " - V1: Basic and default linear implementation",
            " - V2: Introduces a grid-based compression scheme for better data management and flexibility (default)"));

        linearFlushMaxThreads = getInt("settings.linear-region-format.flush-max-threads", linearFlushMaxThreads,
            "The maximum number of threads to use for flushing linear region files.",
            "If this value is less than or equal to 0, it will be set to the number of available processors + this value.");
        linearFlushDelay = getInt("settings.linear-region-format.flush-delay", linearFlushDelay,
            "The delay in milliseconds to wait before flushing next files.");
        linearUseVirtualThread = getBoolean("settings.linear-region-format.use-virtual-thread", linearUseVirtualThread,
            "Whether to use virtual threads for flushing.");
        linearCompressionLevel = getInt("settings.linear-region-format.compression-level", linearCompressionLevel,
            "The compression level to use for the linear region file format.");

        setComment("settings.linear-region-format",
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
            LOGGER.warn("Invalid linear compression level: {}, resetting to default (1)", playerNearChunkDetectionRange);
            linearCompressionLevel = 1;
        }
    }
}
