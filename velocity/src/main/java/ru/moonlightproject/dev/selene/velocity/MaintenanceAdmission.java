package ru.moonlightproject.dev.selene.velocity;

/** Fail-closed admission rules that are evaluated before a maintenance session is created. */
final class MaintenanceAdmission {
    enum Reason { ALLOWED, NOT_WHITELISTED, UNKNOWN_ACCOUNT, UNTRUSTED_IP, PREMIUM_REQUIRED }

    record Decision(boolean allowed, Reason reason) {}

    private MaintenanceAdmission() {}

    static Decision decide(
        MaintenancePolicy policy,
        boolean whitelisted,
        boolean knownAccount,
        boolean trustedIp,
        boolean premiumAccount
    ) {
        if (!whitelisted) return denied(Reason.NOT_WHITELISTED);
        if (!knownAccount) return denied(Reason.UNKNOWN_ACCOUNT);
        if (policy.requireTrustedIp() && !trustedIp) return denied(Reason.UNTRUSTED_IP);
        if (policy.requirePremium() && !premiumAccount) return denied(Reason.PREMIUM_REQUIRED);
        return new Decision(true, Reason.ALLOWED);
    }

    private static Decision denied(Reason reason) {
        return new Decision(false, reason);
    }
}
