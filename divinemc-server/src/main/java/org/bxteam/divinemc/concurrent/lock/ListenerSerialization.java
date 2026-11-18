package org.bxteam.divinemc.concurrent.lock;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.bukkit.plugin.Plugin;
import org.bxteam.divinemc.concurrent.FoliaSupport;
import org.bxteam.divinemc.concurrent.LevelThreads;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;

/**
 * Runs the event listeners of plugins that do not declare {@code folia-supported: true} one at a time while levels
 * tick on several threads ({@code async.serialize-plugin-listeners}); asynchronous events on other threads are left
 * alone. It takes the same lock as
 * {@link ServerStateLock}: a command run under that lock fires events, and a listener may run a command.
 */
@NullMarked
public final class ListenerSerialization {
    private ListenerSerialization() {
    }

    public static boolean enter(final Plugin plugin) {
        if (!DivineConfig.AsyncCategory.serializePluginListeners || !LevelThreads.SHARED || !(Thread.currentThread() instanceof TickThread)
            || FoliaSupport.declares(plugin)) {
            return false;
        }
        ServerStateLock.lockAlways();
        return true;
    }

    public static void exit(final boolean entered) {
        if (entered) {
            ServerStateLock.unlockAlways();
        }
    }
}
