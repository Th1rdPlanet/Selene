package ru.moonlightproject.dev.selene.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class InteractiveCaptchaConfigTest {
 @Test void bundledDefaultsAreCompleteAndImmutable() throws Exception {
  YamlConfiguration yaml=new YamlConfiguration();
  try(InputStream in=getClass().getClassLoader().getResourceAsStream("defaults/interactive-captcha.yml")){
   assertNotNull(in);yaml.loadFromString(new String(in.readAllBytes(),StandardCharsets.UTF_8));
  }
  InteractiveCaptchaConfig config=InteractiveCaptchaConfig.parse(yaml.getConfigurationSection("interactive-captcha"));
  assertEquals(16,config.platform().width());assertEquals(InteractiveCaptchaConfig.FloorPattern.RANDOM,config.platform().floorPattern());
  assertEquals(2,config.platform().floorMaterials().size());assertEquals(9,config.scenarios().size());
  assertTrue(config.platform().decoration().enabled());assertEquals("POLISHED_ANDESITE",config.platform().decoration().borderMaterial());
  assertThrows(UnsupportedOperationException.class,()->config.scenarios().clear());
 }
 @Test void invalidBoundsAndUnsafeMaterialsNameFullPath(){
  YamlConfiguration small=base();small.set("interactive-captcha.platform.width",4);
  assertTrue(assertThrows(IllegalArgumentException.class,()->parse(small)).getMessage().contains("interactive-captcha.platform.width"));
  YamlConfiguration unsafe=base();unsafe.set("interactive-captcha.platform.floor-materials",java.util.List.of("TNT"));
  assertTrue(assertThrows(IllegalArgumentException.class,()->parse(unsafe)).getMessage().contains("interactive-captcha.platform.floor-materials"));
 }
 private static InteractiveCaptchaConfig parse(YamlConfiguration yaml){return InteractiveCaptchaConfig.parse(yaml.getConfigurationSection("interactive-captcha"));}
 private static YamlConfiguration base(){YamlConfiguration y=new YamlConfiguration();y.set("interactive-captcha.world","world");y.set("interactive-captcha.timeout-seconds",120);y.set("interactive-captcha.max-mistakes",2);y.set("interactive-captcha.arena-spacing",64);y.set("interactive-captcha.platform.width",10);y.set("interactive-captcha.platform.length",10);y.set("interactive-captcha.platform.floor-pattern","RANDOM");y.set("interactive-captcha.platform.floor-materials",java.util.List.of("STONE"));y.set("interactive-captcha.platform.boundary-material","BARRIER");y.set("interactive-captcha.generation.attempts",10);y.set("interactive-captcha.generation.min-target-distance",2);y.set("interactive-captcha.generation.decoys",1);y.set("interactive-captcha.scenarios.break-block.enabled",true);y.set("interactive-captcha.scenarios.break-block.weight",1);return y;}
}
