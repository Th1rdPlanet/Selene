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
    int failureMinimumSamples,
    int failureRatioLowPercent,
    int failureRatioMediumPercent,
    int pendingSessionsMedium,
    int pendingSessionsStrong,
    long outboundPacketsLow,
    long outboundPacketsMedium,
    long outboundPacketsStrong,
    long outboundBytesLow,
    long outboundBytesMedium,
    long outboundBytesStrong,
    boolean normalCaptcha,
    boolean forkEnabled,
    boolean forkRequired,
    boolean premiumLookupEnabled,
    Duration premiumLookupTimeout,
    int premiumLookupCacheCapacity,
    Duration premiumLookupCacheTtl,
    int premiumLookupConcurrency,
    boolean premiumTrustEnabled,
    Duration premiumTrustTtl,
    int premiumTrustCapacity,
    Duration premiumTrustSnapshotMaxAge,
    Duration twoFactorManagementTimeout,
    String twoFactorCommandAliases,
    int sessionCapacity,
    int perIpLimit,
    Duration perIpWindow,
    Map<AttackLevel, AttackProfile> attackProfiles,
    boolean otelEnabled,
    String otlpEndpoint,
    String otelServiceName,
    boolean prometheusEnabled,
    String prometheusBind,
    Set<String> commandAdmins,
    Map<String, String> messages
) {
    private static final Set<String> KEYS = Set.of(
        "backend-endpoint", "backend-allow-loopback-plaintext", "tls-keystore",
        "tls-keystore-password-env", "tls-truststore", "tls-truststore-password-env",
        "relay-bind", "auth-server", "auth-server-certificate-sha256", "default-server",
        "restore-last-server", "attack-level", "normal-captcha", "session-capacity",
        "per-ip-limit", "per-ip-window-seconds", "fork-enabled",
        "attack-auto", "attack-window-seconds", "attack-recovery-seconds",
        "attack-low-connections", "attack-medium-connections", "attack-strong-connections",
        "suspicious-ip-connections", "suspicious-ip-usernames",
        "failure-minimum-samples", "failure-ratio-low-percent", "failure-ratio-medium-percent",
        "pending-sessions-medium", "pending-sessions-strong",
        "outbound-packets-low-per-second", "outbound-packets-medium-per-second", "outbound-packets-strong-per-second",
        "outbound-bytes-low-per-second", "outbound-bytes-medium-per-second", "outbound-bytes-strong-per-second",
        "profiles.normal.captcha", "profiles.normal.premium-bypass", "profiles.normal.suspicious-only", "profiles.normal.greenlist-only", "profiles.normal.reconnect-limit", "profiles.normal.password-grace-seconds", "profiles.normal.captcha-grace-seconds", "profiles.normal.totp-grace-seconds",
        "profiles.low.captcha", "profiles.low.premium-bypass", "profiles.low.suspicious-only", "profiles.low.greenlist-only", "profiles.low.reconnect-limit", "profiles.low.password-grace-seconds", "profiles.low.captcha-grace-seconds", "profiles.low.totp-grace-seconds",
        "profiles.medium.captcha", "profiles.medium.premium-bypass", "profiles.medium.suspicious-only", "profiles.medium.greenlist-only", "profiles.medium.reconnect-limit", "profiles.medium.password-grace-seconds", "profiles.medium.captcha-grace-seconds", "profiles.medium.totp-grace-seconds",
        "profiles.strong.captcha", "profiles.strong.premium-bypass", "profiles.strong.suspicious-only", "profiles.strong.greenlist-only", "profiles.strong.reconnect-limit", "profiles.strong.password-grace-seconds", "profiles.strong.captcha-grace-seconds", "profiles.strong.totp-grace-seconds",
        "premium-lookup-enabled", "premium-lookup-timeout-millis",
        "premium-lookup-cache-capacity", "premium-lookup-cache-seconds",
        "premium-lookup-concurrency", "premium-trust-enabled", "premium-trust-ttl-days",
        "premium-trust-capacity", "premium-trust-snapshot-max-age-seconds",
        "two-factor-management-timeout-seconds", "two-factor-command-aliases",
        "telemetry-otel-enabled", "telemetry-otlp-endpoint", "telemetry-service-name",
        "telemetry-prometheus-enabled", "telemetry-prometheus-bind", "command-admins");

    static VelocityConfig load(Path file) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return parse(properties,file.toAbsolutePath().getParent());
    }

    static VelocityConfig parse(Properties properties, Path directory) {
        for (String key : properties.stringPropertyNames()) {
            if (!KEYS.contains(key) && !key.startsWith("message.")) {
                throw new IllegalArgumentException("Unknown Velocity setting: " + key);
            }
        }
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
            positive(properties,"failure-minimum-samples",20),
            positive(properties,"failure-ratio-low-percent",50),
            positive(properties,"failure-ratio-medium-percent",75),
            positive(properties,"pending-sessions-medium",100),
            positive(properties,"pending-sessions-strong",500),
            positiveLong(properties,"outbound-packets-low-per-second",10_000),
            positiveLong(properties,"outbound-packets-medium-per-second",25_000),
            positiveLong(properties,"outbound-packets-strong-per-second",50_000),
            positiveLong(properties,"outbound-bytes-low-per-second",1_000_000),
            positiveLong(properties,"outbound-bytes-medium-per-second",2_500_000),
            positiveLong(properties,"outbound-bytes-strong-per-second",5_000_000),
            bool(properties, "normal-captcha"),
            !required(properties, "fork-enabled").equalsIgnoreCase("false") && forkMode(properties),
            required(properties, "fork-enabled").equalsIgnoreCase("true"),
            bool(properties, "premium-lookup-enabled"),
            Duration.ofMillis(positive(properties, "premium-lookup-timeout-millis")),
            positive(properties, "premium-lookup-cache-capacity"),
            Duration.ofSeconds(positive(properties, "premium-lookup-cache-seconds")),
            positive(properties, "premium-lookup-concurrency"),
            bool(properties, "premium-trust-enabled", true),
            Duration.ofDays(bounded(properties, "premium-trust-ttl-days", 30, 3650)),
            bounded(properties, "premium-trust-capacity", 10000, 1_000_000),
            Duration.ofSeconds(bounded(properties, "premium-trust-snapshot-max-age-seconds", 120, 3600)),
            Duration.ofSeconds(bounded(properties, "two-factor-management-timeout-seconds", 300, 3600)),
            aliases(properties.getProperty("two-factor-command-aliases", "2fa")),
            positive(properties, "session-capacity"),
            positive(properties, "per-ip-limit"),
            Duration.ofSeconds(positive(properties, "per-ip-window-seconds")),
            profiles(properties),
            bool(properties, "telemetry-otel-enabled", false),
            otlpEndpoint(properties.getProperty("telemetry-otlp-endpoint", "http://127.0.0.1:4318/v1/traces").trim()),
            nonBlank(properties.getProperty("telemetry-service-name", "selene-velocity"), "telemetry-service-name"),
            bool(properties, "telemetry-prometheus-enabled", false),
            nonBlank(properties.getProperty("telemetry-prometheus-bind", "127.0.0.1:9466"), "telemetry-prometheus-bind"),
            names(properties.getProperty("command-admins", "")),
            messages);
    }

    private static Set<String> names(String value) {
        Set<String> names = new java.util.HashSet<>();
        for (String name : value.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("command-admins contains an invalid nickname: " + trimmed);
            names.add(trimmed.toLowerCase(java.util.Locale.ROOT));
        }
        return Set.copyOf(names);
    }

    private static String otlpEndpoint(String value) {
        if (!value.startsWith("http://") && !value.startsWith("https://"))
            throw new IllegalArgumentException("telemetry-otlp-endpoint must be an http:// or https:// OTLP traces URL");
        return value;
    }

    private static String nonBlank(String value, String key) {
        if (value.isBlank()) throw new IllegalArgumentException("Setting must not be empty: " + key);
        return value.trim();
    }

    String message(String key) {
        return messages.getOrDefault(key, key);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing setting: " + key);
        return value.trim();
    }

    /** fork-enabled: auto (use a compatible core when present), true (require it) or false. */
    private static boolean forkMode(Properties properties) {
        String value = required(properties, "fork-enabled");
        if (!value.equalsIgnoreCase("auto") && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Setting must be auto, true or false: fork-enabled");
        }
        return true;
    }

    private static boolean bool(Properties properties, String key) {
        String value = required(properties, key);
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Setting must be true or false: " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        return properties.containsKey(key) ? bool(properties, key) : fallback;
    }

    private static Map<AttackLevel, AttackProfile> profiles(Properties properties) {
        var result = new java.util.EnumMap<AttackLevel, AttackProfile>(AttackLevel.class);
        result.put(AttackLevel.NORMAL, profile(properties,"normal",bool(properties,"normal-captcha"),true,false,false,false,300));
        result.put(AttackLevel.LOW, profile(properties,"low",true,true,true,false,false,120));
        result.put(AttackLevel.MEDIUM, profile(properties,"medium",true,false,false,false,true,0));
        result.put(AttackLevel.STRONG, profile(properties,"strong",false,true,false,true,true,0));
        return Map.copyOf(result);
    }

    private static AttackProfile profile(Properties properties,String name,boolean captcha,boolean premium,
                                         boolean suspicious,boolean greenlist,boolean reconnect,int graceDefault) {
        String prefix="profiles."+name+".";
        return new AttackProfile(bool(properties,prefix+"captcha",captcha),bool(properties,prefix+"premium-bypass",premium),
            bool(properties,prefix+"suspicious-only",suspicious),bool(properties,prefix+"greenlist-only",greenlist),
            bool(properties,prefix+"reconnect-limit",reconnect),
            Duration.ofSeconds(nonNegative(properties,prefix+"password-grace-seconds",graceDefault,86400)),
            Duration.ofSeconds(nonNegative(properties,prefix+"captcha-grace-seconds",graceDefault,86400)),
            Duration.ofSeconds(nonNegative(properties,prefix+"totp-grace-seconds",graceDefault,86400)));
    }

    private static int positive(Properties properties, String key) {
        int value = Integer.parseInt(required(properties, key));
        if (value < 1) throw new IllegalArgumentException("Setting must be positive: " + key);
        return value;
    }
    private static int positive(Properties properties,String key,int fallback){return properties.containsKey(key)?positive(properties,key):fallback;}
    private static int bounded(Properties properties,String key,int fallback,int maximum) {
        int value=positive(properties,key,fallback);
        if(value>maximum)throw new IllegalArgumentException("Setting is too large: "+key);
        return value;
    }
    private static int nonNegative(Properties properties,String key,int fallback,int maximum){
        int value=properties.containsKey(key)?Integer.parseInt(required(properties,key)):fallback;
        if(value<0||value>maximum)throw new IllegalArgumentException("Setting must be between 0 and "+maximum+": "+key);
        return value;
    }
    private static String aliases(String value) {
        String normalized=java.util.Arrays.stream(value.split(",")).map(String::trim)
            .filter(alias->!alias.isEmpty()).distinct().collect(java.util.stream.Collectors.joining(","));
        if(normalized.isEmpty()||java.util.Arrays.stream(normalized.split(","))
            .anyMatch(alias->!alias.matches("[a-zA-Z0-9_-]{1,24}")))
            throw new IllegalArgumentException("Invalid two-factor-command-aliases");
        return normalized;
    }
    private static long positiveLong(Properties properties,String key,long fallback){
        if(!properties.containsKey(key))return fallback;long value=Long.parseLong(required(properties,key));
        if(value<1)throw new IllegalArgumentException("Setting must be positive: "+key);return value;
    }

    private static InetSocketAddress address(String value) {
        int delimiter = value.lastIndexOf(':');
        if (delimiter < 1) throw new IllegalArgumentException("Expected host:port: " + value);
        return new InetSocketAddress(value.substring(0, delimiter), Integer.parseInt(value.substring(delimiter + 1)));
    }
}
