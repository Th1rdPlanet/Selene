package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PremiumLookupTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void returnsAndCachesTheProfileId() {
        AtomicInteger calls = new AtomicInteger();
        PremiumLookup lookup = new PremiumLookup(
            8, 2, Duration.ofMinutes(1), name -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(name.equals("notch")
                    ? new PremiumLookup.Response(200, "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}")
                    : new PremiumLookup.Response(404, ""));
            });

        assertEquals(Optional.of(NOTCH), lookup.profileId("Notch", 1_000).join());
        assertEquals(Optional.of(NOTCH), lookup.profileId("notch", 1_001).join());
        assertEquals(Optional.empty(), lookup.profileId("offline", 1_002).join());
        assertEquals(Optional.empty(), lookup.profileId("offline", 1_003).join());
        assertEquals(2, calls.get());
    }

    @Test
    void failsOnUnexpectedStatusOrBody() {
        PremiumLookup lookup = new PremiumLookup(8, 2, Duration.ofMinutes(1),
            name -> CompletableFuture.completedFuture(name.equals("broken")
                ? new PremiumLookup.Response(200, "{}") : new PremiumLookup.Response(429, "")));
        assertTrue(lookup.profileId("broken", 1_000).isCompletedExceptionally());
        assertTrue(lookup.profileId("limited", 1_000).isCompletedExceptionally());
    }

    @Test
    void rejectsWorkWhenConcurrencyIsExhausted() {
        CompletableFuture<PremiumLookup.Response> pending = new CompletableFuture<>();
        PremiumLookup lookup = new PremiumLookup(8, 1, Duration.ofMinutes(1), name -> pending);
        lookup.profileId("first", 1_000);

        assertTrue(lookup.profileId("second", 1_001).isCompletedExceptionally());
    }
}
