package ru.moonlightproject.dev.selene.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Read-only view of a Selene account. Secrets (password hash, TOTP secret, recovery codes) are
 * never exposed; only the fact that TOTP is configured.
 *
 * @param name account name
 * @param premium whether the account is bound to a licensed (online-mode) profile
 * @param trustedIp green-listed IP, empty if none
 * @param trustedUntil expiry of the green-listed IP, or {@code null}
 * @param passwordResetPending whether the next login must set a new password
 * @param totpEnabled whether two-factor authentication is configured
 */
public record AccountView(
    String name,
    boolean premium,
    String trustedIp,
    Instant trustedUntil,
    boolean passwordResetPending,
    boolean totpEnabled
) {
    /** Returns the trusted IP if one is set and not expired. */
    public Optional<String> activeTrustedIp() {
        return trustedIp.isEmpty() || trustedUntil == null || trustedUntil.isBefore(Instant.now())
            ? Optional.empty() : Optional.of(trustedIp);
    }
}
