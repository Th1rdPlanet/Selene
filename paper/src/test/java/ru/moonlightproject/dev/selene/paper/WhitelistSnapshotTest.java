package ru.moonlightproject.dev.selene.paper;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.MaintenanceMessage;
class WhitelistSnapshotTest{
 @Test void fingerprintIsDeterministicAndChangesWithVanillaWhitelist(){
  var a=new MaintenanceMessage.Entry("Alice",UUID.fromString("00000000-0000-0000-0000-000000000001"));
  var b=new MaintenanceMessage.Entry("Bob",UUID.fromString("00000000-0000-0000-0000-000000000002"));
  assertEquals(WhitelistSnapshot.fingerprint(List.of(a,b)),WhitelistSnapshot.fingerprint(List.of(b,a)));
  assertNotEquals(WhitelistSnapshot.fingerprint(List.of(a)),WhitelistSnapshot.fingerprint(List.of(a,b)));
 }
}
