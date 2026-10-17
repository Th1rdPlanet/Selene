package ru.moonlightproject.dev.selene.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

final class TwoFactorCommand implements SimpleCommand {
    private static final List<String> ACTIONS = List.of("enable", "status", "disable", "recovery");
    private final VelocityMessages messages;
    private final BiConsumer<Player, TwoFactorRoutes.Action> handler;

    TwoFactorCommand(VelocityMessages messages, BiConsumer<Player, TwoFactorRoutes.Action> handler) {
        this.messages = messages;
        this.handler = handler;
    }

    @Override public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messages.component("two-factor-player-only"));
            return;
        }
        String[] arguments = invocation.arguments();
        if (arguments.length != 1) {
            player.sendMessage(messages.component("two-factor-help"));
            return;
        }
        TwoFactorRoutes.Action.parse(arguments[0]).ifPresentOrElse(
            action -> handler.accept(player, action),
            () -> player.sendMessage(messages.component("two-factor-help")));
    }

    @Override public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length > 1) return List.of();
        String prefix = invocation.arguments().length == 0 ? ""
            : invocation.arguments()[0].toLowerCase(Locale.ROOT);
        return ACTIONS.stream().filter(action -> action.startsWith(prefix)).toList();
    }
}
