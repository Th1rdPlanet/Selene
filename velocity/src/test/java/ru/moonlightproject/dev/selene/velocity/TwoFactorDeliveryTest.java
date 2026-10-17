package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TwoFactorDeliveryTest {
    @Test
    void returningToAuthRequiresANewSessionAcknowledgement() {
        UUID token = UUID.randomUUID();
        Set<UUID> acknowledged = ConcurrentHashMap.newKeySet();
        acknowledged.add(token);

        TwoFactorDelivery.prepare(acknowledged, token);

        assertFalse(acknowledged.contains(token));
    }

    @Test
    void managementActionIsSentOnlyAfterPaperAcknowledgesTheSession() {
        UUID token = UUID.randomUUID();
        var route = new TwoFactorRoutes.Route(TwoFactorRoutes.Action.ENABLE, "lobby");
        AtomicBoolean sent = new AtomicBoolean();

        assertFalse(TwoFactorDelivery.afterSessionAcknowledged(Optional.empty(), token,
            (actualToken, action) -> sent.compareAndSet(false, true)));
        assertFalse(sent.get());

        assertTrue(TwoFactorDelivery.afterSessionAcknowledged(Optional.of(route), token,
            (actualToken, action) -> {
                assertEquals(token, actualToken);
                assertEquals(TwoFactorRoutes.Action.ENABLE, action);
                sent.set(true);
                return true;
            }));
        assertTrue(sent.get());
    }
}
