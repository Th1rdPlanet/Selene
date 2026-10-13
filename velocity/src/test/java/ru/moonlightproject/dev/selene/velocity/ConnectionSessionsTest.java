package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.AuthRequest;

class ConnectionSessionsTest {
    @Test
    void replacesOldConnectionAndRejectsItsCompletion() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(16);
        UUID firstConnection = UUID.randomUUID();
        UUID secondConnection = UUID.randomUUID();

        ConnectionSessions.Session first = sessions.open(
            firstConnection, "Player_1", InetAddress.getByName("192.0.2.10"), true, false);
        ConnectionSessions.Session second = sessions.open(
            secondConnection, "Player_1", InetAddress.getByName("192.0.2.10"), true, false);

        assertFalse(sessions.isCurrent(firstConnection, first.id()));
        assertTrue(sessions.isCurrent(secondConnection, second.id()));
    }

    @Test
    void relayRequestUsesAuthoritativeConnectionFacts() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(16);
        UUID connection = UUID.randomUUID();
        ConnectionSessions.Session session = sessions.open(
            connection, "Player_1", InetAddress.getByName("198.51.100.7"), true, true);

        AuthRequest untrusted = new AuthRequest(
            "login", "attacker-session", "OtherName", "127.0.0.1", "secret", "", false, false);
        AuthRequest bound = sessions.authorizeRelay(connection, untrusted).orElseThrow();

        assertEquals(session.id().toString(), bound.session());
        assertEquals("Player_1", bound.username());
        assertEquals("198.51.100.7", bound.ip());
        assertTrue(bound.premium());
        assertTrue(bound.captchaRequired());
        assertEquals("secret", bound.password());
    }

    @Test
    void capacityRejectsNewConnectionWithoutGrowing() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(1);
        sessions.open(UUID.randomUUID(), "First", InetAddress.getByName("192.0.2.1"), false, false);

        assertThrows(IllegalStateException.class, () -> sessions.open(
            UUID.randomUUID(), "Second", InetAddress.getByName("192.0.2.2"), false, false));
        assertEquals(1, sessions.size());
    }
}
