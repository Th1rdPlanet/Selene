package ru.moonlightproject.dev.selene.core;
import java.util.List;
public final class StartupBanner {
    private StartupBanner() {}
    public static List<String> lines(String version,String platform) {
        return List.of("", "    .-''-.    Selene " + version,
            "   / .--'     " + platform + " • MoonLightBridge",
            "   \\ '--.     ~VicTim~",
            "    '-..-'    dev.moonlightproject.ru", "");
    }
}
