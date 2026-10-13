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
}
