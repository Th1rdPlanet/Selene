package ru.moonlightproject.dev.selene.velocity;
import java.time.Duration;

/** Effective behavior for one protection level. */
record AttackProfile(
    boolean captchaEnabled,
    boolean premiumBypass,
    boolean suspiciousOnly,
    boolean greenlistOnly,
    boolean reconnectLimit,
    Duration passwordGrace,
    Duration captchaGrace,
    Duration totpGrace
) {
    boolean captcha(boolean premium, boolean suspicious) {
        if (!captchaEnabled || (premium && premiumBypass)) return false;
        return !suspiciousOnly || suspicious;
    }
}
