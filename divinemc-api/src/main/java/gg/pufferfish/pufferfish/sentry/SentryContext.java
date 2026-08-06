package gg.pufferfish.pufferfish.sentry;

import com.google.gson.Gson;
import org.apache.logging.log4j.ThreadContext;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

public class SentryContext {
    private static final Gson GSON = new Gson();

    public static void setPluginContext(@Nullable Plugin plugin) {
        if (plugin != null) {
            ThreadContext.put("pufferfishsentry_pluginname", plugin.getName());
            ThreadContext.put("pufferfishsentry_pluginversion", plugin.getPluginMeta().getVersion());
        }
    }

    public static void removePluginContext() {
        ThreadContext.remove("pufferfishsentry_pluginname");
        ThreadContext.remove("pufferfishsentry_pluginversion");
    }

    public static void setSenderContext(@Nullable CommandSender sender) {
        if (sender != null) {
            ThreadContext.put("pufferfishsentry_playername", sender.getName());
            if (sender instanceof Player player) {
                ThreadContext.put("pufferfishsentry_playerid", player.getUniqueId().toString());
            }
        }
    }

    public static void removeSenderContext() {
        ThreadContext.remove("pufferfishsentry_playername");
        ThreadContext.remove("pufferfishsentry_playerid");
    }

    public static void setEventContext(Event event, RegisteredListener registration) {
        setPluginContext(registration.getPlugin());

        try {
            // Find the player that was involved with this event
            Player player = null;
            if (event instanceof PlayerEvent) {
                player = ((PlayerEvent) event).getPlayer();
            } else {
                Class<? extends Event> eventClass = event.getClass();

                Field playerField = null;

                for (Field field : eventClass.getDeclaredFields()) {
                    if (field.getType().equals(Player.class)) {
                        playerField = field;
                        break;
                    }
                }

                if (playerField != null) {
                    playerField.setAccessible(true);
                    player = (Player) playerField.get(event);
                }
            }

            if (player != null) {
                setSenderContext(player);
            }
        } catch (Exception ignored) {
        } // We can't really safely log exceptions.

        ThreadContext.put("pufferfishsentry_eventdata", GSON.toJson(serializeFields(event)));
    }

    public static void removeEventContext() {
        removePluginContext();
        removeSenderContext();
        ThreadContext.remove("pufferfishsentry_eventdata");
    }

    private static Map<String, String> serializeFields(Object object) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("_class", object.getClass().getName());
        for (Field declaredField : object.getClass().getDeclaredFields()) {
            try {
                if (Modifier.isStatic(declaredField.getModifiers())) {
                    continue;
                }

                String fieldName = declaredField.getName();
                if (fieldName.equals("handlers")) {
                    continue;
                }
                declaredField.setAccessible(true);
                Object value = declaredField.get(object);
                if (value != null) {
                    fields.put(fieldName, value.toString());
                } else {
                    fields.put(fieldName, "<null>");
                }
            } catch (Exception ignored) {
            } // We can't really safely log exceptions.
        }
        return fields;
    }

    /**
     * Holds the context of whatever is currently being executed. Every field is unset
     * until the corresponding setter is called, so all getters may return {@code null}.
     */
    public static class State {

        private @Nullable Plugin plugin;
        private @Nullable Command command;
        private @Nullable String commandLine;
        private @Nullable Event event;
        private @Nullable RegisteredListener registeredListener;

        public @Nullable Plugin getPlugin() {
            return plugin;
        }

        public void setPlugin(@Nullable Plugin plugin) {
            this.plugin = plugin;
        }

        public @Nullable Command getCommand() {
            return command;
        }

        public void setCommand(@Nullable Command command) {
            this.command = command;
        }

        public @Nullable String getCommandLine() {
            return commandLine;
        }

        public void setCommandLine(@Nullable String commandLine) {
            this.commandLine = commandLine;
        }

        public @Nullable Event getEvent() {
            return event;
        }

        public void setEvent(@Nullable Event event) {
            this.event = event;
        }

        public @Nullable RegisteredListener getRegisteredListener() {
            return registeredListener;
        }

        public void setRegisteredListener(@Nullable RegisteredListener registeredListener) {
            this.registeredListener = registeredListener;
        }
    }
}
