package ru.moonlightproject.dev.selene.velocity;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VelocityMessagesTest {
 @Test void validatesAndRendersLiteralPlaceholders(){
  VelocityMessages messages=new VelocityMessages(Map.of("welcome","<green>Hello <player>, retry in <seconds>s</green>"));
  Component result=messages.component("welcome",VelocityMessages.player("<click:run_command:'/op'>VicTim"),VelocityMessages.seconds(5));
  assertFalse(hasClickEvent(result));
  assertTrue(result.toString().contains("VicTim"));
  assertTrue(result.toString().contains("5"));
 }

 @Test void reportsInvalidAndMissingKeysPrecisely(){
  IllegalArgumentException invalid=assertThrows(IllegalArgumentException.class,
   ()->new VelocityMessages(Map.of("broken","<red>broken")));
  assertTrue(invalid.getMessage().startsWith("Invalid MiniMessage at message.broken:"));
  VelocityMessages messages=new VelocityMessages(Map.of("ok","ok"));
  assertEquals("Missing message template: message.missing",
   assertThrows(IllegalArgumentException.class,()->messages.component("missing")).getMessage());
 }

 private static boolean hasClickEvent(Component component){return component.clickEvent()!=null||component.children().stream().anyMatch(VelocityMessagesTest::hasClickEvent);}
}
