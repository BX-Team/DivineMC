package org.bxteam.divinemc.command.subcommands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.command.DivineCommand;
import org.bxteam.divinemc.command.DivineSubCommandPermission;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.stream.LongStream;

import static net.kyori.adventure.text.format.NamedTextColor.*;

@DefaultQualifier(NonNull.class)
public final class MSPTCommand extends DivineSubCommandPermission {
    public static final String LITERAL_ARGUMENT = "mspt";
    public static final String PERM = DivineCommand.BASE_PERM + "." + LITERAL_ARGUMENT;
    private static final DecimalFormat DF = new DecimalFormat("########0.0");
    private static final Component SLASH = Component.text("/");

    public MSPTCommand() {
        super(PERM, PermissionDefault.TRUE);
    }

    @Override
    public boolean execute(CommandSender sender, String subCommand, String[] args) {
        if (!DivineConfig.AsyncCategory.enableParallelWorldTicking) {
            sender.sendMessage(Component.text("Per-world MSPT tracking is only available when parallel world ticking is enabled.", RED));
            sender.sendMessage(Component.text("Please enable it in divinemc.yml to use this command.", GRAY));
            return true;
        }

        boolean compactMode = args.length > 0 && args[0].equalsIgnoreCase("compact");
        MinecraftServer server = MinecraftServer.getServer();

        if (compactMode) {
            displayCompactStats(sender, server);
        } else {
            sender.sendMessage(Component.text("━━━━━━━━━━━━━ ", GOLD)
                .append(Component.text("MSPT Statistics", YELLOW))
                .append(Component.text(" ━━━━━━━━━━━━━", GOLD)));
            displayServerMSPT(sender, server);
            sender.sendMessage(Component.empty());
            displayWorldMSPT(sender, server);
        }

        return true;
    }

    private void displayCompactStats(CommandSender sender, MinecraftServer server) {
        List<Component> serverTimes = eval(server.tickTimes5s.getTimes());
        sender.sendMessage(Component.text("Server: ", GOLD)
            .append(joinComponents(serverTimes, SLASH)));

        List<ServerLevel> worlds = new ArrayList<>();
        server.getAllLevels().forEach(worlds::add);

        for (int i = 0; i < worlds.size(); i++) {
            ServerLevel level = worlds.get(i);
            List<Component> worldTimes = eval(level.tickTimes5s.getTimes());
            sender.sendMessage(Component.text(level.getWorld().getName() + ": ", GOLD)
                .append(joinComponents(worldTimes, SLASH)));
            if (i < worlds.size() - 1) {
                sender.sendMessage(Component.empty());
            }
        }
    }

    private void displayServerMSPT(CommandSender sender, MinecraftServer server) {
        sender.sendMessage(Component.text("Server tick times ", GOLD)
            .append(Component.text("(avg/min/max)", YELLOW)));

        sendTickLine(sender, "  5s: ", eval(server.tickTimes5s.getTimes()), GOLD, SLASH);
        sendTickLine(sender, " 10s: ", eval(server.tickTimes10s.getTimes()), GOLD, SLASH);
        sendTickLine(sender, " 60s: ", eval(server.tickTimes60s.getTimes()), GOLD, SLASH);
    }

    private void displayWorldMSPT(CommandSender sender, MinecraftServer server) {
        sender.sendMessage(Component.text("World-specific tick times ", GOLD)
            .append(Component.text("(avg/min/max)", YELLOW)));

        List<ServerLevel> worlds = new ArrayList<>();
        server.getAllLevels().forEach(worlds::add);

        for (int i = 0; i < worlds.size(); i++) {
            ServerLevel level = worlds.get(i);
            List<Component> worldTimes = new ArrayList<>();
            worldTimes.addAll(eval(level.tickTimes5s.getTimes()));
            worldTimes.addAll(eval(level.tickTimes10s.getTimes()));
            worldTimes.addAll(eval(level.tickTimes60s.getTimes()));

            sender.sendMessage(Component.text("➤ ", YELLOW)
                .append(Component.text(level.getWorld().getName(), GOLD)));

            sendTickLine(sender, "  5s: ", worldTimes.subList(0, 3), GRAY, SLASH);
            sendTickLine(sender, " 10s: ", worldTimes.subList(3, 6), GRAY, SLASH);
            sendTickLine(sender, " 60s: ", worldTimes.subList(6, 9), GRAY, SLASH);

            if (i < worlds.size() - 1) {
                sender.sendMessage(Component.empty());
            }
        }
    }

    private static List<Component> eval(long[] times) {
        LongSummaryStatistics stats = LongStream.of(times)
            .filter(value -> value > 0L)
            .summaryStatistics();

        if (stats.getCount() == 0) {
            return Arrays.asList(
                Component.text("N/A", GRAY),
                Component.text("N/A", GRAY),
                Component.text("N/A", GRAY)
            );
        }

        double avg = stats.getAverage() * 1.0E-6;
        double min = stats.getMin() * 1.0E-6;
        double max = stats.getMax() * 1.0E-6;

        return Arrays.asList(getColoredValue(avg), getColoredValue(min), getColoredValue(max));
    }

    private static Component getColoredValue(double value) {
        NamedTextColor color = value >= 50 ? RED
            : value >= 40 ? YELLOW
            : value >= 30 ? NamedTextColor.GOLD
            : value >= 20 ? GREEN
            : AQUA;
        return Component.text(DF.format(value) + "ms", color);
    }

    private void sendTickLine(CommandSender sender, String label, List<Component> tickComponents, NamedTextColor labelColor, Component separator) {
        sender.sendMessage(Component.text(label, labelColor)
            .append(joinComponents(tickComponents, separator)));
    }

    private Component joinComponents(List<Component> components, Component separator) {
        Component result = Component.empty();
        for (int i = 0; i < components.size(); i++) {
            result = result.append(components.get(i));
            if (i < components.size() - 1) {
                result = result.append(separator);
            }
        }
        return result;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String subCommand, String[] args) {
        if (!DivineConfig.AsyncCategory.enableParallelWorldTicking) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Collections.singletonList("compact");
        }
        return Collections.emptyList();
    }
}
