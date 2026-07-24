package org.bxteam.divinemc.spark;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import me.lucko.spark.paper.common.platform.serverconfig.ConfigParser;
import me.lucko.spark.paper.common.platform.serverconfig.ExcludedConfigFilter;
import me.lucko.spark.paper.common.platform.serverconfig.PropertiesConfigParser;
import me.lucko.spark.paper.common.platform.serverconfig.ServerConfigProvider;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;

public class DivineServerConfigProvider extends ServerConfigProvider {
    private static final Map<String, ConfigParser> FILES;
    private static final Collection<String> HIDDEN_PATHS;

    public DivineServerConfigProvider() {
        super(FILES, HIDDEN_PATHS);
    }

    private static class YamlConfigParser implements ConfigParser {
        public static final YamlConfigParser INSTANCE = new YamlConfigParser();
        protected static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(MemorySection.class, (JsonSerializer<MemorySection>) (obj, type, ctx) -> ctx.serialize(obj.getValues(false)))
            .create();

        @Override
        public @Nullable JsonElement load(String file, ExcludedConfigFilter filter) throws IOException {
            Map<String, Object> values = this.parse(Paths.get(file));
            if (values == null) {
                return null;
            }

            return filter.apply(GSON.toJsonTree(values));
        }

        @Override
        public Map<String, Object> parse(BufferedReader reader) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(reader);
            return config.getValues(false);
        }
    }

    private static final class PaperSplitConfigParser extends YamlConfigParser {
        public static final PaperSplitConfigParser INSTANCE = new PaperSplitConfigParser();

        @Override
        public @Nullable JsonElement load(String group, ExcludedConfigFilter filter) throws IOException {
            Path paperConfigDir = Paths.get(getPath("paper-dir"));
            if (!Files.exists(paperConfigDir)) {
                return null;
            }

            JsonObject root = new JsonObject();
            this.addSection(root, filter, "global.yml", paperConfigDir.resolve("paper-global.yml"));
            this.addSection(root, filter, "world-defaults.yml", paperConfigDir.resolve("paper-world-defaults.yml"));

            for (World world : Bukkit.getWorlds()) {
                this.addSection(root, filter, world.getName() + ".yml", world.getWorldFolder().toPath().resolve("paper-world.yml"));
            }

            return root;
        }

        private void addSection(JsonObject root, ExcludedConfigFilter filter, String name, Path path) throws IOException {
            Map<String, Object> values = this.parse(path);
            if (values == null) {
                return;
            }

            root.add(name, filter.apply(GSON.toJsonTree(values)));
        }
    }

    private static String getPath(String optionsName) {
        return ((java.io.File) net.minecraft.server.MinecraftServer.getServer().options.valueOf(optionsName)).getPath();
    }

    static {
        ImmutableMap.Builder<String, ConfigParser> files = ImmutableMap.<String, ConfigParser>builder()
            .put(getPath("config"), PropertiesConfigParser.INSTANCE)
            .put(getPath("bukkit-settings"), YamlConfigParser.INSTANCE)
            .put(getPath("spigot-settings"), YamlConfigParser.INSTANCE)
            .put("paper/", PaperSplitConfigParser.INSTANCE)
            .put(getPath("purpur-settings"), YamlConfigParser.INSTANCE)
            .put(getPath("divinemc-settings"), YamlConfigParser.INSTANCE);

        for (String config : getSystemPropertyList("spark.serverconfigs.extra")) {
            files.put(config, YamlConfigParser.INSTANCE);
        }

        ImmutableSet.Builder<String> hiddenPaths = ImmutableSet.<String>builder()
            .add("database")
            .add("settings.bungeecord-addresses")
            .add("settings.velocity-support.secret")
            .add("proxies.velocity.secret")
            .add("server-ip")
            .add("motd")
            .add("resource-pack")
            .add("rcon<dot>password")
            .add("rcon<dot>ip")
            .add("level-seed")
            .add("world-settings.*.feature-seeds")
            .add("world-settings.*.seed-*")
            .add("feature-seeds")
            .add("seed-*")
            .add("sentry.dsn")
            .add("management-server-secret")
            .addAll(getSystemPropertyList("spark.serverconfigs.hiddenpaths"));

        FILES = files.build();
        HIDDEN_PATHS = hiddenPaths.build();
    }
}
