package ru.moonlightproject.dev.selene.velocity;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import ru.moonlightproject.dev.selene.core.PremiumTrust;

/** Bounded backend snapshot used only to skip a redundant Mojang ownership proof. */
final class PremiumTrustCache {
    private record Entry(String ip, long expiresAt) {}

    private final int capacity;
    private final long maxSnapshotAgeMillis;
    private final boolean enabled;
    private final long maximumTrustSeconds;
    private Map<String, Entry> entries = Map.of();
    private long loadedAtMillis = Long.MIN_VALUE;
    private boolean usable;

    PremiumTrustCache(int capacity, Duration maxSnapshotAge, boolean enabled) {
        this(capacity, maxSnapshotAge, Duration.ofDays(3650), enabled);
    }

    PremiumTrustCache(int capacity, Duration maxSnapshotAge, Duration maximumTrust, boolean enabled) {
        if (capacity < 1 || maxSnapshotAge.isZero() || maxSnapshotAge.isNegative()) {
            throw new IllegalArgumentException("invalid premium trust cache bounds");
        }
        this.capacity = capacity;
        this.maxSnapshotAgeMillis = maxSnapshotAge.toMillis();
        this.maximumTrustSeconds = maximumTrust.toSeconds();
        this.enabled = enabled;
    }

    synchronized void replace(Map<String, PremiumTrust> snapshot, long loadedAtMillis) {
        if (!enabled || snapshot.size() > capacity) {
            clear();
            return;
        }
        Map<String, Entry> replacement = new HashMap<>();
        try {
            for (var item : snapshot.entrySet()) {
                String name = item.getKey().toLowerCase(Locale.ROOT);
                String ip = InetAddress.getByName(item.getValue().ip()).getHostAddress();
                long localExpiry = Math.min(item.getValue().expiresAt(),
                    loadedAtMillis / 1000 + maximumTrustSeconds);
                if (!name.matches("[a-z0-9_]{3,16}") || replacement.put(name,
                    new Entry(ip, localExpiry)) != null) {
                    clear();
                    return;
                }
            }
        } catch (Exception invalidSnapshot) {
            clear();
            return;
        }
        entries = Map.copyOf(replacement);
        this.loadedAtMillis = loadedAtMillis;
        usable = true;
    }

    synchronized boolean trusted(
        String username, InetAddress address, long epochSeconds, long nowMillis
    ) {
        if (!enabled || !usable || nowMillis < loadedAtMillis
            || nowMillis - loadedAtMillis >= maxSnapshotAgeMillis) return false;
        Entry entry = entries.get(username.toLowerCase(Locale.ROOT));
        return entry != null && epochSeconds < entry.expiresAt()
            && entry.ip().equals(address.getHostAddress());
    }

    synchronized void clear() {
        entries = Map.of();
        loadedAtMillis = Long.MIN_VALUE;
        usable = false;
    }
}
