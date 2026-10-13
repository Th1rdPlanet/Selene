package ru.moonlightproject.dev.selene.velocity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import ru.moonlightproject.dev.selene.core.AttackLevel;

record VelocityConfig(
    String backendEndpoint,
    boolean allowLoopbackPlaintext,
    Path keyStore,
    String keyStorePasswordEnv,
    Path trustStore,
    String trustStorePasswordEnv,
    InetSocketAddress relayBind,
    String authServer,
    String authServerFingerprint,
    String defaultServer,
    boolean restoreLastServer,
    AttackLevel attackLevel,
    boolean autoAttack,
    Duration attackWindow,
    Duration attackRecovery,
    int lowConnections,
    int mediumConnections,
    int strongConnections,
    int suspiciousIpConnections,
    int suspiciousIpUsernames,
    boolean normalCaptcha,
    boolean forkEnabled,
    boolean mediumBeforeLogin,
    boolean premiumLookupEnabled,
    Duration premiumLookupTimeout,
    int premiumLookupCacheCapacity,
    Duration premiumLookupCacheTtl,
    int premiumLookupConcurrency,
    int sessionCapacity,
    int perIpLimit,
    Duration perIpWindow,
    Map<String, String> messages
) {
    private static final Set<String> KEYS = Set.of(
        "backend-endpoint", "backend-allow-loopback-plaintext", "tls-keystore",
        "tls-keystore-password-env", "tls-truststore", "tls-truststore-password-env",
        "relay-bind", "auth-server", "auth-server-certificate-sha256", "default-server",
        "restore-last-server", "attack-level", "normal-captcha", "session-capacity",
        "per-ip-limit", "per-ip-window-seconds", "fork-enabled", "medium-before-login",
        "attack-auto", "attack-window-seconds", "attack-recovery-seconds",
        "attack-low-connections", "attack-medium-connections", "attack-strong-connections",
        "suspicious-ip-connections", "suspicious-ip-usernames",
        "premium-lookup-enabled", "premium-lookup-timeout-millis",
        "premium-lookup-cache-capacity", "premium-lookup-cache-seconds",
        "premium-lookup-concurrency");

    static VelocityConfig load(Path file) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        for (String key : properties.stringPropertyNames()) {
            if (!KEYS.contains(key) && !key.startsWith("message.")) {
                throw new IllegalArgumentException("Unknown Velocity setting: " + key);
            }
        }
        Path directory = file.toAbsolutePath().getParent();
        Map<String, String> messages = properties.stringPropertyNames().stream()
            .filter(key -> key.startsWith("message."))
            .collect(Collectors.toUnmodifiableMap(
                key -> key.substring("message.".length()), properties::getProperty));
        String fingerprint = required(properties, "auth-server-certificate-sha256").toLowerCase();
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("auth-server-certificate-sha256 must be 64 lowercase hex characters");
        }
        return new VelocityConfig(
            required(properties, "backend-endpoint"),
            bool(properties, "backend-allow-loopback-plaintext"),
            directory.resolve(required(properties, "tls-keystore")).normalize(),
            required(properties, "tls-keystore-password-env"),
            directory.resolve(required(properties, "tls-truststore")).normalize(),
            required(properties, "tls-truststore-password-env"),
            address(required(properties, "relay-bind")),
            required(properties, "auth-server"),
            fingerprint,
            required(properties, "default-server"),
            bool(properties, "restore-last-server"),
            AttackLevel.valueOf(required(properties, "attack-level").toUpperCase()),
            bool(properties, "attack-auto"),
            Duration.ofSeconds(positive(properties, "attack-window-seconds")),
            Duration.ofSeconds(positive(properties, "attack-recovery-seconds")),
            positive(properties, "attack-low-connections"),
            positive(properties, "attack-medium-connections"),
            positive(properties, "attack-strong-connections"),
            positive(properties, "suspicious-ip-connections"),
            positive(properties, "suspicious-ip-usernames"),
            bool(properties, "normal-captcha"),
            bool(properties, "fork-enabled"),
            bool(properties, "medium-before-login"),
            bool(properties, "premium-lookup-enabled"),
            Duration.ofMillis(positive(properties, "premium-lookup-timeout-millis")),
            positive(properties, "premium-lookup-cache-capacity"),
            Duration.ofSeconds(positive(properties, "premium-lookup-cache-seconds")),
            positive(properties, "premium-lookup-concurrency"),
            positive(properties, "session-capacity"),
            positive(properties, "per-ip-limit"),
            Duration.ofSeconds(positive(properties, "per-ip-window-seconds")),
            messages);
    }

    String message(String key) {
        return messages.getOrDefault(key, key);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing setting: " + key);
        return value.trim();
    }

    private static boolean bool(Properties properties, String key) {
        String value = required(properties, key);
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Setting must be true or false: " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static int positive(Properties properties, String key) {
        int value = Integer.parseInt(required(properties, key));
        if (value < 1) throw new IllegalArgumentException("Setting must be positive: " + key);
        return value;
    }

    private static InetSocketAddress address(String value) {
        int delimiter = value.lastIndexOf(':');
        if (delimiter < 1) throw new IllegalArgumentException("Expected host:port: " + value);
        return new InetSocketAddress(value.substring(0, delimiter), Integer.parseInt(value.substring(delimiter + 1)));
    }
}
