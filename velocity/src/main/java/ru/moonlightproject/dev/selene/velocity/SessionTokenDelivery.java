package ru.moonlightproject.dev.selene.velocity;

import java.util.Objects;

final class SessionTokenDelivery {
    enum Outcome {
        IGNORED,
        ATTEMPTED,
        EXHAUSTED
    }

    interface Channel {
        String serverName();

        boolean send(byte[] payload);
    }

    private SessionTokenDelivery() {
    }

    static Outcome attempt(
        String expectedServer,
        boolean current,
        boolean acknowledged,
        int attempt,
        int maxAttempts,
        Channel channel,
        byte[] payload
    ) {
        Objects.requireNonNull(expectedServer, "expectedServer");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(payload, "payload");

        if (!current || acknowledged || !expectedServer.equals(channel.serverName())) {
            return Outcome.IGNORED;
        }
        if (attempt >= maxAttempts) {
            return Outcome.EXHAUSTED;
        }
        channel.send(payload);
        return Outcome.ATTEMPTED;
    }
}
