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
            firstConnection, "Player_1", InetAddress.getByName("192.0.2.10"), true, false).session();
        ConnectionSessions.Opened opened = sessions.open(
            secondConnection, "Player_1", InetAddress.getByName("192.0.2.10"), true, false);
        ConnectionSessions.Session second = opened.session();

        assertFalse(sessions.isCurrent(firstConnection, first.id()));
        assertTrue(sessions.isCurrent(secondConnection, second.id()));
        assertEquals(first, opened.replaced());
    }

    @Test
    void relayRequestUsesAuthoritativeConnectionFacts() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(16);
        UUID connection = UUID.randomUUID();
        ConnectionSessions.Session session = sessions.open(
            connection, "Player_1", InetAddress.getByName("198.51.100.7"), true, true).session();

        AuthRequest untrusted = new AuthRequest(
            "login", session.id().toString(), "OtherName", "127.0.0.1", "secret", "", false, false);
        AuthRequest bound = sessions.authorizeRelay(connection, untrusted).orElseThrow();

        assertEquals(session.id().toString(), bound.session());
        assertEquals("Player_1", bound.username());
        assertEquals("198.51.100.7", bound.ip());
        assertTrue(bound.premium());
        assertTrue(bound.captchaRequired());
        assertEquals("secret", bound.password());
    }

    @Test
    void relayCannotForgeGraceFlags() throws Exception {
        ConnectionSessions sessions=new ConnectionSessions(16);UUID connection=UUID.randomUUID();
        ConnectionSessions.Session session=sessions.open(connection,"Player_1",
            InetAddress.getByName("198.51.100.7"),false,false,true,false).session();
        AuthRequest forged=new AuthRequest("begin",session.id().toString(),"Player_1","198.51.100.7",
            "","",false,false,"","",false,true);
        AuthRequest bound=sessions.authorizeRelay(connection,forged).orElseThrow();
        assertTrue(bound.passwordTrusted());
        assertFalse(bound.totpTrusted());
    }

    @Test
    void staleRelayRequestCannotAuthenticateReplacementConnection() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(16);
        ConnectionSessions.Session oldSession = sessions.open(
            UUID.randomUUID(), "Player_1", InetAddress.getByName("198.51.100.7"), false, false).session();
        UUID replacementConnection = UUID.randomUUID();
        sessions.open(
            replacementConnection, "Player_1", InetAddress.getByName("198.51.100.7"), false, false);

        AuthRequest delayed = new AuthRequest(
            "login", oldSession.id().toString(), "Player_1", "198.51.100.7", "secret", "", false, false);

        assertTrue(sessions.authorizeRelay(replacementConnection, delayed).isEmpty());
    }

    @Test
    void beginAlsoRequiresProxyIssuedConnectionSession() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(16);
        UUID connection = UUID.randomUUID();
        ConnectionSessions.Session session = sessions.open(
            connection, "Player_1", InetAddress.getByName("198.51.100.7"), false, false).session();

        AuthRequest guessed = new AuthRequest(
            "begin", UUID.randomUUID().toString(), "Player_1", "198.51.100.7", "", "", false, false);
        AuthRequest exact = new AuthRequest(
            "begin", session.id().toString(), "Player_1", "198.51.100.7", "", "", false, false);

        assertTrue(sessions.authorizeRelay(connection, guessed).isEmpty());
        assertTrue(sessions.authorizeRelay(connection, exact).isPresent());
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
