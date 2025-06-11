package org.bxteam.divinemc.command.subcommands;

import net.kyori.adventure.text.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.permissions.PermissionDefault;
import org.bxteam.divinemc.command.DivineCommand;
import org.bxteam.divinemc.command.DivineSubCommandPermission;
import org.bxteam.divinemc.config.DivineConfig;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;

import java.io.File;
import java.io.IOException;

import static net.kyori.adventure.text.format.NamedTextColor.*;

@DefaultQualifier(NonNull.class)
public final class ReloadCommand extends DivineSubCommandPermission {
    public final static String LITERAL_ARGUMENT = "reload";
    public static final String PERM = DivineCommand.BASE_PERM + "." + LITERAL_ARGUMENT;

    public ReloadCommand() {
        super(PERM, PermissionDefault.OP);
    }

    @Override
    public boolean execute(final CommandSender sender, final String subCommand, final String[] args) {
        this.doReload(sender);
        return true;
    }

    private void doReload(final CommandSender sender) {
        Command.broadcastCommandMessage(sender, Component.text("Please note that this command is not supported and may cause issues.", RED));
        Command.broadcastCommandMessage(sender, Component.text("If you encounter any issues please use the /stop command to restart your server.", RED));

        MinecraftServer server = ((CraftServer) sender.getServer()).getServer();

        try {
            DivineConfig.init((File) server.options.valueOf("divinemc-settings"));
        } catch (IOException e) {
            MinecraftServer.LOGGER.error("Failed to reload DivineMC config", e);
        }

        for (ServerLevel level : server.getAllLevels()) {
            try {
                level.divineConfig.init();
            } catch (IOException e) {
                MinecraftServer.LOGGER.error("Failed to reload DivineMC world config for level {}", level.dimension().location(), e);
            }
            level.resetBreedingCooldowns();
        }
        server.server.reloadCount++;

        Command.broadcastCommandMessage(sender, Component.text("DivineMC config reload complete.", GREEN));
    }
}
