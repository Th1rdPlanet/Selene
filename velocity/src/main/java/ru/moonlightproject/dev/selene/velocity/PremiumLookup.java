package ru.moonlightproject.dev.selene.velocity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/** Bounded profile-name discovery. Mojang ownership is proven later by Velocity online mode. */
final class PremiumLookup {
    private record Cached(boolean premium, long expiresAt) {}

    private final int capacity;
    private final long ttlMillis;
    private final Semaphore permits;
    private final Function<String, CompletableFuture<Integer>> transport;
    private final Map<String, Cached> cache;

    PremiumLookup(
        int capacity,
        int concurrency,
        Duration ttl,
        Function<String, CompletableFuture<Integer>> transport
    ) {
        if (capacity < 1 || concurrency < 1 || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("invalid premium lookup bounds");
        }
        this.capacity = capacity;
        this.ttlMillis = ttl.toMillis();
        this.permits = new Semaphore(concurrency);
        this.transport = transport;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > PremiumLookup.this.capacity;
            }
        };
    }

    static PremiumLookup mojang(int capacity, int concurrency, Duration ttl, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        return new PremiumLookup(capacity, concurrency, ttl, name -> {
            HttpRequest request = HttpRequest.newBuilder(
                URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(HttpResponse::statusCode);
        });
    }

    CompletableFuture<Boolean> hasProfile(String username, long nowMillis) {
        String canonical = username.toLowerCase(Locale.ROOT);
        synchronized (cache) {
            Cached cached = cache.get(canonical);
            if (cached != null && nowMillis < cached.expiresAt()) {
                return CompletableFuture.completedFuture(cached.premium());
            }
            if (cached != null) cache.remove(canonical);
        }
        if (!permits.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("premium lookup busy"));
        }
        CompletableFuture<Integer> response;
        try {
            response = transport.apply(canonical);
        } catch (RuntimeException error) {
            permits.release();
            return CompletableFuture.failedFuture(error);
        }
        return response.thenApply(status -> {
            boolean premium;
            if (status == 200) premium = true;
            else if (status == 204 || status == 404) premium = false;
            else throw new IllegalStateException("premium lookup HTTP " + status);
            synchronized (cache) {
                cache.put(canonical, new Cached(premium, nowMillis + ttlMillis));
            }
            return premium;
        }).whenComplete((ignored, error) -> permits.release());
    }
}
