package ru.moonlightproject.dev.selene.paper;
import java.util.*;
import java.util.function.*;
import ru.moonlightproject.dev.selene.core.*;
final class MaintenanceNode{
 private final String nodeId;private final Predicate<byte[]> sender;private final Consumer<String> logger;
 private String fingerprint="";private long snapshotRevision;private long revision=-1;private boolean enabled;private MaintenanceMessage.Policy policy;
 MaintenanceNode(String nodeId,Predicate<byte[]> sender,Consumer<String> logger){
  if(nodeId==null||!nodeId.matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Invalid node-id");
  this.nodeId=nodeId;this.sender=Objects.requireNonNull(sender);this.logger=Objects.requireNonNull(logger);
 }
 synchronized boolean publish(List<MaintenanceMessage.Entry> entries){String next=WhitelistSnapshot.fingerprint(entries);if(next.equals(fingerprint))return false;
  byte[] message=MaintenanceCodec.encode(new MaintenanceMessage.Whitelist(nodeId,snapshotRevision+1,entries));if(!sender.test(message))return false;
  fingerprint=next;snapshotRevision++;if(enabled)logger.accept("maintenance whitelist node="+nodeId+" revision="+snapshotRevision+" entries="+entries.size());return true;}
 synchronized boolean accept(MaintenanceMessage.State state){if(state.revision()<=revision)return false;revision=state.revision();enabled=state.enabled();policy=state.policy();
  logger.accept("maintenance state node="+nodeId+" enabled="+enabled+" revision="+revision+" actor="+state.actor()+" reason="+state.reason());
  sender.test(MaintenanceCodec.encode(new MaintenanceMessage.Ack(nodeId,revision)));return true;}
 synchronized boolean debugLogging(){return enabled;}
 synchronized long revision(){return revision;}
 synchronized MaintenanceMessage.Policy policy(){return policy;}
}
