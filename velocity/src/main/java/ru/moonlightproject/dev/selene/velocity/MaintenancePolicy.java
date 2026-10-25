package ru.moonlightproject.dev.selene.velocity;

import java.util.Map;
import java.util.Set;

record MaintenancePolicy(
    boolean requireTrustedIp,
    boolean requirePremium,
    boolean enableCaptcha,
    boolean verifyCaptcha,
    boolean enablePassword,
    boolean verifyPassword,
    boolean enableTotp,
    boolean verifyTotp,
    boolean elevateSeleneCommands
) {
    private static final Set<String> KEYS = Set.of(
        "require-trusted-ip", "require-premium",
        "enable-captcha", "verify-captcha",
        "enable-password", "verify-password",
        "enable-totp", "verify-totp",
        "elevate-selene-commands");

    static MaintenancePolicy parse(Map<String,String> values) {
        if (!values.keySet().equals(KEYS)) {
            var missing = new java.util.TreeSet<>(KEYS); missing.removeAll(values.keySet());
            var unknown = new java.util.TreeSet<>(values.keySet()); unknown.removeAll(KEYS);
            throw new IllegalArgumentException("Invalid maintenance settings; missing=" + missing + " unknown=" + unknown);
        }
        MaintenancePolicy result = new MaintenancePolicy(
            bool(values,"require-trusted-ip"), bool(values,"require-premium"),
            bool(values,"enable-captcha"), bool(values,"verify-captcha"),
            bool(values,"enable-password"), bool(values,"verify-password"),
            bool(values,"enable-totp"), bool(values,"verify-totp"),
            bool(values,"elevate-selene-commands"));
        if (result.verifyCaptcha && !result.enableCaptcha
            || result.verifyPassword && !result.enablePassword
            || result.verifyTotp && !result.enableTotp) {
            throw new IllegalArgumentException("A maintenance factor cannot be verified when it is disabled");
        }
        return result;
    }

    private static boolean bool(Map<String,String> values,String key) {
        String value=values.get(key);
        if (!"true".equals(value) && !"false".equals(value))
            throw new IllegalArgumentException(key+" must be true or false");
        return Boolean.parseBoolean(value);
    }
}
