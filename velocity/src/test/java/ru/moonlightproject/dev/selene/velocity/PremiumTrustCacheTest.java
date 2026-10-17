package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.PremiumTrust;

class PremiumTrustCacheTest {
    @Test
    void trustsOnlyExactNormalizedNameCanonicalIpAndUnexpiredEntry() throws Exception {
        PremiumTrustCache cache = new PremiumTrustCache(8, Duration.ofMinutes(2), true);
        cache.replace(Map.of("player_1", new PremiumTrust("2001:db8:0:0:0:0:0:1", 2_000)), 1_000_000);
        assertTrue(cache.trusted("PLAYER_1", InetAddress.getByName("2001:db8::1"), 1_500, 1_010_000));
        assertFalse(cache.trusted("player_2", InetAddress.getByName("2001:db8::1"), 1_500, 1_010_000));
        assertFalse(cache.trusted("player_1", InetAddress.getByName("2001:db8::2"), 1_500, 1_010_000));
        assertFalse(cache.trusted("player_1", InetAddress.getByName("2001:db8::1"), 2_000, 1_010_000));
    }

    @Test
    void overflowOrStaleSnapshotFallsBackToMojang() throws Exception {
        PremiumTrustCache cache = new PremiumTrustCache(1, Duration.ofSeconds(30), true);
        cache.replace(Map.of("a", new PremiumTrust("192.0.2.1", 5_000),
            "b", new PremiumTrust("192.0.2.2", 5_000)), 1_000);
        assertFalse(cache.trusted("a", InetAddress.getByName("192.0.2.1"), 2_000, 2_000));
        cache.replace(Map.of("a", new PremiumTrust("192.0.2.1", 5_000)), 1_000);
        assertFalse(cache.trusted("a", InetAddress.getByName("192.0.2.1"), 2_000, 32_000));
    }

    @Test
    void disabledCacheAlwaysFallsBack() throws Exception {
        PremiumTrustCache cache = new PremiumTrustCache(8, Duration.ofMinutes(2), false);
        cache.replace(Map.of("a", new PremiumTrust("192.0.2.1", 5_000)), 1_000);
        assertFalse(cache.trusted("a", InetAddress.getByName("192.0.2.1"), 2_000, 2_000));
    }
}
