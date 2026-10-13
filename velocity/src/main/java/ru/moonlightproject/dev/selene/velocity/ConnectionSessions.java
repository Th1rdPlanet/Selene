package ru.moonlightproject.dev.selene.velocity;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import ru.moonlightproject.dev.selene.core.AuthRequest;

/** Connection-bound authentication state owned by the proxy. */
final class ConnectionSessions {
    record Session(
        UUID id,
        UUID connectionId,
        String username,
        String canonicalUsername,
        InetAddress address,
        boolean premium,
        boolean captchaRequired
    ) {}

    private final int capacity;
    private final Map<UUID, Session> byConnection = new ConcurrentHashMap<>();
    private final Map<String, UUID> currentByUsername = new ConcurrentHashMap<>();

    ConnectionSessions(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    synchronized Session open(
        UUID connectionId,
        String username,
        InetAddress address,
        boolean premium,
        boolean captchaRequired
    ) {
        if (!byConnection.containsKey(connectionId) && byConnection.size() >= capacity) {
            throw new IllegalStateException("authentication session capacity reached");
        }
        String canonical = username.toLowerCase(Locale.ROOT);
        UUID previousConnection = currentByUsername.put(canonical, connectionId);
        if (previousConnection != null && !previousConnection.equals(connectionId)) {
            byConnection.remove(previousConnection);
        }
        Session session = new Session(
            UUID.randomUUID(), connectionId, username, canonical, address, premium, captchaRequired);
        byConnection.put(connectionId, session);
        return session;
    }

    Optional<AuthRequest> authorizeRelay(UUID connectionId, AuthRequest request) {
        Session session = byConnection.get(connectionId);
        if (session == null || !allowedRelayOperation(request.op())) return Optional.empty();
        return Optional.of(new AuthRequest(
            request.op(), session.id().toString(), session.username(),
            session.address().getHostAddress(), request.password(), request.answer(),
            session.premium(), session.captchaRequired()));
    }

    boolean isCurrent(UUID connectionId, UUID sessionId) {
        Session session = byConnection.get(connectionId);
        return session != null
            && session.id().equals(sessionId)
            && connectionId.equals(currentByUsername.get(session.canonicalUsername()));
    }

    Optional<Session> get(UUID connectionId) {
        return Optional.ofNullable(byConnection.get(connectionId));
    }

    synchronized void close(UUID connectionId) {
        Session removed = byConnection.remove(connectionId);
        if (removed != null) currentByUsername.remove(removed.canonicalUsername(), connectionId);
    }

    int size() {
        return byConnection.size();
    }

    private static boolean allowedRelayOperation(String operation) {
        return switch (operation) {
            case "begin", "answer", "register", "login", "close" -> true;
            default -> false;
        };
    }
}
