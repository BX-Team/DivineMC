package org.bxteam.divinemc.concurrent;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a plugin declares {@code folia-supported: true}, i.e. expects its listeners and region or entity tasks to
 * run on several threads at once. Paper reads the flag but does not expose it, so it is read from the descriptor.
 */
@NullMarked
public final class FoliaSupport {
    private static final Map<Plugin, Boolean> DECLARED = new ConcurrentHashMap<>();

    private FoliaSupport() {
    }

    public static boolean declares(final Plugin plugin) {
        return DECLARED.computeIfAbsent(plugin, FoliaSupport::readDescriptor);
    }

    private static boolean readDescriptor(final Plugin plugin) {
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
