package ru.moonlightproject.dev.selene.paper;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
/** Runs before a Bukkit player exists: unknown channels are always restricted. */
final class InventoryFirewall extends PacketListenerAbstract {
 final ConnectionGate<User> gate=new ConnectionGate<>();
 InventoryFirewall(){super(PacketListenerPriority.HIGHEST);}
 @Override public void onPacketSend(PacketSendEvent e){
  if(gate.authenticated(e.getUser()))return;
  if(e.getPacketType() instanceof PacketType.Play.Server t && PacketPolicy.sensitiveOutbound(t.name()))e.setCancelled(true);
 }
 @Override public void onPacketReceive(PacketReceiveEvent e){
  if(gate.authenticated(e.getUser()) || !(e.getPacketType() instanceof PacketType.Play.Client t))return;
  if(!PacketPolicy.allowInbound(t.name()))e.setCancelled(true);
 }
 @Override public void onUserDisconnect(UserDisconnectEvent e){gate.close(e.getUser());}
}
