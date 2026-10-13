package ru.moonlightproject.dev.selene.core;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
class CoreTest {
  @Test
  void dtoAndCodecRoundTrip() {
    var r = new AuthRequest("login", "s", "Player", "127.0.0.1", "secret", "", false, true);
    assertEquals(r, JsonCodec.request(JsonCodec.encode(r)));
    assertFalse(r.toString().contains("secret"));
  }
  @Test
  void strictWireBounds() {
    assertThrows(IllegalArgumentException.class, () -> JsonCodec.request(new byte[262145]));
    assertThrows(IllegalArgumentException.class,
        () -> JsonCodec.request("{\"premium\":\"true\"}".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> JsonCodec.request("{} trailing".getBytes(StandardCharsets.UTF_8)));
  }
  @Test
  void unsafeEndpointsRejectedBeforeDial() {
    assertThrows(IllegalArgumentException.class,
        () -> BackendGateway.connect("tcp://example.com:1234", null, true, Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class,
        () -> BackendGateway.connect("tcp://127.0.0.1:1234", null, false, Duration.ofSeconds(1)));
  }
  @Test
  void admissionUsesExactPairAndBoundedTracking() {
    var p = new AdmissionPolicy(2, 2, Duration.ofSeconds(10));
    p.replaceGreen(java.util.Map.of("alice", "127.0.0.1"));
    assertTrue(p.isGreen("Alice", "127.0.0.1"));
    assertFalse(p.isGreen("Alice", "127.0.0.2"));
    assertFalse(p.captcha(AttackLevel.STRONG, false, false));
    assertTrue(p.captcha(AttackLevel.MEDIUM, false, false));
    assertTrue(p.admit(AttackLevel.NORMAL, "127.0.0.1", 0));
    assertTrue(p.admit(AttackLevel.MEDIUM, "127.0.0.1", 0));
    assertTrue(p.admit(AttackLevel.MEDIUM, "127.0.0.1", 0));
    assertFalse(p.admit(AttackLevel.MEDIUM, "127.0.0.1", 0));
    assertTrue(p.admit(AttackLevel.MEDIUM, "127.0.0.1", 11000));
  }
}
