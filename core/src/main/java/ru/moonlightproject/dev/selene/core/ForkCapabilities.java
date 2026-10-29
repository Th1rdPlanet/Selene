package ru.moonlightproject.dev.selene.core;
/**
 * Optional proxy-fork extension loaded through ServiceLoader. Every method must be local and
 * nonblocking.
 */
public interface ForkCapabilities {
  default boolean supportsEarlyAdmission() {
    return false;
  }
  default boolean suspicious(String username, String ip) {
    return false;
  }
  default AttackLevel attackLevel() {
    return AttackLevel.NORMAL;
  }
  /** Current one-second outbound traffic rate. Must return immediately and never inspect payloads. */
  default ProtectionSignals protectionSignals() {
    return ProtectionSignals.UNAVAILABLE;
  }
  /**
   * Result of an authentication step reported by the backend (AUTHENTICATED, CAPTCHA_INVALID,
   * INVALID_CREDENTIALS, RATE_LIMITED). Lets the core learn trusted addresses and penalize
   * failures. Must return immediately.
   */
  default void onAuthentication(String username, java.net.InetAddress address, String status) {
  }
  /** Short provider name for logs. */
  default String name() {
    return "none";
  }
}
