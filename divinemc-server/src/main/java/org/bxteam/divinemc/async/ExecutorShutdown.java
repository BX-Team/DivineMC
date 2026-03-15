package org.bxteam.divinemc.async;

import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bxteam.divinemc.async.pathfinding.AsyncPath;
import org.bxteam.divinemc.async.tracking.MultithreadedTracker;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.region.EnumRegionFileExtension;
import org.bxteam.divinemc.region.type.BufferedRegionFile;

import java.util.concurrent.TimeUnit;

@SuppressWarnings("ConstantValue")
public class ExecutorShutdown {
    public static final Logger LOGGER = LogManager.getLogger(ExecutorShutdown.class.getSimpleName());

    public static void shutdown(MinecraftServer server) {
        if (BufferedRegionFile.flusherInitialized && DivineConfig.MiscCategory.regionFileType == EnumRegionFileExtension.B_LINEAR) {
            LOGGER.info("Shutting down buffered region executors...");

            try {
                BufferedRegionFile.shutdown();
            } catch (InterruptedException ignored) { }
        }

        if (server.mobSpawnExecutor != null && server.mobSpawnExecutor.thread.isAlive()) {
            LOGGER.info("Shutting down mob spawn executor...");

            try {
                server.mobSpawnExecutor.join(3000L);
            } catch (InterruptedException ignored) { }
        }

        if (AsyncChunkSend.POOL != null) {
            LOGGER.info("Shutting down async chunk send executor...");
            AsyncChunkSend.POOL.shutdown();

            try {
                AsyncChunkSend.POOL.awaitTermination(10L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }
        }

        if (MultithreadedTracker.TRACKER_EXECUTOR != null) {
            LOGGER.info("Shutting down mob tracker executor...");
            MultithreadedTracker.TRACKER_EXECUTOR.shutdown();

            try {
                MultithreadedTracker.TRACKER_EXECUTOR.awaitTermination(10L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }
        }

        if (AsyncPath.EXECUTOR != null) {
            LOGGER.info("Shutting down mob pathfinding processing executor...");
            AsyncPath.EXECUTOR.shutdown();

            try {
                AsyncPath.EXECUTOR.awaitTermination(10L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }
        }
    }
}
