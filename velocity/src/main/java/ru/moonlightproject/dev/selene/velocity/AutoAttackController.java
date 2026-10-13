package ru.moonlightproject.dev.selene.velocity;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import ru.moonlightproject.dev.selene.core.AttackLevel;

/** Bounded, local-only signal aggregation. It performs no DNS, GeoIP or backend work. */
final class AutoAttackController {
    private static final class IpWindow {
        final ArrayDeque<Long> attempts = new ArrayDeque<>();
        final Map<String, Long> usernames = new HashMap<>();
    }

    private final int capacity;
    private final long windowMillis;
    private final long recoveryMillis;
    private final int lowThreshold;
    private final int mediumThreshold;
    private final int strongThreshold;
    private final int suspiciousAttempts;
    private final int suspiciousNames;
    private final ArrayDeque<Long> global = new ArrayDeque<>();
    private final Map<String, IpWindow> ips = new HashMap<>();
    private long overflowUntil;
    private long recoveryUntil;
    private AttackLevel current = AttackLevel.NORMAL;

    AutoAttackController(
        int capacity,
        Duration window,
        Duration recovery,
        int lowThreshold,
        int mediumThreshold,
        int strongThreshold,
        int suspiciousAttempts,
        int suspiciousNames
    ) {
        if (capacity < 1 || window.isZero() || window.isNegative() || recovery.isNegative()
            || lowThreshold < 1 || mediumThreshold <= lowThreshold || strongThreshold <= mediumThreshold
            || suspiciousAttempts < 1 || suspiciousNames < 1) {
            throw new IllegalArgumentException("invalid automatic attack policy");
        }
        this.capacity = capacity;
        this.windowMillis = window.toMillis();
        this.recoveryMillis = recovery.toMillis();
        this.lowThreshold = lowThreshold;
        this.mediumThreshold = mediumThreshold;
        this.strongThreshold = strongThreshold;
        this.suspiciousAttempts = suspiciousAttempts;
        this.suspiciousNames = suspiciousNames;
    }

    synchronized void observe(String ip, String username, long nowMillis) {
        prune(nowMillis);
        if (global.size() >= strongThreshold) global.removeFirst();
        global.addLast(nowMillis);
        IpWindow window = ips.get(ip);
        if (window == null) {
            if (ips.size() >= capacity) {
                overflowUntil = Math.max(overflowUntil, nowMillis + windowMillis);
                return;
            }
            window = new IpWindow();
            ips.put(ip, window);
        }
        if (window.attempts.size() >= suspiciousAttempts) window.attempts.removeFirst();
        window.attempts.addLast(nowMillis);
        if (window.usernames.size() < suspiciousNames || window.usernames.containsKey(username.toLowerCase())) {
            window.usernames.put(username.toLowerCase(), nowMillis);
        }
    }

    synchronized boolean suspicious(String ip, long nowMillis) {
        prune(nowMillis);
        IpWindow window = ips.get(ip);
        return nowMillis < overflowUntil || (window != null
            && (window.attempts.size() >= suspiciousAttempts || window.usernames.size() >= suspiciousNames));
    }

    synchronized AttackLevel level(long nowMillis) {
        prune(nowMillis);
        AttackLevel desired;
        if (global.size() >= strongThreshold) desired = AttackLevel.STRONG;
        else if (global.size() >= mediumThreshold) desired = AttackLevel.MEDIUM;
        else if (global.size() >= lowThreshold || hasSuspiciousIp(nowMillis)) desired = AttackLevel.LOW;
        else desired = AttackLevel.NORMAL;

        if (desired.ordinal() > current.ordinal()) {
            current = desired;
            recoveryUntil = 0;
        } else if (desired.ordinal() < current.ordinal()) {
            if (recoveryUntil == 0) recoveryUntil = nowMillis + recoveryMillis;
            if (nowMillis >= recoveryUntil) {
                current = desired;
                recoveryUntil = 0;
            }
        } else {
            recoveryUntil = 0;
        }
        return current;
    }

    private boolean hasSuspiciousIp(long nowMillis) {
        if (nowMillis < overflowUntil) return true;
        return ips.values().stream().anyMatch(window ->
            window.attempts.size() >= suspiciousAttempts || window.usernames.size() >= suspiciousNames);
    }

    private void prune(long nowMillis) {
        long cutoff = nowMillis - windowMillis;
        while (!global.isEmpty() && global.peekFirst() <= cutoff) global.removeFirst();
        ips.entrySet().removeIf(entry -> {
            IpWindow window = entry.getValue();
            while (!window.attempts.isEmpty() && window.attempts.peekFirst() <= cutoff) {
                window.attempts.removeFirst();
            }
            window.usernames.entrySet().removeIf(username -> username.getValue() <= cutoff);
            return window.attempts.isEmpty();
        });
        if (nowMillis >= overflowUntil) overflowUntil = 0;
    }

    synchronized int trackedAttemptCount() {
        return global.size() + ips.values().stream().mapToInt(window -> window.attempts.size()).sum();
    }
}
