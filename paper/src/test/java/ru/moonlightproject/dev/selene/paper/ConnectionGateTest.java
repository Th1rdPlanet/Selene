package ru.moonlightproject.dev.selene.paper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConnectionGateTest {
 @Test void unknownConnectionsNeverReceiveInventory() { assertFalse(new ConnectionGate<Object>().authenticated(new Object())); }
 @Test void oldConnectionsCannotAuthenticateReplacements() {
  var g=new ConnectionGate<Object>(); var old=new Object(); var fresh=new Object();
  g.open(old); g.close(old); g.open(fresh); assertFalse(g.authenticate(old)); assertFalse(g.authenticated(fresh));
  assertTrue(g.authenticate(fresh)); assertTrue(g.authenticated(fresh)); g.close(fresh); assertFalse(g.authenticated(fresh));
 }
}
