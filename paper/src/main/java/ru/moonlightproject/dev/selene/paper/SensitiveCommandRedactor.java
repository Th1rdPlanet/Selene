package ru.moonlightproject.dev.selene.paper;

import java.util.Locale;

final class SensitiveCommandRedactor {
 private SensitiveCommandRedactor(){}
 static String redact(String input){
  String stripped=input.strip();String[] parts=stripped.split("\\s+",2);String root=parts[0].toLowerCase(Locale.ROOT);
  if(root.startsWith("/"))root=root.substring(1);
  if(java.util.Set.of("login","l","register","reg","selene:login","selene:l","selene:register","selene:reg","captcha","selene:captcha").contains(root))return parts[0]+(parts.length==2?" <redacted>":"");
  return input;
 }
}
