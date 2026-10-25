package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.AdminCommandParser;

import static org.junit.jupiter.api.Assertions.*;

class AccountAdminCommandTest {
 @Test void onlyPermittedPlayersPassAndProxyPaperGivesVelocityGuidance(){
  assertEquals(AdminCommandParser.Access.PLAYER_DENIED,AdminCommandParser.access(false,false,true));
  assertEquals(AdminCommandParser.Access.ALLOWED,AdminCommandParser.access(false,true,true));
  assertEquals(AdminCommandParser.Access.PROXY_GUIDANCE,AdminCommandParser.access(true,false,false));
  assertEquals(AdminCommandParser.Access.PROXY_GUIDANCE,AdminCommandParser.access(false,true,false));
  assertEquals(AdminCommandParser.Access.ALLOWED,AdminCommandParser.access(true,false,true));
 }
 @Test void readingAndChangingAccountsNeedSeparatePermissions(){
  assertEquals("selene.command.account.view",AdminCommandParser.permission("list"));
  assertEquals("selene.command.account.view",AdminCommandParser.permission("info"));
  for(String op:new String[]{"delete","resetPassword","resetTotp"})assertEquals("selene.command.account.manage",AdminCommandParser.permission(op));
 }
 @Test void validatesSyntaxPagesAndUsernames(){
  assertEquals("list",AdminCommandParser.parse(new String[]{"account","list","2"}).request().op());
  assertEquals(2,AdminCommandParser.parse(new String[]{"account","list","2"}).request().page());
  assertEquals("syntax",AdminCommandParser.parse(new String[]{"account","list","0"}).error());
  assertEquals("username",AdminCommandParser.parse(new String[]{"account","info","a!"}).error());
  assertEquals("syntax",AdminCommandParser.parse(new String[]{"wat"}).error());
 }
 @Test void safeAuditContainsNoSecretsOrSessionIdentifiers(){
  String line=AdminCommandParser.audit("resetPassword","alice","OK",12);
  assertFalse(line.contains("sentinel-password"));assertFalse(line.matches(".*[0-9a-f]{8}-[0-9a-f-]{27,}.*"));assertTrue(line.contains("operation=resetPassword"));
 }
}
