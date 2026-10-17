package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TwoFactorRoutesTest {
    @Test
    void remembersActionAndPreviousServerUntilCompletionOrDisconnect() {
        TwoFactorRoutes routes = new TwoFactorRoutes(2);
        UUID connection = UUID.randomUUID();
        assertTrue(routes.begin(connection, TwoFactorRoutes.Action.ENABLE, "survival"));
        assertEquals("survival", routes.get(connection).orElseThrow().previousServer());
        assertEquals(TwoFactorRoutes.Action.ENABLE, routes.get(connection).orElseThrow().action());
        assertEquals("survival", routes.complete(connection).orElseThrow().previousServer());
        assertTrue(routes.get(connection).isEmpty());
        assertTrue(routes.begin(connection, TwoFactorRoutes.Action.STATUS, "lobby"));
        routes.clear(connection);
        assertTrue(routes.get(connection).isEmpty());
    }

    @Test
    void capacityAndDuplicateStartsAreBounded() {
        TwoFactorRoutes routes = new TwoFactorRoutes(1);
        UUID first = UUID.randomUUID();
        assertTrue(routes.begin(first, TwoFactorRoutes.Action.DISABLE, "lobby"));
        assertFalse(routes.begin(first, TwoFactorRoutes.Action.ENABLE, "other"));
        assertFalse(routes.begin(UUID.randomUUID(), TwoFactorRoutes.Action.STATUS, "lobby"));
    }

    @Test
    void parserAcceptsOnlyNonSecretManagementActions() {
        assertEquals(TwoFactorRoutes.Action.ENABLE, TwoFactorRoutes.Action.parse("enable").orElseThrow());
        assertEquals(TwoFactorRoutes.Action.RECOVERY, TwoFactorRoutes.Action.parse("recovery").orElseThrow());
        assertTrue(TwoFactorRoutes.Action.parse("123456").isEmpty());
    }
}
