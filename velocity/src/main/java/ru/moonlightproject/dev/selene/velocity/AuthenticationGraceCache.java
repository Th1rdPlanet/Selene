package ru.moonlightproject.dev.selene.velocity;
import java.net.InetAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
final class AuthenticationGraceCache {
 record Decision(boolean password,boolean captcha,boolean totp){static final Decision NONE=new Decision(false,false,false);}
 private record Key(String username,String ip){}
 private record Proof(long password,long captcha,long totp){}
 private final int capacity; private final LongSupplier clock;
 private final Map<Key,Proof> entries=new LinkedHashMap<>();
 AuthenticationGraceCache(int capacity){this(capacity,System::nanoTime);}
 AuthenticationGraceCache(int capacity,LongSupplier clock){if(capacity<1)throw new IllegalArgumentException("capacity must be positive");this.capacity=capacity;this.clock=clock;}
 synchronized Decision decision(String username,InetAddress ip,AttackProfile profile){
  Proof p=entries.get(key(username,ip));if(p==null)return Decision.NONE;long now=clock.getAsLong();
  return new Decision(fresh(p.password,profile.passwordGrace(),now),fresh(p.captcha,profile.captchaGrace(),now),fresh(p.totp,profile.totpGrace(),now));
 }
 synchronized void remember(String username,InetAddress ip,boolean password,boolean captcha,boolean totp){
  Key key=key(username,ip);Proof old=entries.getOrDefault(key,new Proof(Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE));long now=clock.getAsLong();
  entries.put(key,new Proof(password?now:old.password,captcha?now:old.captcha,totp?now:old.totp));
  while(entries.size()>capacity)entries.remove(entries.keySet().iterator().next());
 }
 synchronized void invalidate(String username,InetAddress ip){entries.remove(key(username,ip));}
 private static boolean fresh(long proof,Duration window,long now){return !window.isZero()&&!window.isNegative()&&proof!=Long.MIN_VALUE&&now-proof<window.toNanos();}
 private static Key key(String username,InetAddress ip){return new Key(username.toLowerCase(Locale.ROOT),ip.getHostAddress());}
}
