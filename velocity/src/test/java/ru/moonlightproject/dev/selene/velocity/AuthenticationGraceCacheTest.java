package ru.moonlightproject.dev.selene.velocity;
import static org.junit.jupiter.api.Assertions.*;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
class AuthenticationGraceCacheTest {
 @Test void remembersProofsOnlyForSameUsernameAndIp() throws Exception {
  AtomicLong clock=new AtomicLong();var cache=new AuthenticationGraceCache(8,clock::get);
  var ip=InetAddress.getByName("192.0.2.10");var profile=profile(300,120,60);
  cache.remember("Player_1",ip,true,true,true);
  assertEquals(new AuthenticationGraceCache.Decision(true,true,true),cache.decision("player_1",ip,profile));
  assertEquals(AuthenticationGraceCache.Decision.NONE,cache.decision("Other",ip,profile));
  assertEquals(AuthenticationGraceCache.Decision.NONE,cache.decision("Player_1",InetAddress.getByName("192.0.2.11"),profile));
 }
 @Test void currentProfileControlsIndependentExpiryAndZeroAlwaysRequiresProof() throws Exception {
  AtomicLong clock=new AtomicLong();var cache=new AuthenticationGraceCache(8,clock::get);var ip=InetAddress.getByName("192.0.2.10");
  cache.remember("Player_1",ip,true,true,true);clock.set(Duration.ofSeconds(90).toNanos());
  assertEquals(new AuthenticationGraceCache.Decision(true,false,false),cache.decision("Player_1",ip,profile(300,60,0)));
 }
 private static AttackProfile profile(long password,long captcha,long totp){return new AttackProfile(true,false,false,false,false,Duration.ofSeconds(password),Duration.ofSeconds(captcha),Duration.ofSeconds(totp));}
}
