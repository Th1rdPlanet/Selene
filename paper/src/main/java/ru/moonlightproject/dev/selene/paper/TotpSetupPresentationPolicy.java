package ru.moonlightproject.dev.selene.paper;

import java.util.Locale;

record TotpSetupPresentationPolicy(boolean resourcePackConfigured, String loadingMode, int maxAttempts) {
    enum Action { WAIT, SHOW_QR, SHOW_MANUAL }

    TotpSetupPresentationPolicy {
        loadingMode = loadingMode == null ? "MANUAL" : loadingMode.toUpperCase(Locale.ROOT);
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
    }

    Action action(Boolean packLoaded, int attempt) {
        if (!resourcePackConfigured) return Action.SHOW_MANUAL;
        if (loadingMode.equals("MANUAL") || Boolean.TRUE.equals(packLoaded)) return Action.SHOW_QR;
        if (Boolean.FALSE.equals(packLoaded) || attempt >= maxAttempts) return Action.SHOW_MANUAL;
        return Action.WAIT;
    }
}
