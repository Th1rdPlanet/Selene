package ru.moonlightproject.dev.selene.core;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
/**
 * UTF-8 properties with strict typed parsing. Role-specific required keys remain with each plugin.
 */
public final class Settings {
  private final Properties values = new Properties();
  public Settings(Properties values) {
    this.values.putAll(values);
  }
  public static Settings load(Path path) throws IOException {
    var p = new Properties();
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      p.load(reader);
    }
    return new Settings(p);
  }
  public String required(String key) {
    String v = values.getProperty(key);
    if (v == null || v.isBlank())
      throw new IllegalArgumentException("Required setting: " + key);
    return v;
  }
  public String string(String key, String fallback) {
    return values.getProperty(key, fallback);
  }
  public boolean bool(String key, boolean fallback) {
    String v = values.getProperty(key);
    if (v == null)
      return fallback;
    if (v.equalsIgnoreCase("true"))
      return true;
    if (v.equalsIgnoreCase("false"))
      return false;
    throw new IllegalArgumentException("Invalid boolean: " + key);
  }
  public int integer(String key, int fallback, int min, int max) {
    String v = values.getProperty(key);
    int n;
    try {
      n = v == null ? fallback : Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid integer: " + key);
    }
    if (n < min || n > max)
      throw new IllegalArgumentException("Out of range: " + key);
    return n;
  }
  public Duration duration(String key, int fallbackSeconds, int minSeconds, int maxSeconds) {
    return Duration.ofSeconds(integer(key, fallbackSeconds, minSeconds, maxSeconds));
  }
  public String message(String key, Map<String, String> replacements) {
    String text = required(key);
    for (var e : replacements.entrySet()) text = text.replace("{" + e.getKey() + "}", e.getValue());
    return text;
  }
}
