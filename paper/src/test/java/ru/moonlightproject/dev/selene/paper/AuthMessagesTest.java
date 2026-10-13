package ru.moonlightproject.dev.selene.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthMessagesTest {
 @Test void rendersColorsDecorationsAndCountdown(){
  AuthMessages messages=new AuthMessages(Map.of("messages.wait","<gold><bold>Wait <seconds>s</bold></gold>"));
  Component result=messages.component("messages.wait",AuthMessages.seconds(12));
  assertEquals(NamedTextColor.GOLD,result.color());
  assertEquals(TextDecoration.State.TRUE,result.decoration(TextDecoration.BOLD));
  assertTrue(result.toString().contains("12"));
 }

 @Test void rejectsInvalidTemplateWithExactPath(){
  IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
   ()->new AuthMessages(Map.of("messages.broken","<red>broken")));
  assertTrue(error.getMessage().startsWith("Invalid MiniMessage at messages.broken:"),error.getMessage());
 }

 @Test void rendersUserValuesAsLiteralText(){
  AuthMessages messages=new AuthMessages(Map.of("messages.hello","Hello <player>"));
  Component result=messages.component("messages.hello",AuthMessages.player("<click:run_command:'/op'>Bob"));
  assertFalse(hasClickEvent(result));
  assertTrue(result.toString().contains("<click:run_command"));
 }

 @Test void missingTemplateNeverFallsBackSilently(){
  AuthMessages messages=new AuthMessages(Map.of("messages.present","ok"));
  IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->messages.component("messages.missing"));
  assertEquals("Missing message template: messages.missing",error.getMessage());
 }

 private static boolean hasClickEvent(Component component){return component.clickEvent()!=null||component.children().stream().anyMatch(AuthMessagesTest::hasClickEvent);}
}
