package ru.moonlightproject.dev.selene.core;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.Test;
class SettingsTest {
  @Test
  void failsClosedOnMalformedConfiguration() {
    var p = new Properties();
    p.setProperty("enabled", "tru");
    p.setProperty("port", "99999");
    var s = new Settings(p);
    assertThrows(IllegalArgumentException.class, () -> s.bool("enabled", false));
    assertThrows(IllegalArgumentException.class, () -> s.integer("port", 1, 1, 65535));
    assertThrows(IllegalArgumentException.class, () -> s.required("secret"));
  }
  @Test
  void messageReplacementIsLiteral() {
    var p = new Properties();
    p.setProperty("hello", "Hello {name}");
    assertEquals("Hello $1", new Settings(p).message("hello", java.util.Map.of("name", "$1")));
  }
}
