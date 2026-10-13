package ru.moonlightproject.dev.selene.paper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PacketPolicyTest {
 @Test void unknownOrInteractionPacketsFailClosed(){for(String packet:new String[]{"NEW_PACKET","PLUGIN_MESSAGE","QUERY_ENTITY_NBT","CREATIVE_INVENTORY_ACTION","USE_ITEM","CLICK_WINDOW","CHAT_MESSAGE","PLAYER_POSITION"})assertFalse(PacketPolicy.allowInbound(packet),packet);}
 @Test void authenticationAndLivenessRemainUsable(){for(String packet:new String[]{"KEEP_ALIVE","PONG","CHAT_COMMAND","CHAT_COMMAND_UNSIGNED","TELEPORT_CONFIRM"})assertTrue(PacketPolicy.allowInbound(packet),packet);}
 @Test void everyInventoryCarrierIsSuppressed(){for(String packet:new String[]{"WINDOW_ITEMS","SET_SLOT","SET_CURSOR_ITEM","SET_PLAYER_INVENTORY","ENTITY_EQUIPMENT","ENTITY_METADATA"})assertTrue(PacketPolicy.sensitiveOutbound(packet),packet);assertFalse(PacketPolicy.sensitiveOutbound("MAP_DATA"));}
 @Test void onlyNamespacedAuthenticationCommandsReachBukkitWhileLocked(){assertTrue(PacketPolicy.allowCommand("selene:login secret"));assertTrue(PacketPolicy.allowCommand("SeLeNe:captcha 1234"));assertFalse(PacketPolicy.allowCommand("login secret"));assertFalse(PacketPolicy.allowCommand("spawn"));assertFalse(PacketPolicy.allowCommand("minecraft:help"));}
}
