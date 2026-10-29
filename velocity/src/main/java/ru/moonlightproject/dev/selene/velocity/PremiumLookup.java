package ru.moonlightproject.dev.selene.velocity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Bounded Mojang name-to-UUID lookup. It only tells which UUID owns a name; ownership itself is
 * proven later by the online-mode handshake.
 */
final class PremiumLookup {
    /** HTTP status and body of a profile request. */
    record Response(int status, String body) {}
    private record Cached(Optional<UUID> profileId, long expiresAt) {}
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");

    private final int capacity;
    private final long ttlMillis;
    private final Semaphore permits;
    private final Function<String, CompletableFuture<Response>> transport;
    private final Map<String, Cached> cache;

    PremiumLookup(
        int capacity,
        int concurrency,
        Duration ttl,
        Function<String, CompletableFuture<Response>> transport
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
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new Response(response.statusCode(), response.body()));
        });
    }

    /** UUID of the licensed account with this name, or empty when no such account exists. */
    CompletableFuture<Optional<UUID>> profileId(String username, long nowMillis) {
        String canonical = username.toLowerCase(Locale.ROOT);
        synchronized (cache) {
            Cached cached = cache.get(canonical);
            if (cached != null && nowMillis < cached.expiresAt()) {
                return CompletableFuture.completedFuture(cached.profileId());
            }
            if (cached != null) cache.remove(canonical);
        }
        if (!permits.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("premium lookup busy"));
        }
        CompletableFuture<Response> response;
        try {
            response = transport.apply(canonical);
        } catch (RuntimeException error) {
            permits.release();
            return CompletableFuture.failedFuture(error);
        }
        return response.thenApply(reply -> {
            Optional<UUID> id;
            if (reply.status() == 200) id = Optional.of(parseId(reply.body()));
            else if (reply.status() == 204 || reply.status() == 404) id = Optional.empty();
            else throw new IllegalStateException("premium lookup HTTP " + reply.status());
            synchronized (cache) {
                cache.put(canonical, new Cached(id, nowMillis + ttlMillis));
            }
            return id;
        }).whenComplete((ignored, error) -> permits.release());
    }

    static UUID parseId(String body) {
        Matcher matcher = ID.matcher(body == null ? "" : body);
        if (!matcher.find()) throw new IllegalStateException("premium lookup: no profile id");
        String hex = matcher.group(1);
        return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
    }
}
