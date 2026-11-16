package org.bxteam.divinemc.concurrent.lock;

import ca.spottedleaf.moonrise.common.util.TickThread;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bxteam.divinemc.concurrent.LevelThreads;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the event listeners of plugins that do not declare {@code folia-supported: true} one at a time while levels
 * tick on several threads ({@code async.serialize-plugin-listeners}); asynchronous events on other threads are left
 * alone. It takes the same lock as
 * {@link ServerStateLock}: a command run under that lock fires events, and a listener may run a command.
 */
@NullMarked
public final class ListenerSerialization {
    private static final Map<Plugin, Boolean> FOLIA_SUPPORTED = new ConcurrentHashMap<>();

    private ListenerSerialization() {
    }

    public static boolean enter(final Plugin plugin) {
        if (!DivineConfig.AsyncCategory.serializePluginListeners || !LevelThreads.SHARED || !(Thread.currentThread() instanceof TickThread)
            || FOLIA_SUPPORTED.computeIfAbsent(plugin, ListenerSerialization::declaresFoliaSupport)) {
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

    private static boolean declaresFoliaSupport(final Plugin plugin) {
        if (!(plugin instanceof JavaPlugin javaPlugin)) {
            return false;
        }
        for (final String descriptor : new String[] {"paper-plugin.yml", "plugin.yml"}) {
            try (InputStream in = javaPlugin.getResource(descriptor)) {
                if (in != null) {
                    final Object yaml = new Yaml().load(in);
                    return yaml instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("folia-supported"));
                }
            } catch (final Exception ignored) {
                return false;
            }
        }
        return false;
    }
}
