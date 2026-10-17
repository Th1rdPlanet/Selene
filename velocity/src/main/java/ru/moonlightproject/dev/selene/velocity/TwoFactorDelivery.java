package ru.moonlightproject.dev.selene.velocity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class TwoFactorDelivery {
    @FunctionalInterface
    interface Sender {
        boolean send(UUID token, TwoFactorRoutes.Action action);
    }

    private TwoFactorDelivery() {}

    static void prepare(Set<UUID> acknowledgedTokens, UUID token) {
        acknowledgedTokens.remove(token);
    }

    static boolean afterSessionAcknowledged(
        Optional<TwoFactorRoutes.Route> route,
        UUID token,
        Sender sender
    ) {
        return route.map(value -> sender.send(token, value.action())).orElse(false);
    }
}
