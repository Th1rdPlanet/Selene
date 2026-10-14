package ru.moonlightproject.dev.selene.paper;

import java.util.Locale;
import java.util.Set;

/** Chooses CAPTCHA presentation without treating plugin presence as pack delivery proof. */
final class CaptchaPresentationPolicy {
    enum Action { WAIT, SHOW_DIALOG, SHOW_MAP, REJECT }

    private final String resourcePackMode;
    private final boolean dialogFont;
    private final boolean fallbackToMap;

    CaptchaPresentationPolicy(String resourcePackMode, boolean dialogFont, boolean fallbackToMap) {
        this.resourcePackMode = resourcePackMode.toUpperCase(Locale.ROOT);
        if (!Set.of("SERVER", "MANUAL").contains(this.resourcePackMode)) {
            throw new IllegalArgumentException("captcha.resource-pack-loading must be SERVER or MANUAL");
        }
        this.dialogFont = dialogFont;
        this.fallbackToMap = fallbackToMap;
    }

    Action action(Boolean packLoaded, boolean dialogSupported) {
        if (!dialogFont || !dialogSupported) return fallbackToMap ? Action.SHOW_MAP : Action.REJECT;
        if (resourcePackMode.equals("MANUAL")) return Action.SHOW_DIALOG;
        if (packLoaded == null) return Action.WAIT;
        if (packLoaded) return Action.SHOW_DIALOG;
        return fallbackToMap ? Action.SHOW_MAP : Action.REJECT;
    }
}
