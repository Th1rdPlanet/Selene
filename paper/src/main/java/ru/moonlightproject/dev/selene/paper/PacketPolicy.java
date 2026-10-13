package ru.moonlightproject.dev.selene.paper;
final class PacketPolicy {
 private PacketPolicy(){}
 static boolean sensitiveOutbound(String packet){return switch(packet){case "WINDOW_ITEMS","SET_SLOT","SET_CURSOR_ITEM","SET_PLAYER_INVENTORY","ENTITY_EQUIPMENT","ENTITY_METADATA" -> true;default -> false;};}
 static boolean allowInbound(String packet){return switch(packet){case "KEEP_ALIVE","PONG","TELEPORT_CONFIRM","CLIENT_SETTINGS","CHAT_COMMAND","CHAT_COMMAND_UNSIGNED","CHAT_ACK","CHAT_SESSION_UPDATE","CHUNK_BATCH_ACK","CONFIGURATION_ACK","RESOURCE_PACK_STATUS","CLIENT_TICK_END","PLAYER_LOADED","PLAYER_ROTATION","PLAYER_FLYING" -> true;default -> false;};}
 static boolean allowCommand(String command){
  String root=command.stripLeading().split(" ",2)[0].toLowerCase(java.util.Locale.ROOT);
  return java.util.Set.of("selene:login","selene:register","selene:captcha").contains(root);
 }
}
