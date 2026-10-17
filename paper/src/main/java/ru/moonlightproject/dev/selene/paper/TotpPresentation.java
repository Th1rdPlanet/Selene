package ru.moonlightproject.dev.selene.paper;

import java.util.List;

record TotpPresentation(String qrGlyphs, String manualSecret, List<String> recoveryCodes) {
    TotpPresentation {
        qrGlyphs = qrGlyphs == null ? "" : qrGlyphs;
        manualSecret = manualSecret == null ? "" : manualSecret;
        recoveryCodes = List.copyOf(recoveryCodes == null ? List.of() : recoveryCodes);
        if (manualSecret.length() > 128 || recoveryCodes.size() > 32) {
            throw new IllegalArgumentException("oversized TOTP presentation");
        }
    }

    static TotpPresentation setup(String uri, String secret, boolean resourcePackAvailable,
                                  QrOptions options) {
        if (secret == null || secret.isBlank() || secret.length() > 128) {
            throw new IllegalArgumentException("invalid TOTP secret presentation");
        }
        return new TotpPresentation(resourcePackAvailable ? QrGlyphEncoder.encode(uri, options) : "",
            secret, List.of());
    }

    static TotpPresentation recovery(List<String> codes) {
        if (codes == null || codes.isEmpty() || codes.size() > 32
            || codes.stream().anyMatch(code -> !AuthInputValidation.recovery(code).valid())) {
            throw new IllegalArgumentException("invalid recovery-code presentation");
        }
        return new TotpPresentation("", "", codes);
    }

    String recoveryText() {
        return String.join("\n", recoveryCodes);
    }

    @Override public String toString() {
        return "TotpPresentation[security=<redacted>, recoveryCount=" + recoveryCodes.size() + "]";
    }
}
