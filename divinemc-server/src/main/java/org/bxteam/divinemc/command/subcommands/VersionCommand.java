package org.bxteam.divinemc.command.subcommands;

import net.minecraft.server.MinecraftServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.jetbrains.annotations.Nullable;
import org.bxteam.divinemc.command.DivineCommand;
import org.bxteam.divinemc.command.DivineSubCommandPermission;

@DefaultQualifier(NonNull.class)
public final class VersionCommand extends DivineSubCommandPermission {
    public final static String LITERAL_ARGUMENT = "version";
    public static final String PERM = DivineCommand.BASE_PERM + "." + LITERAL_ARGUMENT;

    public VersionCommand() {
        super(PERM, PermissionDefault.OP);
    }

    @Override
    public boolean execute(final CommandSender sender, final String subCommand, final String[] args) {
        final @Nullable Command ver = MinecraftServer.getServer().server.getCommandMap().getCommand("version");
        if (ver != null) {
            ver.execute(sender, DivineCommand.COMMAND_LABEL, new String[0]);
        }
        return true;
    }

    @Override
    public boolean testPermission(CommandSender sender) {
        return super.testPermission(sender) && sender.hasPermission("bukkit.command.version");
    }
}
