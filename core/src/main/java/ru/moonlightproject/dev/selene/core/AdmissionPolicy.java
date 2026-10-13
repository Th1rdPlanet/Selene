package ru.moonlightproject.dev.selene.core;
import java.time.Duration;
import java.util.*;
public final class AdmissionPolicy {
  private final int capacity, limit;
  private final long windowMillis;
  private final Map<String, Window> windows = new HashMap<>();
  private volatile Map<String, String> green = Map.of();
  private record Window(long since, int count) {}
  public AdmissionPolicy(int capacity, int limit, Duration window) {
    if (capacity < 1 || limit < 1 || window.isNegative() || window.isZero())
      throw new IllegalArgumentException("Invalid admission bounds");
    this.capacity = capacity;
    this.limit = limit;
    windowMillis = window.toMillis();
  }
  public void replaceGreen(Map<String, String> entries) {
    if (entries.size() > capacity)
      throw new IllegalArgumentException("Green capacity exceeded");
    var copy = new HashMap<String, String>();
    entries.forEach((n, ip) -> copy.put(n.toLowerCase(Locale.ROOT), ip));
    green = Map.copyOf(copy);
  }
  public boolean isGreen(String username, String ip) {
    return ip.equals(green.get(username.toLowerCase(Locale.ROOT)));
  }
  public boolean captcha(AttackLevel level, boolean normalCaptcha, boolean suspicious) {
    return switch (level) {
      case NORMAL -> normalCaptcha;
      case LOW -> suspicious;
      case MEDIUM -> true;
      case STRONG -> false;
    };
  }
  public synchronized boolean admit(AttackLevel level, String ip, long nowMillis) {
    if (level == AttackLevel.NORMAL || level == AttackLevel.LOW)
      return true;
    windows.entrySet().removeIf(e -> nowMillis - e.getValue().since >= windowMillis);
    Window old = windows.get(ip);
    if (old == null) {
      if (windows.size() >= capacity)
        return false;
      windows.put(ip, new Window(nowMillis, 1));
      return true;
    }
    if (old.count >= limit)
      return false;
    windows.put(ip, new Window(old.since, old.count + 1));
    return true;
  }
}
