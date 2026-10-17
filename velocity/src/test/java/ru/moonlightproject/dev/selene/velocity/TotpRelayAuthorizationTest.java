package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.AuthReply;
import ru.moonlightproject.dev.selene.core.AuthRequest;

class TotpRelayAuthorizationTest {
    private static AuthRequest request(String op, ConnectionSessions.Session session) {
        return new AuthRequest(op, session.id().toString(), "forged", "203.0.113.9",
            "", "", false, false, "", "");
    }

    @Test
    void loginTotpRequiresBackendChallengeAndUsesAuthoritativeIdentity() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(8);
        UUID connection = UUID.randomUUID();
        ConnectionSessions.Session session = sessions.open(connection, "Player_1",
            InetAddress.getByName("192.0.2.10"), true, true).session();
        AuthRequest verify = new AuthRequest("totp_verify", session.id().toString(), "forged",
            "203.0.113.9", "", "", false, false, "123456", "");

        assertTrue(sessions.authorizeRelay(connection, verify).isEmpty());
        assertTrue(sessions.observeReply(connection, session.id(), AuthReply.status("TOTP_REQUIRED",
            session.id().toString())));
        AuthRequest bound = sessions.authorizeRelay(connection, verify).orElseThrow();
        assertEquals("Player_1", bound.username());
        assertEquals("192.0.2.10", bound.ip());
        assertEquals("123456", bound.totpCode());
    }

    @Test
    void managementNeedsAuthenticatedBoundGrantAndExpires() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        ConnectionSessions sessions = new ConnectionSessions(8, clock::get);
        UUID connection = UUID.randomUUID();
        ConnectionSessions.Session session = sessions.open(connection, "Player_1",
            InetAddress.getByName("192.0.2.10"), false, false).session();

        assertFalse(sessions.grantManagement(connection, session.id(), Duration.ofSeconds(30)));
        sessions.observeReply(connection, session.id(), AuthReply.status("AUTHENTICATED",
            session.id().toString()));
        assertTrue(sessions.grantManagement(connection, session.id(), Duration.ofSeconds(30)));
        assertTrue(sessions.authorizeRelay(connection, request("totp_setup_begin", session)).isPresent());
        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        assertTrue(sessions.authorizeRelay(connection, request("totp_setup_begin", session)).isEmpty());
    }

    @Test
    void staleOrGuessedSessionCannotUseManagementGrant() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(8);
        UUID firstConnection = UUID.randomUUID();
        ConnectionSessions.Session first = sessions.open(firstConnection, "Player_1",
            InetAddress.getByName("192.0.2.10"), false, false).session();
        sessions.observeReply(firstConnection, first.id(), AuthReply.status("AUTHENTICATED",
            first.id().toString()));
        sessions.grantManagement(firstConnection, first.id(), Duration.ofMinutes(5));

        UUID replacement = UUID.randomUUID();
        ConnectionSessions.Session current = sessions.open(replacement, "Player_1",
            InetAddress.getByName("192.0.2.10"), false, false).session();
        assertTrue(sessions.authorizeRelay(replacement, request("totp_status", first)).isEmpty());
        assertTrue(sessions.authorizeRelay(firstConnection, request("totp_status", first)).isEmpty());
        assertTrue(sessions.authorizeRelay(replacement, request("totp_status", current)).isEmpty());
    }

    @Test
    void credentialFieldsAreRejectedOutsideTheirDedicatedOperations() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(8);
        UUID connection = UUID.randomUUID();
        ConnectionSessions.Session session = sessions.open(connection, "Player_1",
            InetAddress.getByName("192.0.2.10"), false, false).session();
        AuthRequest leaked = new AuthRequest("begin", session.id().toString(), "Player_1",
            "192.0.2.10", "password", "captcha", false, false, "123456", "RECOVERY");
        assertTrue(sessions.authorizeRelay(connection, leaked).isEmpty());
    }

    @Test
    void closeClearsAuthenticationAndManagementState() throws Exception {
        ConnectionSessions sessions = new ConnectionSessions(8);
        UUID connection = UUID.randomUUID();
        ConnectionSessions.Session session = sessions.open(connection, "Player_1",
            InetAddress.getByName("192.0.2.10"), false, false).session();
        sessions.observeReply(connection, session.id(), AuthReply.status("AUTHENTICATED",
            session.id().toString()));
        sessions.grantManagement(connection, session.id(), Duration.ofMinutes(5));
        sessions.close(connection);
        assertTrue(sessions.authorizeRelay(connection, request("totp_status", session)).isEmpty());
    }
}
