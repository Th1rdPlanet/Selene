package ru.moonlightproject.dev.selene.paper;

import java.util.Map;
import java.util.Set;

final class PaperConfigValidator {
 record Settings(boolean compatibilityPasswordArguments,boolean titleEnabled){}
 private static final Set<String> ROOTS=Set.of("role","packet-events-mode","stage","captcha-view","dialogs","secure-input","timeout-seconds","effects","visibility","limbo","presentation","resource-pack-provider","messages");
 private PaperConfigValidator(){}
 static Settings validate(Map<String,Object> values){
  for(String path:values.keySet()){
   if(path.equals("auth-location")||path.startsWith("auth-location."))throw new IllegalArgumentException("Legacy Paper setting "+path+" is no longer supported; replace it with limbo.* settings");
   String root=path.split("\\.",2)[0];if(!ROOTS.contains(root))throw new IllegalArgumentException("Unknown Paper setting: "+path);
  }
  requiredText(values,"role");requiredText(values,"packet-events-mode");requiredText(values,"stage");requiredText(values,"captcha-view");
  String mode=requiredText(values,"limbo.mode").toUpperCase(java.util.Locale.ROOT);if(!Set.of("MANAGED","EXISTING").contains(mode))throw new IllegalArgumentException("limbo.mode must be MANAGED or EXISTING");
  requiredText(values,"limbo.world");
  Object timeout=values.get("timeout-seconds");if(!(timeout instanceof Number number)||number.longValue()<10||number.longValue()>3600)throw new IllegalArgumentException("timeout-seconds must be between 10 and 3600");
  return new Settings(bool(values,"secure-input.compatibility-password-arguments",false),bool(values,"presentation.title-enabled",true));
 }
 private static String requiredText(Map<String,Object> values,String path){Object value=values.get(path);if(!(value instanceof String text)||text.isBlank())throw new IllegalArgumentException("Missing or empty Paper setting: "+path);return text.trim();}
 private static boolean bool(Map<String,Object> values,String path,boolean fallback){Object value=values.get(path);if(value==null)return fallback;if(!(value instanceof Boolean result))throw new IllegalArgumentException(path+" must be true or false");return result;}
}
