package ru.moonlightproject.dev.selene.velocity;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import ru.moonlightproject.dev.selene.api.MaintenanceState;
import ru.moonlightproject.dev.selene.api.SeleneApi;

final class MaintenanceController implements SeleneApi {
    private final LongSupplier clock;
    private final Consumer<MaintenanceState> publisher;
    private MaintenanceState state = new MaintenanceState(false, 0, Instant.EPOCH, "", "", 0, 0, 0);

    MaintenanceController(LongSupplier clock, Consumer<MaintenanceState> publisher) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.publisher = java.util.Objects.requireNonNull(publisher, "publisher");
    }

    @Override public synchronized MaintenanceState maintenance() {
        return state;
    }

    @Override public synchronized CompletionStage<MaintenanceState> setMaintenance(
        boolean enabled, String reason, String actor
    ) {
        String safeReason = required(reason, "reason");
        String safeActor = required(actor, "actor");
        if (state.enabled() == enabled) return CompletableFuture.completedFuture(state);
        state = new MaintenanceState(enabled, state.revision() + 1, Instant.ofEpochMilli(clock.getAsLong()),
            safeActor, safeReason, state.whitelistSize(), state.connectedServers(), state.acknowledgedServers());
        publisher.accept(state);
        return CompletableFuture.completedFuture(state);
    }

    synchronized void updateTopology(int whitelistSize, int connectedServers, int acknowledgedServers) {
        state = new MaintenanceState(state.enabled(), state.revision(), state.changedAt(), state.actor(), state.reason(),
            whitelistSize, connectedServers, acknowledgedServers);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (value.length() > 256) throw new IllegalArgumentException(field + " is too long");
        return value.trim();
    }
}
