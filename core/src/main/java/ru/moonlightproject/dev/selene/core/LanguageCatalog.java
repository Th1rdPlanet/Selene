package ru.moonlightproject.dev.selene.core;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable catalog. Client locales select preloaded entries, never filesystem paths. */
public final class LanguageCatalog {
    private final String defaultLocale;
    private final boolean detect;
    private final Map<String, Map<String, String>> resolved;

    private LanguageCatalog(String defaultLocale, boolean detect, Map<String, Map<String, String>> languages,
                            Map<String, String> builtIn) {
        this.defaultLocale = normalize(defaultLocale);
        if (this.defaultLocale.isEmpty()) throw new IllegalArgumentException("Invalid default language: " + defaultLocale);
        this.detect = detect;
        Map<String, Map<String, String>> result = new HashMap<>();
        Set<String> locales = new HashSet<>(languages.keySet());
        locales.add(this.defaultLocale);
        for (String locale : locales) {
            Map<String, String> values = new HashMap<>(builtIn);
            values.putAll(languages.getOrDefault(base(this.defaultLocale), Map.of()));
            values.putAll(languages.getOrDefault(this.defaultLocale, Map.of()));
            values.putAll(languages.getOrDefault(base(locale), Map.of()));
            values.putAll(languages.getOrDefault(locale, Map.of()));
            result.put(locale, Map.copyOf(values));
        }
        resolved = Map.copyOf(result);
    }

    public static LanguageCatalog load(Path directory, String defaultLocale, boolean detect,
                                       Map<String, String> builtIn) throws IOException {
        Map<String, Map<String, String>> languages = new HashMap<>();
        if (Files.isDirectory(directory)) {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    String name = path.getFileName().toString();
                    String locale = normalize(name.substring(0, name.length() - 5));
                    if (locale.isEmpty()) throw new IllegalArgumentException("Invalid language filename: " + name);
                    if (languages.putIfAbsent(locale, read(path)) != null)
                        throw new IllegalArgumentException("Duplicate language: " + locale);
                }
            }
        }
        return new LanguageCatalog(defaultLocale, detect, languages, builtIn);
    }

    public static Map<String, String> read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) throw new IllegalArgumentException("Language must be a JSON object: " + path);
            Map<String, String> values = new LinkedHashMap<>();
            for (var entry : root.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Language value must be text: " + path + " / " + entry.getKey());
                values.put(entry.getKey(), entry.getValue().getAsString());
            }
            return Map.copyOf(values);
        } catch (JsonParseException error) {
            throw new IllegalArgumentException("Invalid JSON language file: " + path, error);
        }
    }

    public String localeKey(String clientLocale) {
        String locale = detect ? normalize(clientLocale) : defaultLocale;
        if (resolved.containsKey(locale)) return locale;
        if (resolved.containsKey(base(locale))) return base(locale);
        return defaultLocale;
    }
    public Map<String, String> templates(String locale) { return resolved.get(localeKey(locale)); }
    public Set<String> locales() { return resolved.keySet(); }
    public String defaultLocale() { return defaultLocale; }
    public String text(String locale, String key) {
        String value = templates(locale).get(key);
        if (value == null) throw new IllegalArgumentException("Missing message template: " + key);
        return value;
    }
    private static String base(String locale) { return locale.split("_", 2)[0]; }
    private static String normalize(String locale) {
        if (locale == null) return "";
        String value = locale.toLowerCase(Locale.ROOT).replace('-', '_');
        return value.matches("[a-z]{2,3}(?:_[a-z0-9]{2,8}){0,2}") ? value : "";
    }
}
