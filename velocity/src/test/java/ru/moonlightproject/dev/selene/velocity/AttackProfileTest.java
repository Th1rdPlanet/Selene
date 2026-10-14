package ru.moonlightproject.dev.selene.velocity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AttackProfileTest {
 @Test void premiumBypassAppliesOnlyWhenConfiguredAndDoesNotAffectOfflinePlayers(){
  var low=new AttackProfile(true,true,true,false,false);
  assertTrue(low.captcha(false,true));
  assertFalse(low.captcha(true,true));
  assertFalse(low.captcha(false,false));
  var medium=new AttackProfile(true,false,false,false,true);
  assertTrue(medium.captcha(true,false));
 }
}
