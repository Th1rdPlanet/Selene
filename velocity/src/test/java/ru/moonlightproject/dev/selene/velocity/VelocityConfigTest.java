package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class VelocityConfigTest {
    @Test
    void rejectsServerOnlyKeysInProxyConfiguration() throws Exception {
        var file = Files.createTempFile("selene-velocity", ".properties");
        Files.writeString(file, valid() + "auth-world=world\n");

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class, () -> VelocityConfig.load(file));
        assertTrue(error.getMessage().contains("auth-world"));
    }

    @Test
    void loadsProxyRoutingAndMessages() throws Exception {
        var file = Files.createTempFile("selene-velocity", ".properties");
        Files.writeString(file, valid());

        VelocityConfig config = VelocityConfig.load(file);

        assertEquals("auth", config.authServer());
        assertEquals("lobby", config.defaultServer());
        assertEquals("Access denied", config.message("denied"));
        assertEquals(4096, config.sessionCapacity());
        assertFalse(config.forkEnabled());
        assertTrue(config.premiumLookupEnabled());
        assertEquals(8, config.premiumLookupConcurrency());
    }

    private static String valid() {
        return """
            backend-endpoint=tcp://127.0.0.1:38191
            backend-allow-loopback-plaintext=true
            tls-keystore=proxy.p12
            tls-keystore-password-env=SELENE_PROXY_KEYSTORE_PASSWORD
            tls-truststore=trust.p12
            tls-truststore-password-env=SELENE_PROXY_TRUSTSTORE_PASSWORD
            relay-bind=127.0.0.1:38291
            auth-server=auth
            auth-server-certificate-sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
            default-server=lobby
            restore-last-server=true
            attack-level=NORMAL
            attack-auto=true
            attack-window-seconds=10
            attack-recovery-seconds=30
            attack-low-connections=30
            attack-medium-connections=60
            attack-strong-connections=100
            suspicious-ip-connections=8
            suspicious-ip-usernames=4
            normal-captcha=false
            fork-enabled=false
            medium-before-login=false
            premium-lookup-enabled=true
            premium-lookup-timeout-millis=2000
            premium-lookup-cache-capacity=10000
            premium-lookup-cache-seconds=3600
            premium-lookup-concurrency=8
            session-capacity=4096
            per-ip-limit=3
            per-ip-window-seconds=10
            message.denied=Access denied
            message.busy=Authentication busy
            message.strong=Only trusted addresses are admitted
            message.backend-unavailable=Authentication unavailable
            message.auth-required=Authenticate first
            message.premium-lookup-unavailable=Premium verification unavailable
            message.session-channel-unavailable=Authentication channel unavailable
            """;
    }
}
