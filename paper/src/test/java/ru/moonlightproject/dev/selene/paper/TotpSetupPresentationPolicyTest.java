package ru.moonlightproject.dev.selene.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TotpSetupPresentationPolicyTest {
    @Test
    void serverDeliveredPackWaitsUntilMinecraftReportsItLoaded() {
        var policy = new TotpSetupPresentationPolicy(true, "SERVER", 8);

        assertEquals(TotpSetupPresentationPolicy.Action.WAIT, policy.action(null, 0));
        assertEquals(TotpSetupPresentationPolicy.Action.SHOW_QR, policy.action(true, 1));
    }

    @Test
    void failedOrTimedOutPackStillShowsTheManualSecret() {
        var policy = new TotpSetupPresentationPolicy(true, "SERVER", 8);

        assertEquals(TotpSetupPresentationPolicy.Action.SHOW_MANUAL, policy.action(false, 1));
        assertEquals(TotpSetupPresentationPolicy.Action.SHOW_MANUAL, policy.action(null, 8));
    }

    @Test
    void ManualPackModeCanRenderQrImmediately() {
        assertEquals(TotpSetupPresentationPolicy.Action.SHOW_QR,
            new TotpSetupPresentationPolicy(true, "MANUAL", 8).action(null, 0));
        assertEquals(TotpSetupPresentationPolicy.Action.SHOW_MANUAL,
            new TotpSetupPresentationPolicy(false, "SERVER", 8).action(true, 0));
    }
}
