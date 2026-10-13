package ru.moonlightproject.dev.selene.velocity;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Assigns an ID to an object identity, never to account equality. */
final class ConnectionIdentities<T> {
    private final int capacity;
    private final Map<T, UUID> identities = new IdentityHashMap<>();

    ConnectionIdentities(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    synchronized UUID open(T connection) {
        UUID existing = identities.get(connection);
        if (existing != null) return existing;
        if (identities.size() >= capacity) throw new IllegalStateException("connection identity capacity reached");
        UUID created = UUID.randomUUID();
        identities.put(connection, created);
        return created;
    }

    synchronized Optional<UUID> get(T connection) {
        return Optional.ofNullable(identities.get(connection));
    }

    synchronized void close(T connection) {
        identities.remove(connection);
    }
}
