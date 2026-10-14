package ru.moonlightproject.dev.selene.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LanguageCatalogTest {
    @TempDir Path directory;
    @Test void resolvesExactThenBaseThenServerThenBuiltInPerKey() throws Exception {
        Files.writeString(directory.resolve("en_us.json"), "{\"welcome\":\"Howdy\"}");
        Files.writeString(directory.resolve("en.json"), "{\"help\":\"Help\"}");
        Files.writeString(directory.resolve("ru.json"), "{\"error\":\"Ошибка\"}");
        var catalog = LanguageCatalog.load(directory, "ru", true, Map.of("last", "Fallback"));
        assertEquals("Howdy", catalog.text("en-US", "welcome"));
        assertEquals("Help", catalog.text("en_US", "help"));
        assertEquals("Ошибка", catalog.text("en_US", "error"));
        assertEquals("Fallback", catalog.text("en_US", "last"));
    }
    @Test void disabledDetectionAndUntrustedLocaleUseServerLanguage() throws Exception {
        Files.writeString(directory.resolve("ru.json"), "{\"hello\":\"Привет\"}");
        Files.writeString(directory.resolve("en.json"), "{\"hello\":\"Hello\"}");
        assertEquals("Привет", LanguageCatalog.load(directory,"ru",false,Map.of()).text("en", "hello"));
        assertEquals("Привет", LanguageCatalog.load(directory,"ru",true,Map.of()).text("../../en", "hello"));
    }
    @Test void rejectsNonTextAndMalformedFilesInsteadOfSilentlyDiscardingChanges() throws Exception {
        Files.writeString(directory.resolve("ru.json"), "{\"hello\":42}");
        assertThrows(IllegalArgumentException.class, () -> LanguageCatalog.load(directory,"ru",true,Map.of()));
    }
}
