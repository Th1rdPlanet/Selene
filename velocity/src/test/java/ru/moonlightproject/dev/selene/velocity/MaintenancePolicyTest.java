package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MaintenancePolicyTest {
    @Test void strictDefaultsKeepIdentityGateAndBypassFactors() {
        MaintenancePolicy policy = MaintenancePolicy.parse(Map.of(
            "require-trusted-ip", "true",
            "require-premium", "false",
            "enable-captcha", "true",
            "verify-captcha", "false",
            "enable-password", "true",
            "verify-password", "false",
            "enable-totp", "true",
            "verify-totp", "false",
            "elevate-selene-commands", "true"));
        assertTrue(policy.requireTrustedIp());
        assertFalse(policy.requirePremium());
        assertTrue(policy.enableCaptcha());
        assertFalse(policy.verifyCaptcha());
        assertTrue(policy.enablePassword());
        assertFalse(policy.verifyPassword());
        assertTrue(policy.enableTotp());
        assertFalse(policy.verifyTotp());
        assertTrue(policy.elevateSeleneCommands());
    }

    @Test void rejectsMissingUnknownAndNonBooleanSettings() {
        var valid = new java.util.LinkedHashMap<>(Map.of(
            "require-trusted-ip", "true", "require-premium", "false",
            "enable-captcha", "true",
            "verify-captcha", "false", "verify-password", "false",
            "enable-password", "true", "enable-totp", "true",
            "verify-totp", "false", "elevate-selene-commands", "true"));
        valid.remove("verify-password");
        assertThrows(IllegalArgumentException.class, () -> MaintenancePolicy.parse(valid));
        valid.put("verify-password", "sometimes");
        assertThrows(IllegalArgumentException.class, () -> MaintenancePolicy.parse(valid));
        valid.put("verify-password", "true");
        valid.put("whitelist-enabled", "false");
        assertThrows(IllegalArgumentException.class, () -> MaintenancePolicy.parse(valid));
    }

    @Test void verificationCannotBeEnabledForASkippedFactor() {
        var values = new java.util.LinkedHashMap<>(Map.ofEntries(
            Map.entry("require-trusted-ip", "true"), Map.entry("require-premium", "false"),
            Map.entry("enable-captcha", "false"), Map.entry("verify-captcha", "true"),
            Map.entry("enable-password", "true"), Map.entry("verify-password", "false"),
            Map.entry("enable-totp", "true"), Map.entry("verify-totp", "false"),
            Map.entry("elevate-selene-commands", "true")));
        assertThrows(IllegalArgumentException.class, () -> MaintenancePolicy.parse(values));
    }
}
