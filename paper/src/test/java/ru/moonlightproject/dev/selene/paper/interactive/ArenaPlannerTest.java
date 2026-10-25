package ru.moonlightproject.dev.selene.paper.interactive;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.paper.InteractiveCaptchaConfig;

class ArenaPlannerTest {
 @Test void sameSeedIsStableAndIslandVaries(){var config=config(16);var planner=new ArenaPlanner();assertEquals(planner.plan(config,42),planner.plan(config,42));assertNotEquals(planner.plan(config,42).floor(),planner.plan(config,43).floor());}
 @Test void everyEnabledScenarioCanBePlanned(){for(ScenarioType type:ScenarioType.values())for(int size:List.of(16,20,32)){var config=config(size);var scenarios=new LinkedHashMap<String,InteractiveCaptchaConfig.ScenarioConfig>();scenarios.put(type.name().toLowerCase(Locale.ROOT).replace('_','-'),new InteractiveCaptchaConfig.ScenarioConfig(true,1));config=new InteractiveCaptchaConfig(config.world(),120,2,64,config.isolation(),config.display(),config.platform(),config.generation(),scenarios);for(long seed=0;seed<50;seed++)assertEquals(type,new ArenaPlanner().plan(config,seed).scenario());}}
 @Test void islandKeepsSpawnTargetAndCoastSound(){
  var c=config(20);
  for(long seed=0;seed<500;seed++){
   var plan=new ArenaPlanner().plan(c,seed);
   Set<List<Integer>> land=new HashSet<>();
   for(var block:plan.floor())if(block.point().y()==0)land.add(List.of(block.point().x(),block.point().z()));
   var spawn=plan.spawn();var target=plan.target();
   assertTrue(land.contains(List.of(spawn.x(),spawn.z())));
   assertTrue(land.contains(List.of(target.x(),target.z())));
   assertEquals(1,Math.abs(plan.front().x())+Math.abs(plan.front().z()));
   // Fixtures hang over land next to the target, on the side that faces the spawn.
   var fixture=plan.fixture();assertTrue(land.contains(List.of(fixture.x(),fixture.z())));
   assertTrue(Math.abs(spawn.x()-fixture.x())+Math.abs(spawn.z()-fixture.z())<Math.abs(spawn.x()-target.x())+Math.abs(spawn.z()-target.z()));
   Set<List<Integer>> occupied=new HashSet<>();
   for(var block:plan.scenery())if(block.point().y()>=1){
    assertTrue(land.contains(List.of(block.point().x(),block.point().z())));
    assertTrue(chebyshev(block.point(),spawn)>1);
    assertTrue(chebyshev(block.point(),target)>1);
    assertFalse(block.point().equals(plan.fixture()));
    occupied.add(List.of(block.point().x(),block.point().z()));
   }
   for(var point:plan.decoys()){
    assertTrue(land.contains(List.of(point.x(),point.z())));
    assertTrue(chebyshev(point,spawn)>1&&chebyshev(point,target)>1);
    assertFalse(occupied.contains(List.of(point.x(),point.z())));
   }
   // Every land cell is closed in by land or barrier, so nobody steps into the void.
   Set<List<Integer>> boundary=new HashSet<>();for(var point:plan.boundary())boundary.add(List.of(point.x(),point.z()));
   for(var cell:land)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){var next=List.of(cell.get(0)+dx,cell.get(1)+dz);assertTrue(land.contains(next)||boundary.contains(next));}
   for(var block:plan.floor())if(block.point().y()<0)assertTrue(land.contains(List.of(block.point().x(),block.point().z())));
  }
 }
 private static int chebyshev(ArenaPlan.Point a,ArenaPlan.Point b){return Math.max(Math.abs(a.x()-b.x()),Math.abs(a.z()-b.z()));}
 private static InteractiveCaptchaConfig config(int size){YamlConfiguration y=new YamlConfiguration();String p="interactive-captcha.";y.set(p+"world","world");y.set(p+"timeout-seconds",120);y.set(p+"max-mistakes",2);y.set(p+"arena-spacing",64);y.set(p+"platform.width",size);y.set(p+"platform.length",size);y.set(p+"platform.floor-pattern","SOLID");y.set(p+"platform.floor-materials",List.of("GRASS_BLOCK"));y.set(p+"platform.boundary-material","BARRIER");y.set(p+"generation.attempts",32);y.set(p+"generation.min-target-distance",3);y.set(p+"generation.decoys",5);y.set(p+"scenarios.break-block.enabled",true);y.set(p+"scenarios.break-block.weight",1);return InteractiveCaptchaConfig.parse(y.getConfigurationSection("interactive-captcha"));}
}
