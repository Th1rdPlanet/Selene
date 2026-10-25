package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class MaintenanceAdmissionTest {
    private static MaintenancePolicy policy(boolean trustedIp, boolean premium) {
        return new MaintenancePolicy(trustedIp, premium, true, false, true, false, true, false, true);
    }

    @Test void whitelistAndKnownAccountAreAlwaysRequired() {
        assertEquals(MaintenanceAdmission.Reason.NOT_WHITELISTED,
            MaintenanceAdmission.decide(policy(false, false), false, true, true, true).reason());
        assertEquals(MaintenanceAdmission.Reason.UNKNOWN_ACCOUNT,
            MaintenanceAdmission.decide(policy(false, false), true, false, true, true).reason());
    }

    @Test void optionalTrustedIpAndPremiumChecksAreIndependent() {
        assertEquals(MaintenanceAdmission.Reason.UNTRUSTED_IP,
            MaintenanceAdmission.decide(policy(true, false), true, true, false, false).reason());
        assertEquals(MaintenanceAdmission.Reason.PREMIUM_REQUIRED,
            MaintenanceAdmission.decide(policy(false, true), true, true, false, false).reason());
        assertTrue(MaintenanceAdmission.decide(policy(false, false), true, true, false, false).allowed());
        assertTrue(MaintenanceAdmission.decide(policy(true, true), true, true, true, true).allowed());
    }
}
