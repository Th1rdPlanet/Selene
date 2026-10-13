package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionTokenDeliveryTest {
    @Test
    void providedConnectionDeliversTokenWithoutAPlayerCurrentServerLookup() {
        AtomicBoolean sent = new AtomicBoolean();
        byte[] token = new byte[16];

        SessionTokenDelivery.Outcome outcome = SessionTokenDelivery.attempt(
            "auth", true, false, 0, 4,
            new SessionTokenDelivery.Channel() {
                @Override public String serverName() { return "auth"; }
                @Override public boolean send(byte[] payload) {
                    assertSame(token, payload);
                    sent.set(true);
                    return true;
                }
            },
            token);

        assertEquals(SessionTokenDelivery.Outcome.ATTEMPTED, outcome);
        assertTrue(sent.get());
    }

    @Test
    void staleWrongServerAndExhaustedAttemptsNeverSend() {
        AtomicBoolean sent = new AtomicBoolean();
        SessionTokenDelivery.Channel connection = new SessionTokenDelivery.Channel() {
            @Override public String serverName() { return "lobby"; }
            @Override public boolean send(byte[] payload) { sent.set(true); return true; }
        };

        assertEquals(SessionTokenDelivery.Outcome.IGNORED,
            SessionTokenDelivery.attempt("auth", true, false, 0, 4, connection, new byte[16]));
        assertEquals(SessionTokenDelivery.Outcome.IGNORED,
            SessionTokenDelivery.attempt("auth", false, false, 0, 4, connection, new byte[16]));
        assertEquals(SessionTokenDelivery.Outcome.IGNORED,
            SessionTokenDelivery.attempt("auth", true, true, 0, 4, connection, new byte[16]));
        assertEquals(SessionTokenDelivery.Outcome.EXHAUSTED,
            SessionTokenDelivery.attempt("lobby", true, false, 4, 4, connection, new byte[16]));
        assertFalse(sent.get());
    }
}
