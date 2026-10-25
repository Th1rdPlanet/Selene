package ru.moonlightproject.dev.selene.api;

import java.time.Instant;

/** Immutable network-wide maintenance status exposed to Velocity plugins. */
public record MaintenanceState(
    boolean enabled,
    long revision,
    Instant changedAt,
    String actor,
    String reason,
    int whitelistSize,
    int connectedServers,
    int acknowledgedServers
) {
    public MaintenanceState {
        if (revision < 0 || whitelistSize < 0 || connectedServers < 0 || acknowledgedServers < 0
            || acknowledgedServers > connectedServers) throw new IllegalArgumentException("Invalid maintenance state");
        changedAt = java.util.Objects.requireNonNull(changedAt, "changedAt");
        actor = actor == null ? "" : actor;
        reason = reason == null ? "" : reason;
    }

    public boolean converged() {
        return connectedServers == acknowledgedServers;
    }
}
