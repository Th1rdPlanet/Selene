package ru.moonlightproject.dev.selene.velocity;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TwoFactorRoutes {
    enum Action {
        ENABLE, STATUS, DISABLE, RECOVERY;

        static Optional<Action> parse(String value) {
            try {
                return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException error) {
                return Optional.empty();
            }
        }
    }
    record Route(Action action, String previousServer) {}

    private final int capacity;
    private final Map<UUID, Route> routes = new ConcurrentHashMap<>();

    TwoFactorRoutes(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    synchronized boolean begin(UUID connection, Action action, String previousServer) {
        if (routes.containsKey(connection) || routes.size() >= capacity) return false;
        routes.put(connection, new Route(action, previousServer));
        return true;
    }

    Optional<Route> get(UUID connection) {
        return Optional.ofNullable(routes.get(connection));
    }

    synchronized Optional<Route> complete(UUID connection) {
        return Optional.ofNullable(routes.remove(connection));
    }

    void clear(UUID connection) {
        routes.remove(connection);
    }
}
