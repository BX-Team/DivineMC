package org.bxteam.divinemc.command.subcommands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.permissions.PermissionDefault;
import org.bxteam.divinemc.async.rct.RegionizedChunkTicking;
import org.bxteam.divinemc.async.rct.RollingLongBuffer;
import org.bxteam.divinemc.command.DivineCommand;
import org.bxteam.divinemc.command.DivineSubCommandPermission;
import org.bxteam.divinemc.config.DivineConfig;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.*;

import static net.kyori.adventure.text.Component.empty;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public final class TickTimes extends DivineSubCommandPermission {
    public static final String LITERAL_ARGUMENT = "ticktimes";
    public static final String PERM = DivineCommand.BASE_PERM + "." + LITERAL_ARGUMENT;
    private static final DecimalFormat DF = new DecimalFormat("########0.0");
    private static final Component SLASH = text("/");
    private static final ClickCallback.Options options = ClickCallback.Options.builder().uses(-1).lifetime(Duration.ofMinutes(1)).build();

    private record RegionStatsKey(int chunkSize, int entityAmount, int regionHash) {}

    public TickTimes() {
        super(PERM, PermissionDefault.OP);
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String subCommand, String @NotNull [] args) {
        if (!DivineConfig.AsyncCategory.enableRegionizedChunkTicking) {
            sender.sendMessage(Component.text("Per-world tick times tracking is only available when regionized chunk ticking is enabled.", RED));
            sender.sendMessage(Component.text("Please enable it in divinemc.yml to use this command.", GRAY));
            return true;
        }

        List<ServerPlayer> onlinePlayers = MinecraftServer.getServer().getPlayerList().players;

        if (onlinePlayers.isEmpty()) {
            sender.sendMessage(text("No players online.", NamedTextColor.GRAY));
            return true;
        }

        TreeMap<ServerLevel, List<ServerPlayer>> playersByWorld = new TreeMap<>(
            Comparator.<ServerLevel>comparingInt(level -> level.players().size())
                .reversed()
                .thenComparing(Comparator.<ServerLevel>comparingDouble(level -> ((RegionizedChunkTicking) level.chunkSource).avgTime.average().orElse(0)).reversed())
                .thenComparing(level -> level.serverLevelData.getLevelName())
        );

        for (ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (!players.isEmpty()) {
                playersByWorld.put(level, new ArrayList<>(players));
            }
        }

        sender.sendMessage(text("━━━━━━━━━━━━━ ", GOLD)
            .append(text("Player Tick Times ", YELLOW))
            .append(text("(last/1s/5s)", NamedTextColor.GRAY))
            .append(text(" ━━━━━━━━━━━━━", GOLD)));

        int i = 0;
        for (Map.Entry<ServerLevel, List<ServerPlayer>> entry : playersByWorld.entrySet()) {
            ServerLevel level = entry.getKey();
            String worldName = level.serverLevelData.getLevelName();
            List<ServerPlayer> players = entry.getValue();
            RollingLongBuffer average = ((RegionizedChunkTicking) level.chunkSource).avgTime;

            players.sort((p1, p2) -> Double.compare(
                p2.avgTickTimeNanos.average().orElse(0),
                p1.avgTickTimeNanos.average().orElse(0)
            ));

            Map<RegionStatsKey, List<ServerPlayer>> playersByRegionStats = new LinkedHashMap<>();
            for (ServerPlayer player : players) {
                RegionStatsKey key = new RegionStatsKey(player.lastRegionChunkSize, player.lastRegionEntityAmount, player.regionHash);
                playersByRegionStats.computeIfAbsent(key, ignored -> new ArrayList<>()).add(player);
            }

            sender.sendMessage(text("➤ ", YELLOW)
                .append(text(worldName, GOLD))
                .append(text(" (" + players.size() + " players)", GRAY))
                .append(stats(average))
                .hoverEvent(text("Region amount: " + playersByRegionStats.size(), GREEN)));

            for (List<ServerPlayer> group : playersByRegionStats.values()) {
                Component stats;
                ServerPlayer firstPlayer = group.getFirst();
                if (group.size() == 1) {
                    stats = stats(firstPlayer.avgTickTimeNanos);
                } else {
                    double last = 0.0;
                    double avg1s = 0.0;
                    double avg5s = 0.0;

                    for (ServerPlayer player : group) {
                        last += player.avgTickTimeNanos.last().orElse(0L);
                        avg1s += player.avgTickTimeNanos.averageLast(20).orElse(0L);
                        avg5s += player.avgTickTimeNanos.average().orElse(0L);
                    }

                    last = last / group.size() * 1.0E-6;
                    avg1s = avg1s / group.size() * 1.0E-6;
                    avg5s = avg5s / group.size() * 1.0E-6;
                    stats = stats(last, avg1s, avg5s);
                }

                Component hover = text("Last region size: ", GRAY)
                    .append(text(firstPlayer.lastRegionChunkSize, GREEN))
                    .appendNewline()
                    .append(text("Last region entity amount: ", GRAY))
                    .append(text(firstPlayer.lastRegionEntityAmount, GREEN))
                    .appendNewline()
                    .append(text("Grouped players: ", GRAY))
                    .append(text(group.size(), GREEN));

                Component playerLine = text("  - ", GRAY)
                    .append(group.stream()
                        .map(player -> text(player.displayName, AQUA)
                            .hoverEvent(text("Click to teleport", GREEN))
                            .clickEvent(ClickEvent.callback(audience -> {
                                if (audience instanceof CraftPlayer sourcePlayer) {
                                    MinecraftServer.getServer().execute(() -> sourcePlayer.teleport(player.getBukkitEntity().getLocation()));
                                }
                            }, options))
                        )
                        .reduce((left, right) -> left.append(text(", ", GRAY)).append(right))
                        .orElseGet(Component::empty)
                    )
                    .append(stats.hoverEvent(hover));

                sender.sendMessage(playerLine);
            }

            if (i < playersByWorld.size() - 1) {
                sender.sendMessage(empty());
            }
            i++;
        }

        return true;
    }

    private Component stats(RollingLongBuffer buffer) {
        return stats(buffer.last().orElse(0L) * 1.0E-6, buffer.averageLast(20).orElse(0L) * 1.0E-6, buffer.average().orElse(0L) * 1.0E-6);
    }

    private Component stats(double last, double avg1s, double avg5s) {
        return text(" [", GRAY)
            .append(getColoredValue(last))
            .append(SLASH)
            .append(getColoredValue(avg1s))
            .append(SLASH)
            .append(getColoredValue(avg5s))
            .append(text("]", GRAY));
    }

    private static Component getColoredValue(double value) {
        NamedTextColor color = value >= 35 ? RED
            : value >= 30 ? YELLOW
              : value >= 25 ? GOLD
                : value >= 15 ? GREEN
                  : AQUA;
        return text(DF.format(value) + "ms", color);
    }
}
