package ru.moonlightproject.dev.selene.paper;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Objects;

final class PaperConfigValidator {
 record Settings(boolean compatibilityPasswordArguments,boolean titleEnabled){}
 private static final Set<String> ROOTS=Set.of("role","packet-events-mode","stage","captcha-view","captcha","dialogs","secure-input","timeout-seconds","effects","visibility","limbo","presentation","resource-pack-provider","messages","language","console");
 private PaperConfigValidator(){}
 static Settings validate(Map<String,Object> values){
  for(String path:values.keySet()){
   if(path.equals("auth-location")||path.startsWith("auth-location."))throw new IllegalArgumentException("Legacy Paper setting "+path+" is no longer supported; replace it with limbo.* settings");
   String root=path.split("\\.",2)[0];if(!ROOTS.contains(root))throw new IllegalArgumentException("Unknown Paper setting: "+path);
  }
  bool(values,"language.auto-detect",true);bool(values,"console.banner",true);
  requiredText(values,"role");requiredText(values,"packet-events-mode");requiredText(values,"stage");requiredText(values,"captcha-view");
  String mode=requiredText(values,"limbo.mode").toUpperCase(java.util.Locale.ROOT);if(!Set.of("MANAGED","EXISTING").contains(mode))throw new IllegalArgumentException("limbo.mode must be MANAGED or EXISTING");
  requiredText(values,"limbo.world");
  Object timeout=values.get("timeout-seconds");if(!(timeout instanceof Number number)||number.longValue()<10||number.longValue()>3600)throw new IllegalArgumentException("timeout-seconds must be between 10 and 3600");
  for(String legacy:List.of("presentation.title-enabled","presentation.actionbar-enabled","presentation.scoreboard-enabled","presentation.experience-bar","presentation.experience-level"))if(values.containsKey(legacy))throw new IllegalArgumentException("Legacy Paper setting "+legacy+" is no longer supported; copy the new presentation section from config.yml");
  for(String path:List.of("presentation.title.enabled","presentation.actionbar.enabled","presentation.scoreboard.enabled","presentation.experience.bar-enabled","presentation.experience.level-enabled"))bool(values,path,true);
  bounded(values,"presentation.title.fade-in-millis",0,60000,0);bounded(values,"presentation.title.stay-millis",50,60000,1200);bounded(values,"presentation.title.fade-out-millis",0,60000,200);
  bool(values,"dialogs.enabled",true);bool(values,"dialogs.fallback-to-chat",true);bool(values,"dialogs.can-close-with-escape",true);bool(values,"dialogs.pause",false);
  bounded(values,"dialogs.callback-lifetime-seconds",1,3600,180);
  Object captchaFont=values.get("captcha.font");if(captchaFont!=null&&(!(captchaFont instanceof String text)||text.isBlank()))throw new IllegalArgumentException("captcha.font must not be empty");bool(values,"captcha.fallback-to-map",true);bounded(values,"captcha.pack-load-timeout-seconds",1,120,15);
  boolean deliverPack=bool(values,"captcha.resource-pack.enabled",false);if(deliverPack)ResourcePackDelivery.parse(requiredText(values,"captcha.resource-pack.id"),requiredText(values,"captcha.resource-pack.url"),requiredText(values,"captcha.resource-pack.sha1"),Objects.toString(values.getOrDefault("captcha.resource-pack.prompt","")));
  String resourcePackLoading=values.getOrDefault("captcha.resource-pack-loading","MANUAL").toString().toUpperCase(java.util.Locale.ROOT);if(!Set.of("SERVER","MANUAL").contains(resourcePackLoading))throw new IllegalArgumentException("captcha.resource-pack-loading must be SERVER or MANUAL");
  bounded(values,"dialogs.captcha.body-width",1,1024,320);bounded(values,"dialogs.captcha.answer.width",1,1024,220);bool(values,"dialogs.captcha.answer.label-visible",true);bounded(values,"dialogs.captcha.button.width",1,1024,150);
  for(String screen:List.of("captcha","login","register","totp","totp-setup","recovery","management-result"))DialogButtonPlacement.parse(values.getOrDefault("dialogs."+screen+".button.placement","CONTENT").toString(),"dialogs."+screen+".button.placement");
  for(String screen:List.of("login","register")){bounded(values,"dialogs."+screen+".body-width",1,1024,320);bounded(values,"dialogs."+screen+".button.width",1,1024,150);for(String field:screen.equals("login")?List.of("password"):List.of("password","confirmation")){bounded(values,"dialogs."+screen+"."+field+".width",1,1024,300);bounded(values,"dialogs."+screen+"."+field+".max-length",8,256,256);bool(values,"dialogs."+screen+"."+field+".label-visible",true);}}
  bounded(values,"dialogs.totp.body-width",1,1024,320);bounded(values,"dialogs.totp.button.width",1,1024,180);bounded(values,"dialogs.totp.code.width",1,1024,300);bounded(values,"dialogs.totp.code.max-length",20,64,32);bool(values,"dialogs.totp.code.label-visible",true);
  bounded(values,"dialogs.totp-setup.body-width",1,1024,420);bounded(values,"dialogs.totp-setup.button.width",1,1024,180);bounded(values,"dialogs.totp-setup.code.width",1,1024,220);bounded(values,"dialogs.totp-setup.code.max-length",6,6,6);bool(values,"dialogs.totp-setup.code.label-visible",true);bounded(values,"dialogs.totp-setup.qr-quiet-zone",4,16,4);bounded(values,"dialogs.totp-setup.qr-max-modules",21,177,177);
  for(String screen:List.of("recovery","management-result")){bounded(values,"dialogs."+screen+".body-width",1,1024,400);bounded(values,"dialogs."+screen+".button.width",1,1024,180);}
  return new Settings(bool(values,"secure-input.compatibility-password-arguments",false),bool(values,"presentation.title.enabled",true));
 }
 private static String requiredText(Map<String,Object> values,String path){Object value=values.get(path);if(!(value instanceof String text)||text.isBlank())throw new IllegalArgumentException("Missing or empty Paper setting: "+path);return text.trim();}
 private static boolean bool(Map<String,Object> values,String path,boolean fallback){Object value=values.get(path);if(value==null)return fallback;if(!(value instanceof Boolean result))throw new IllegalArgumentException(path+" must be true or false");return result;}
 private static long bounded(Map<String,Object> values,String path,long min,long max,long fallback){Object value=values.get(path);if(value==null)return fallback;if(!(value instanceof Number number)||number.longValue()<min||number.longValue()>max)throw new IllegalArgumentException(path+" must be between "+min+" and "+max);return number.longValue();}
}
