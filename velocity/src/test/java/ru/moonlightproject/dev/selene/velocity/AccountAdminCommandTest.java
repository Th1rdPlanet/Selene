package ru.moonlightproject.dev.selene.velocity;

import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.AdminCommandParser;

import static org.junit.jupiter.api.Assertions.*;

class AccountAdminCommandTest {
 @Test void mapsEveryBackendStatusToConfiguredOutput(){
  for(String status:new String[]{"OK","NOT_FOUND","INVALID_REQUEST","BUSY","UNAVAILABLE"})assertEquals("admin-status-"+status.toLowerCase().replace('_','-'),AdminCommandParser.messageKey(status));
  assertEquals("admin-status-unavailable",AdminCommandParser.messageKey("UNKNOWN"));
 }
 @Test void parsesAllOperations(){
  assertEquals("info",AdminCommandParser.parse(new String[]{"account","info","Alice"}).request().op());
  assertEquals("delete",AdminCommandParser.parse(new String[]{"account","delete","Alice"}).request().op());
  assertEquals("resetPassword",AdminCommandParser.parse(new String[]{"account","reset-password","Alice"}).request().op());
 }
}
