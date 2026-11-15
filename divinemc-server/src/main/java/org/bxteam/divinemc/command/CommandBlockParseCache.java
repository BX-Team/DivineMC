package org.bxteam.divinemc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Parse result of one command block, reused while its command and the server's dispatcher stay the same.
 */
@NullMarked
public final class CommandBlockParseCache {
    private @Nullable String command;
    private @Nullable CommandDispatcher<CommandSourceStack> dispatcher;
    private @Nullable ParseResults<CommandSourceStack> parsed;

    public void perform(final Commands commands, final CommandSourceStack source, final String prefixedCommand) {
        final String command = Commands.trimOptionalPrefix(prefixedCommand);
        final CommandDispatcher<CommandSourceStack> dispatcher = commands.getDispatcher();
        ParseResults<CommandSourceStack> parsed = this.parsed;
        if (parsed == null || dispatcher != this.dispatcher || !command.equals(this.command)) {
            parsed = dispatcher.parse(command, source);
            this.parsed = parsed;
            this.command = command;
            this.dispatcher = dispatcher;
        } else {
            // the cached parse holds the source of the run that parsed it: closed output, old position of a minecart
            parsed = Commands.mapSource(parsed, ignored -> source);
        }
        commands.performCommand(parsed, command);
    }
}
