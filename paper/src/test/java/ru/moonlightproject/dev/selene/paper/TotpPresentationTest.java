package ru.moonlightproject.dev.selene.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TotpPresentationTest {
    @Test
    void setupUsesQrWithPackAndAlwaysKeepsManualFallback() {
        TotpPresentation setup = TotpPresentation.setup(
            "otpauth://totp/Selene%3AAlice?secret=JBSWY3DPEHPK3PXP&issuer=Selene",
            "JBSWY3DPEHPK3PXP", true, new QrOptions(4, 177));
        assertFalse(setup.qrGlyphs().isBlank());
        assertEquals("JBSWY3DPEHPK3PXP", setup.manualSecret());
        TotpPresentation fallback = TotpPresentation.setup("otpauth://totp/a?secret=A",
            "A", false, new QrOptions(4, 177));
        assertTrue(fallback.qrGlyphs().isEmpty());
        assertEquals("A", fallback.manualSecret());
        assertFalse(setup.toString().contains("JBSWY3DPEHPK3PXP"));
    }

    @Test
    void recoveryCodesAreBoundedAndFormattedForOneTimeDisplay() {
        TotpPresentation recovery = TotpPresentation.recovery(List.of(
            "23456-789AB-CDEFG-HJKLM", "NPQRS-TUVWX-YZ234-56789"));
        assertEquals(2, recovery.recoveryCodes().size());
        assertTrue(recovery.recoveryText().contains("23456-789AB-CDEFG-HJKLM"));
        assertThrows(IllegalArgumentException.class,
            () -> TotpPresentation.recovery(java.util.Collections.nCopies(33, "23456-789AB-CDEFG-HJKLM")));
    }
}
