package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.MaintenanceMessage;

class WhitelistRegistryTest {
 private static MaintenanceMessage.Entry entry(String name){return new MaintenanceMessage.Entry(name,UUID.nameUUIDFromBytes(name.getBytes()));}
 @Test void unionsServerSnapshotsCaseInsensitivelyAndRemovesDisconnectedServers(){
  var registry=new WhitelistRegistry();
  assertTrue(registry.replace("lobby",1,List.of(entry("Alice"),entry("Shared"))));
  assertTrue(registry.replace("survival",1,List.of(entry("Bob"),entry("shared"))));
  assertEquals(3,registry.unionSize());
  assertTrue(registry.contains("ALICE"));assertTrue(registry.contains("shared"));
  registry.remove("lobby");
  assertFalse(registry.contains("alice"));assertTrue(registry.contains("SHARED"));
  assertEquals(2,registry.unionSize());
 }
 @Test void replacementIsAtomicAndOldRevisionsAreIgnored(){
  var registry=new WhitelistRegistry();
  assertTrue(registry.replace("lobby",4,List.of(entry("Alice"))));
  assertFalse(registry.replace("lobby",3,List.of(entry("Mallory"))));
  assertTrue(registry.contains("alice"));assertFalse(registry.contains("mallory"));
  assertTrue(registry.replace("lobby",5,List.of(entry("Bob"))));
  assertFalse(registry.contains("alice"));assertTrue(registry.contains("bob"));
 }
}
