package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PremiumLookupTest {
    @Test
    void cachesPositiveAndNegativeProfileResults() {
        AtomicInteger calls = new AtomicInteger();
        PremiumLookup lookup = new PremiumLookup(
            8, 2, Duration.ofMinutes(1), name -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(name.equals("premium") ? 200 : 404);
            });

        assertTrue(lookup.hasProfile("Premium", 1_000).join());
        assertTrue(lookup.hasProfile("premium", 1_001).join());
        assertFalse(lookup.hasProfile("offline", 1_002).join());
        assertFalse(lookup.hasProfile("offline", 1_003).join());
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsWorkWhenConcurrencyIsExhausted() {
        CompletableFuture<Integer> pending = new CompletableFuture<>();
        PremiumLookup lookup = new PremiumLookup(8, 1, Duration.ofMinutes(1), name -> pending);
        lookup.hasProfile("first", 1_000);

        assertTrue(lookup.hasProfile("second", 1_001).isCompletedExceptionally());
    }
}
