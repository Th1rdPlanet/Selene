package ru.moonlightproject.dev.selene.paper;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommandUnsigned;
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
  if(!PacketPolicy.allowInbound(t.name())){e.setCancelled(true);return;}
  if(t==PacketType.Play.Client.CHAT_COMMAND&&!PacketPolicy.allowCommand(new WrapperPlayClientChatCommand(e).getCommand()))e.setCancelled(true);
  if(t==PacketType.Play.Client.CHAT_COMMAND_UNSIGNED&&!PacketPolicy.allowCommand(new WrapperPlayClientChatCommandUnsigned(e).getCommand()))e.setCancelled(true);
 }
 @Override public void onUserDisconnect(UserDisconnectEvent e){gate.close(e.getUser());}
}
