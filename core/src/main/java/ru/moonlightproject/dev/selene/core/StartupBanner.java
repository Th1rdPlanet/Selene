package ru.moonlightproject.dev.selene.core;

import java.util.List;

/** Console banner: a crescent moon (Selene is the moon) with the build details beside it. */
public final class StartupBanner {
    private static final String[] MOON = {
        "       ▄▄██▀▀  ",
        "    ▄████▀      ",
        "   ████▀        ",
        "  ████          ",
        "  ████          ",
        "   ████▄        ",
        "    ▀████▄      ",
        "       ▀▀██▄▄   ",
    };

    private StartupBanner() {}

    public static List<String> lines(String version, String platform) {
        String[] text = {
            "Selene " + version,
            platform + " • MoonLightBridge",
            "",
            "authentication & access control",
            "",
            "~VicTim~",
            "",
            "dev.moonlightproject.ru",
        };
        String[] out = new String[MOON.length + 2];
        out[0] = "";
        for (int i = 0; i < MOON.length; i++) {
            out[i + 1] = MOON[i] + "  " + text[i];
        }
        out[out.length - 1] = "";
        return List.of(out);
    }
}
