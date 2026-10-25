package ru.moonlightproject.dev.selene.paper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import ru.moonlightproject.dev.selene.core.MaintenanceMessage;
final class WhitelistSnapshot{
 private WhitelistSnapshot(){}
 static String fingerprint(Collection<MaintenanceMessage.Entry> entries){
  try{MessageDigest digest=MessageDigest.getInstance("SHA-256");entries.stream()
   .sorted(Comparator.comparing(e->e.name().toLowerCase(Locale.ROOT)))
   .forEach(e->digest.update((e.name().toLowerCase(Locale.ROOT)+"\0"+e.uuid()+"\n").getBytes(StandardCharsets.UTF_8)));
   return java.util.HexFormat.of().formatHex(digest.digest());
  }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
}
