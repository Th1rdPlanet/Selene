package ru.moonlightproject.dev.selene.paper;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.*;
class MaintenanceNodeTest{
 @Test void publishesChangedSnapshotsAndAcknowledgesOnlyNewState(){
  var sent=new ArrayList<byte[]>();var logs=new ArrayList<String>();var node=new MaintenanceNode("lobby",bytes->{sent.add(bytes);return true;},logs::add);
  var entries=List.of(new MaintenanceMessage.Entry("Alice",UUID.randomUUID()));
  assertTrue(node.publish(entries));assertFalse(node.publish(entries));
  assertInstanceOf(MaintenanceMessage.Whitelist.class,MaintenanceCodec.decode(sent.get(0)));
  var policy=new MaintenanceMessage.Policy(true,false,true,false,true,false,true,false,true);
  assertTrue(node.accept(new MaintenanceMessage.State(2,true,policy,"test","repair")));
  assertTrue(node.debugLogging());assertEquals(2,node.revision());
  assertInstanceOf(MaintenanceMessage.Ack.class,MaintenanceCodec.decode(sent.get(1)));
  assertFalse(node.accept(new MaintenanceMessage.State(1,false,policy,"old","old")));
  assertTrue(node.debugLogging());assertFalse(logs.isEmpty());
 }
 @Test void retriesSnapshotWhenNoPlayerCanCarryThePluginMessage(){
  var node=new MaintenanceNode("lobby",bytes->false,ignored->{});
  var entries=List.of(new MaintenanceMessage.Entry("Alice",UUID.randomUUID()));
  assertFalse(node.publish(entries));assertFalse(node.publish(entries));
 }
}
