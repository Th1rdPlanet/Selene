package ru.moonlightproject.dev.selene.core;

import java.util.ArrayList;
import java.util.List;

/** Console banner: a crescent moon tilted counter-clockwise (Selene is the moon goddess). */
public final class StartupBanner {
    // Generated from two offset circles; the lower horn reaches further right than the upper one.
    private static final String[] MOON = {
        "     ▄          ",
        "   ▄█▀          ",
        "  ███           ",
        " ████           ",
        " ████           ",
        " ████▄          ",
        "  █████▄        ",
        "   ▀█████▄▄     ",
        "     ▀▀██████▀▀ ",
    };

    private StartupBanner() {}

    public static List<String> lines(String version, String platform) {
        String[] text = {
            ConsoleStyle.DIM + "·      ✦" + ConsoleStyle.RESET,
            ConsoleStyle.BOLD + ConsoleStyle.WHITE + "S E L E N E" + ConsoleStyle.RESET + "  " + ConsoleStyle.GRAY + version + ConsoleStyle.RESET,
            ConsoleStyle.GRAY + "─────────────────────────────" + ConsoleStyle.RESET,
            ConsoleStyle.CYAN + platform + ConsoleStyle.GRAY + " • " + ConsoleStyle.CYAN + "MoonLightBridge" + ConsoleStyle.RESET,
            ConsoleStyle.WHITE + "authentication & access control" + ConsoleStyle.RESET,
            "",
            ConsoleStyle.MAGENTA + "~VicTim~" + ConsoleStyle.RESET,
            ConsoleStyle.GRAY + "dev.moonlightproject.ru" + ConsoleStyle.RESET,
            ConsoleStyle.DIM + "        ✦    ·" + ConsoleStyle.RESET,
        };
        List<String> out = new ArrayList<>();
        out.add("");
        for (int i = 0; i < MOON.length; i++) {
            out.add(ConsoleStyle.MOON + MOON[i] + ConsoleStyle.RESET + "  " + text[i]);
        }
        out.add("");
        return List.copyOf(out);
    }
}
