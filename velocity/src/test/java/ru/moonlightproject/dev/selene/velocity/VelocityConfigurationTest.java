package ru.moonlightproject.dev.selene.velocity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class VelocityConfigurationTest {
 @TempDir Path directory;
 @Test void migratesPropertiesAndPreservesCustomizedMessagesAndExistingFiles() throws Exception {
  String old=new String(getClass().getResourceAsStream("/velocity.properties").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
   .replace("replace-with-64-character-lowercase-sha256","a".repeat(64)).replace("default-server=lobby","default-server=survival");
  Files.writeString(directory.resolve("velocity.properties"),old);
  Files.writeString(directory.resolve("premium.yml"),"premium-lookup-enabled: false\n");
  var result=VelocityConfiguration.load(directory);
  assertEquals("survival",result.config().defaultServer());
  assertFalse(result.config().premiumLookupEnabled());
  assertTrue(Files.exists(directory.resolve("velocity.properties.legacy.bak")));
  assertTrue(Files.readString(directory.resolve("attack.yml")).contains("profiles:"));
  assertEquals(result.config(),VelocityConfiguration.load(directory).config());
 }
 @Test void migratesLegacyNormalCaptchaIntoNormalProtectionProfile() throws Exception {
  String old=new String(getClass().getResourceAsStream("/velocity.properties").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
   .replace("replace-with-64-character-lowercase-sha256","a".repeat(64))
   .replace("normal-captcha=false","normal-captcha=true");
  Files.writeString(directory.resolve("velocity.properties"),old);

  var result=VelocityConfiguration.load(directory);

  assertTrue(result.config().attackProfiles().get(ru.moonlightproject.dev.selene.core.AttackLevel.NORMAL).captchaEnabled());
  assertTrue(Files.readString(directory.resolve("attack.yml")).matches("(?s).*profiles:.*normal:.*captcha: true.*"));
 }
 @Test void appendsNewDocumentedSettingsWithoutReplacingExistingValues() throws Exception {
  String old=new String(getClass().getResourceAsStream("/velocity.properties").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
   .replace("replace-with-64-character-lowercase-sha256","a".repeat(64));
  Files.writeString(directory.resolve("velocity.properties"),old);
  VelocityConfiguration.load(directory);
  Files.writeString(directory.resolve("attack.yml"),"attack-level: LOW\n");
  var result=VelocityConfiguration.load(directory);
  assertEquals(ru.moonlightproject.dev.selene.core.AttackLevel.LOW,result.config().attackLevel());
  String updated=Files.readString(directory.resolve("attack.yml"));
  assertTrue(updated.contains("profiles:"));
  assertTrue(updated.contains("attack-level: LOW"));
 }
 @Test void rejectsMisplacedAndDuplicateYamlSettings() throws Exception {
  Files.writeString(directory.resolve("config.yml"),"role: PROXY_AUTH\n");
  assertThrows(IllegalArgumentException.class,()->VelocityConfiguration.load(directory));
 }
}
