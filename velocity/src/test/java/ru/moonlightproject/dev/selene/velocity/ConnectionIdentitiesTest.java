package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConnectionIdentitiesTest {
    private record EqualPlayer(String name) {}

    @Test
    void equalPlayersFromDifferentConnectionsReceiveDifferentIds() {
        ConnectionIdentities<EqualPlayer> identities = new ConnectionIdentities<>(4);
        EqualPlayer oldConnection = new EqualPlayer("same-account");
        EqualPlayer newConnection = new EqualPlayer("same-account");

        var oldId = identities.open(oldConnection);
        var newId = identities.open(newConnection);
        identities.close(oldConnection);

        assertNotEquals(oldId, newId);
        assertEquals(newId, identities.get(newConnection).orElseThrow());
    }
}
