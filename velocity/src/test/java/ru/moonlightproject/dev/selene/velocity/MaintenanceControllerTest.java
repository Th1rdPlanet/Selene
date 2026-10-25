package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.api.MaintenanceState;

class MaintenanceControllerTest {
    @Test void startsDisabledAndPublishesMonotonicTransitions() {
        var published = new ArrayList<MaintenanceState>();
        var controller = new MaintenanceController(() -> 1_700_000_000_000L, published::add);

        assertFalse(controller.maintenance().enabled());
        assertEquals(0, controller.maintenance().revision());

        MaintenanceState enabled = controller.setMaintenance(true, "backend repair", "control-plugin")
            .toCompletableFuture().join();
        assertTrue(enabled.enabled());
        assertEquals(1, enabled.revision());
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), enabled.changedAt());
        assertEquals("control-plugin", enabled.actor());
        assertEquals("backend repair", enabled.reason());
        assertEquals(1, published.size());

        assertSame(enabled, controller.setMaintenance(true, "ignored", "ignored")
            .toCompletableFuture().join());
        assertEquals(1, published.size());

        MaintenanceState disabled = controller.setMaintenance(false, "repair complete", "control-plugin")
            .toCompletableFuture().join();
        assertFalse(disabled.enabled());
        assertEquals(2, disabled.revision());
        assertEquals(2, published.size());
    }

    @Test void rejectsBlankAuditFields() {
        var controller = new MaintenanceController(System::currentTimeMillis, ignored -> {});
        assertThrows(IllegalArgumentException.class,
            () -> controller.setMaintenance(true, " ", "plugin"));
        assertThrows(IllegalArgumentException.class,
            () -> controller.setMaintenance(true, "reason", " "));
    }

    @Test void topologyMetadataUpdatesWithoutChangingTheModeRevision() {
        var controller = new MaintenanceController(System::currentTimeMillis, ignored -> {});
        controller.updateTopology(7, 3, 2);
        MaintenanceState state = controller.maintenance();
        assertEquals(7, state.whitelistSize());
        assertEquals(3, state.connectedServers());
        assertEquals(2, state.acknowledgedServers());
        assertFalse(state.converged());
        assertEquals(0, state.revision());

        controller.updateTopology(7, 3, 3);
        assertTrue(controller.maintenance().converged());
    }
}
