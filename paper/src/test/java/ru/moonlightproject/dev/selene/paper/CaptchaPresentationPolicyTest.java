package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaptchaPresentationPolicyTest {
    @Test
    void manuallyInstalledPackShowsConfiguredDialogWithoutServerStatus() {
        var policy = new CaptchaPresentationPolicy("MANUAL", true, true);

        assertEquals(CaptchaPresentationPolicy.Action.SHOW_DIALOG, policy.action(null, true));
    }

    @Test
    void managedPackWaitsForSuccessAndFallsBackAfterFailure() {
        var policy = new CaptchaPresentationPolicy("SERVER", true, true);

        assertEquals(CaptchaPresentationPolicy.Action.WAIT, policy.action(null, true));
        assertEquals(CaptchaPresentationPolicy.Action.SHOW_DIALOG, policy.action(true, true));
        assertEquals(CaptchaPresentationPolicy.Action.SHOW_MAP, policy.action(false, true));
    }

    @Test
    void missingDialogSupportUsesMapEvenWithManualPack() {
        var policy = new CaptchaPresentationPolicy("MANUAL", true, true);

        assertEquals(CaptchaPresentationPolicy.Action.SHOW_MAP, policy.action(null, false));
    }

    @Test
    void disabledFallbackRejectsWhenPackFailed() {
        var policy = new CaptchaPresentationPolicy("SERVER", true, false);

        assertEquals(CaptchaPresentationPolicy.Action.REJECT, policy.action(false, true));
    }
}
