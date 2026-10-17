package ru.moonlightproject.dev.selene.velocity;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import ru.moonlightproject.dev.selene.core.AuthReply;
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
        boolean captchaRequired,
        boolean passwordTrusted,
        boolean totpTrusted
    ) {}
    record Opened(Session session, Session replaced) {}

    private final int capacity;
    private final LongSupplier nanoTime;
    private final Map<UUID, Session> byConnection = new ConcurrentHashMap<>();
    private final Map<String, UUID> currentByUsername = new ConcurrentHashMap<>();
    private final Set<UUID> awaitingSecondFactor = new HashSet<>();
    private final Set<UUID> authenticated = new HashSet<>();
    private final Map<UUID, Long> managementUntil = new ConcurrentHashMap<>();

    ConnectionSessions(int capacity) {
        this(capacity, System::nanoTime);
    }

    ConnectionSessions(int capacity, LongSupplier nanoTime) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        this.capacity = capacity;
    }

    synchronized Opened open(
        UUID connectionId,
        String username,
        InetAddress address,
        boolean premium,
        boolean captchaRequired
    ) {
        return open(connectionId, username, address, premium, captchaRequired, false, false);
    }
    synchronized Opened open(UUID connectionId,String username,InetAddress address,boolean premium,
        boolean captchaRequired,boolean passwordTrusted,boolean totpTrusted) {
        if (!byConnection.containsKey(connectionId) && byConnection.size() >= capacity) {
            throw new IllegalStateException("authentication session capacity reached");
        }
        String canonical = username.toLowerCase(Locale.ROOT);
        UUID previousConnection = currentByUsername.put(canonical, connectionId);
        Session replaced = previousConnection != null && !previousConnection.equals(connectionId)
            ? byConnection.remove(previousConnection) : null;
        if (replaced != null) clearAuthorization(previousConnection);
        clearAuthorization(connectionId);
        Session session = new Session(
            UUID.randomUUID(), connectionId, username, canonical, address, premium, captchaRequired,
            passwordTrusted, totpTrusted);
        byConnection.put(connectionId, session);
        return new Opened(session, replaced);
    }

    synchronized Optional<AuthRequest> authorizeRelay(UUID connectionId, AuthRequest request) {
        Session session = byConnection.get(connectionId);
        if (session == null || !allowedRelayOperation(connectionId, request.op())
            || !validCredentialFields(request)) return Optional.empty();
        if (!request.session().equals(session.id().toString())) {
            return Optional.empty();
        }
        return Optional.of(new AuthRequest(
            request.op(), session.id().toString(), session.username(),
            session.address().getHostAddress(), request.password(), request.answer(),
            session.premium(), session.captchaRequired(), request.totpCode(), request.recoveryCode(),
            session.passwordTrusted(), session.totpTrusted(), request.captchaKind(),
            request.challengeNonce()));
    }

    synchronized boolean observeReply(UUID connectionId, UUID sessionId, AuthReply reply) {
        if (!isCurrent(connectionId, sessionId) || !reply.session().equals(sessionId.toString())) {
            return false;
        }
        if (reply.status().equals("TOTP_REQUIRED")) awaitingSecondFactor.add(connectionId);
        if (reply.status().equals("AUTHENTICATED")) {
            awaitingSecondFactor.remove(connectionId);
            authenticated.add(connectionId);
        }
        return true;
    }

    synchronized boolean grantManagement(UUID connectionId, UUID sessionId, Duration lifetime) {
        if (!isCurrent(connectionId, sessionId) || !authenticated.contains(connectionId)
            || lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(Duration.ofHours(1)) > 0) {
            return false;
        }
        managementUntil.put(connectionId, saturatingAdd(nanoTime.getAsLong(), lifetime.toNanos()));
        return true;
    }

    synchronized void revokeManagement(UUID connectionId) {
        managementUntil.remove(connectionId);
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
        clearAuthorization(connectionId);
    }

    int size() {
        return byConnection.size();
    }

    private boolean allowedRelayOperation(UUID connectionId, String operation) {
        return switch (operation) {
            case "begin", "answer", "register", "login", "close", "interactive_mistake",
                 "interactive_complete" -> true;
            case "totp_verify" -> awaitingSecondFactor.contains(connectionId);
            case "premium_trust" -> authenticated.contains(connectionId);
            case "totp_status", "totp_setup_begin", "totp_setup_confirm", "totp_disable",
                 "totp_recovery_regenerate" -> authenticated.contains(connectionId)
                    && managementUntil.getOrDefault(connectionId, Long.MIN_VALUE) > nanoTime.getAsLong();
            default -> false;
        };
    }

    private static boolean validCredentialFields(AuthRequest request) {
        boolean password = !request.password().isEmpty();
        boolean answer = !request.answer().isEmpty();
        boolean totp = !request.totpCode().isEmpty();
        boolean recovery = !request.recoveryCode().isEmpty();
        boolean kind = !request.captchaKind().isEmpty();
        boolean nonce = !request.challengeNonce().isEmpty();
        if (password && !Set.of("register", "login").contains(request.op())) return false;
        if (answer && !request.op().equals("answer")) return false;
        if (totp && !Set.of("totp_verify", "totp_setup_confirm", "totp_disable",
            "totp_recovery_regenerate").contains(request.op())) return false;
        if (recovery && !Set.of("totp_verify", "totp_disable").contains(request.op())) return false;
        if (kind && !request.op().equals("begin")) return false;
        if (nonce != Set.of("interactive_mistake", "interactive_complete").contains(request.op())) return false;
        return true;
    }

    private void clearAuthorization(UUID connectionId) {
        awaitingSecondFactor.remove(connectionId);
        authenticated.remove(connectionId);
        managementUntil.remove(connectionId);
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
