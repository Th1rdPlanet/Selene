package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PaperConfigValidatorTest {
 @Test void appliesSafeOptionalDefaults(){
  Map<String,Object> values=valid();
  assertFalse(PaperConfigValidator.validate(values).compatibilityPasswordArguments());
  assertTrue(PaperConfigValidator.validate(values).titleEnabled());
 }
 @Test void rejectsUnknownAndLegacyKeysWithActionablePath(){
  Map<String,Object> unknown=valid();unknown.put("mystery",true);
  assertEquals("Unknown Paper setting: mystery",assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(unknown)).getMessage());
  Map<String,Object> legacy=valid();legacy.put("auth-location.enabled",true);
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(legacy)).getMessage().contains("replace it with limbo.*"));
 }
 @Test void rejectsMissingLimboWorldAndInvalidBounds(){
  Map<String,Object> missing=valid();missing.remove("limbo.world");
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(missing)).getMessage().contains("limbo.world"));
  Map<String,Object> bounds=valid();bounds.put("timeout-seconds",1);
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(bounds)).getMessage().contains("timeout-seconds"));
  Map<String,Object> animation=valid();animation.put("presentation.title.fade-in-millis",-1);
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(animation)).getMessage().contains("fade-in-millis"));
  Map<String,Object> dialog=valid();dialog.put("dialogs.register.confirmation.max-length",1024);
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(dialog)).getMessage().contains("dialogs.register.confirmation.max-length"));
 }
 @Test void rejectsOldPresentationLayoutInsteadOfSilentlyIgnoringIt(){
  Map<String,Object> values=valid();values.put("presentation.actionbar-enabled",true);
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(values)).getMessage().contains("copy the new presentation section"));
 }
 @Test void invalidMiniMessageNamesItsConfigPath(){
  assertTrue(assertThrows(IllegalArgumentException.class,()->new AuthMessages(Map.of("messages.bad","<red>broken"))).getMessage().startsWith("Invalid MiniMessage at messages.bad:"));
 }
 @Test void validatesDialogButtonPlacement(){
  Map<String,Object> content=valid();content.put("dialogs.captcha.button.placement","CONTENT");
  assertDoesNotThrow(()->PaperConfigValidator.validate(content));
  Map<String,Object> invalid=valid();invalid.put("dialogs.login.button.placement","CENTER");
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(invalid)).getMessage().contains("dialogs.login.button.placement"));
 }
 @Test void validatesTotpDialogAndQrBounds(){
  Map<String,Object> quiet=valid();quiet.put("dialogs.totp-setup.qr-quiet-zone",3);
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(quiet)).getMessage().contains("qr-quiet-zone"));
  Map<String,Object> code=valid();code.put("dialogs.totp-setup.code.max-length",7);
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(code)).getMessage().contains("code.max-length"));
 }
 @Test void totpSetupEscapePolicyIsConfigurable(){
  Map<String,Object> values=valid();values.put("dialogs.totp-setup.can-close-with-escape",false);
  assertDoesNotThrow(()->PaperConfigValidator.validate(values));
  values.put("dialogs.totp-setup.can-close-with-escape","no");
  assertTrue(assertThrows(IllegalArgumentException.class,()->PaperConfigValidator.validate(values)).getMessage().contains("can-close-with-escape"));
 }
 private static Map<String,Object> valid(){Map<String,Object> values=new HashMap<>();values.put("role","PROXY_AUTH");values.put("node-id","auth");values.put("packet-events-mode","AUTO");values.put("stage","BEFORE_JOIN");values.put("captcha-view","MAP");values.put("dialogs.enabled",true);values.put("timeout-seconds",120);values.put("limbo.mode","MANAGED");values.put("limbo.world","selene_limbo");return values;}
}
