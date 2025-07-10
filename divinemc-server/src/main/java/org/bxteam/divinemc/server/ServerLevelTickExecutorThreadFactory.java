package org.bxteam.divinemc.server;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.bxteam.divinemc.spark.ThreadDumperRegistry;
import java.util.concurrent.ThreadFactory;

public class ServerLevelTickExecutorThreadFactory implements ThreadFactory {
    private final String worldName;

    public ServerLevelTickExecutorThreadFactory(String worldName) {
        this.worldName = worldName;
        ThreadDumperRegistry.REGISTRY.add(worldName + "ServerLevel Tick Worker");
    }

    @Override
    public Thread newThread(Runnable runnable) {
        TickThread.ServerLevelTickThread tickThread = new TickThread.ServerLevelTickThread(runnable, this.worldName + "ServerLevel Tick Worker");

        if (tickThread.isDaemon()) {
            tickThread.setDaemon(false);
        }

        if (tickThread.getPriority() != 5) {
            tickThread.setPriority(5);
        }

        return tickThread;
    }
}
