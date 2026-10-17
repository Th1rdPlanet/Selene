package ru.moonlightproject.dev.selene.paper.interactive;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.paper.InteractiveCaptchaConfig;

class ArenaPlannerTest {
 @Test void sameSeedIsStableAndRandomFloorVaries(){var config=config("RANDOM");var planner=new ArenaPlanner();assertEquals(planner.plan(config,42),planner.plan(config,42));assertNotEquals(planner.plan(config,42).floor(),planner.plan(config,43).floor());}
 @Test void everyEnabledScenarioCanBePlanned(){for(ScenarioType type:ScenarioType.values()){var config=config("CHECKERBOARD");var scenarios=new LinkedHashMap<String,InteractiveCaptchaConfig.ScenarioConfig>();scenarios.put(type.name().toLowerCase(Locale.ROOT).replace('_','-'),new InteractiveCaptchaConfig.ScenarioConfig(true,1));config=new InteractiveCaptchaConfig(config.world(),120,2,64,config.isolation(),config.platform(),config.generation(),scenarios);assertEquals(type,new ArenaPlanner().plan(config,1).scenario());}}
 private static InteractiveCaptchaConfig config(String pattern){YamlConfiguration y=new YamlConfiguration();String p="interactive-captcha.";y.set(p+"world","world");y.set(p+"timeout-seconds",120);y.set(p+"max-mistakes",2);y.set(p+"arena-spacing",64);y.set(p+"platform.width",10);y.set(p+"platform.length",10);y.set(p+"platform.floor-pattern",pattern);y.set(p+"platform.floor-materials",List.of("STONE","ANDESITE"));y.set(p+"platform.boundary-material","BARRIER");y.set(p+"generation.attempts",32);y.set(p+"generation.min-target-distance",2);y.set(p+"generation.decoys",3);y.set(p+"scenarios.break-block.enabled",true);y.set(p+"scenarios.break-block.weight",1);return InteractiveCaptchaConfig.parse(y.getConfigurationSection("interactive-captcha"));}
}
