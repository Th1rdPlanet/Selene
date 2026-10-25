package ru.moonlightproject.dev.selene.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaintenanceCodecTest {
 @Test void roundTripsStateSnapshotAndAcknowledgement(){
  var policy=new MaintenanceMessage.Policy(true,false,true,false,true,false,true,false,true);
  var state=new MaintenanceMessage.State(7,true,policy,"plugin","repair");
  assertEquals(state,MaintenanceCodec.decode(MaintenanceCodec.encode(state)));
  var snapshot=new MaintenanceMessage.Whitelist("lobby",4,List.of(
   new MaintenanceMessage.Entry("Alice",UUID.fromString("00000000-0000-0000-0000-000000000001")),
   new MaintenanceMessage.Entry("Bob_2",UUID.fromString("00000000-0000-0000-0000-000000000002"))));
  assertEquals(snapshot,MaintenanceCodec.decode(MaintenanceCodec.encode(snapshot)));
  var ack=new MaintenanceMessage.Ack("lobby",7);
  assertEquals(ack,MaintenanceCodec.decode(MaintenanceCodec.encode(ack)));
 }
 @Test void rejectsMalformedOversizedAndInvalidMessages(){
  assertThrows(IllegalArgumentException.class,()->MaintenanceCodec.decode(new byte[]{99,1,2}));
  assertThrows(IllegalArgumentException.class,()->new MaintenanceMessage.Entry("bad name",UUID.randomUUID()));
  assertThrows(IllegalArgumentException.class,()->new MaintenanceMessage.Whitelist("x".repeat(65),1,List.of()));
  assertThrows(IllegalArgumentException.class,()->MaintenanceCodec.decode(new byte[1_048_577]));
 }
}
