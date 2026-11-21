package org.bxteam.divinemc.command.subcommands;

import net.kyori.adventure.text.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.permissions.PermissionDefault;
import org.bxteam.divinemc.command.DivineCommand;
import org.bxteam.divinemc.command.DivineSubCommandPermission;
import org.bxteam.divinemc.region.LinearFlusher;
import org.bxteam.divinemc.region.RegionFormat;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

@DefaultQualifier(NonNull.class)
public final class CompactCommand extends DivineSubCommandPermission {
    public static final String LITERAL_ARGUMENT = "compact";
    public static final String PERM = DivineCommand.BASE_PERM + "." + LITERAL_ARGUMENT;

    public CompactCommand() {
        super(PERM, PermissionDefault.OP);
    }

    @Override
    public boolean execute(final CommandSender sender, final String subCommand, final String[] args) {
        if (RegionFormat.configured() != RegionFormat.LINEAR) {
            sender.sendMessage(Component.text("Region files are " + RegionFormat.configured() + "; only Linear files are compacted.", RED));
            return true;
        }
        final String mode = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        final List<Path> folders = regionFolders(((CraftServer) sender.getServer()).getServer());
        switch (mode) {
            case "now" -> {
                final int queued = LinearFlusher.get().compactNow(folders);
                sender.sendMessage(Component.text("Compacting " + queued + " Linear region files in the background; chunk reads go on meanwhile.", GREEN));
            }
            case "shutdown" -> {
                LinearFlusher.get().compactOnShutdown(folders);
                sender.sendMessage(Component.text("Every Linear region file with garbage is compacted when the server stops.", GREEN));
            }
            default -> sender.sendMessage(Component.text("Usage: /" + DivineCommand.COMMAND_LABEL + " " + LITERAL_ARGUMENT + " <now|shutdown>", GRAY));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String subCommand, final String[] args) {
        return args.length <= 1 ? List.of("now", "shutdown") : List.of();
    }

    private static List<Path> regionFolders(MinecraftServer server) {
        final List<Path> folders = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            final Path dimension = server.storageSource.getDimensionPath(level.dimension());
            folders.add(dimension.resolve("region"));
            folders.add(dimension.resolve("entities"));
            folders.add(dimension.resolve("poi"));
        }
        return folders;
    }
}
