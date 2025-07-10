package org.bxteam.divinemc.command;

import io.papermc.paper.command.CommandUtil;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.Util;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bxteam.divinemc.command.subcommands.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

public final class DivineCommand extends Command {
    public static final String COMMAND_LABEL = "divinemc";
    public static final String BASE_PERM = DivineCommands.COMMAND_BASE_PERM + "." + COMMAND_LABEL;
    private static final Permission basePermission = new Permission(BASE_PERM, PermissionDefault.TRUE);

    private static final DivineSubCommand MSPT_SUBCOMMAND = new MSPTCommand();
    private static final DivineSubCommand RELOAD_SUBCOMMAND = new ReloadCommand();
    private static final DivineSubCommand VERSION_SUBCOMMAND = new VersionCommand();
    private static final Map<String, DivineSubCommand> SUBCOMMANDS = Util.make(() -> {
        final Map<Set<String>, DivineSubCommand> commands = new HashMap<>();

        commands.put(Set.of(MSPTCommand.LITERAL_ARGUMENT), MSPT_SUBCOMMAND);
        commands.put(Set.of(ReloadCommand.LITERAL_ARGUMENT), RELOAD_SUBCOMMAND);
        commands.put(Set.of(VersionCommand.LITERAL_ARGUMENT), VERSION_SUBCOMMAND);

        return commands.entrySet().stream()
                .flatMap(entry -> entry.getKey().stream().map(s -> Map.entry(s, entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    });
    // alias -> subcommand label
    private static final Map<String, String> ALIASES = Util.make(() -> {
        final Map<String, Set<String>> aliases = new HashMap<>();

        aliases.put(VersionCommand.LITERAL_ARGUMENT, Set.of("ver"));

        return aliases.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(s -> Map.entry(s, entry.getKey())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    });

    private String createUsageMessage(Collection<String> arguments) {
        return "/" + COMMAND_LABEL + " [" + String.join(" | ", arguments) + "]";
    }

    public DivineCommand() {
        super(COMMAND_LABEL);
        this.description = "DivineMC related commands";
        this.usageMessage = this.createUsageMessage(SUBCOMMANDS.keySet());
        final List<Permission> permissions = SUBCOMMANDS.values().stream().map(DivineSubCommand::getPermission).filter(Objects::nonNull).toList();
        this.setPermission(BASE_PERM);
        final PluginManager pluginManager = Bukkit.getServer().getPluginManager();
        pluginManager.addPermission(basePermission);
        for (final Permission permission : permissions) {
            pluginManager.addPermission(permission);
        }
    }

    @Override
    public List<String> tabComplete(
            final CommandSender sender,
            final String alias,
            final String[] args,
            final @Nullable Location location
    ) throws IllegalArgumentException {
        if (args.length <= 1) {
            List<String> subCommandArguments = new ArrayList<>(SUBCOMMANDS.size());
            for (Map.Entry<String, DivineSubCommand> subCommandEntry : SUBCOMMANDS.entrySet()) {
                if (subCommandEntry.getValue().testPermission(sender)) {
                    subCommandArguments.add(subCommandEntry.getKey());
                }
            }
            return CommandUtil.getListMatchingLast(sender, args, subCommandArguments);
        }

        final @Nullable Pair<String, DivineSubCommand> subCommand = resolveCommand(args[0]);
        if (subCommand != null && subCommand.second().testPermission(sender)) {
            return subCommand.second().tabComplete(sender, subCommand.first(), Arrays.copyOfRange(args, 1, args.length));
        }

        return Collections.emptyList();
    }

    private boolean testHasOnePermission(CommandSender sender) {
        for (Map.Entry<String, DivineSubCommand> subCommandEntry : SUBCOMMANDS.entrySet()) {
            if (subCommandEntry.getValue().testPermission(sender)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean execute(
            final CommandSender sender,
            final String commandLabel,
            final String[] args
    ) {

        // Check if the sender has the base permission and at least one specific permission
        if (!sender.hasPermission(basePermission) || !this.testHasOnePermission(sender)) {
            sender.sendMessage(Bukkit.permissionMessage());
            return true;
        }

        // Determine the usage message with the subcommands they can perform
        List<String> subCommandArguments = new ArrayList<>(SUBCOMMANDS.size());
        for (Map.Entry<String, DivineSubCommand> subCommandEntry : SUBCOMMANDS.entrySet()) {
            if (subCommandEntry.getValue().testPermission(sender)) {
                subCommandArguments.add(subCommandEntry.getKey());
            }
        }
        String specificUsageMessage = this.createUsageMessage(subCommandArguments);

        // If they did not give a subcommand
        if (args.length == 0) {
            sender.sendMessage(newline().append(text("Command usage: " + specificUsageMessage, GRAY)));
            return false;
        }

        // If they do not have permission for the subcommand they gave, or the argument is not a valid subcommand
        final @Nullable Pair<String, DivineSubCommand> subCommand = resolveCommand(args[0]);
        if (subCommand == null || !subCommand.second().testPermission(sender)) {
            sender.sendMessage(text("Usage: " + specificUsageMessage, RED));
            return false;
        }

        // Execute the subcommand
        final String[] choppedArgs = Arrays.copyOfRange(args, 1, args.length);
        return subCommand.second().execute(sender, subCommand.first(), choppedArgs);
    }

    private static @Nullable Pair<String, DivineSubCommand> resolveCommand(String label) {
        label = label.toLowerCase(Locale.ENGLISH);
        @Nullable DivineSubCommand subCommand = SUBCOMMANDS.get(label);
        if (subCommand == null) {
            final @Nullable String command = ALIASES.get(label);
            if (command != null) {
                label = command;
                subCommand = SUBCOMMANDS.get(command);
            }
        }

        if (subCommand != null) {
            return Pair.of(label, subCommand);
        }

        return null;
    }
}
