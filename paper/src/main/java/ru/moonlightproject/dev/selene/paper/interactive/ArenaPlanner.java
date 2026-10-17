package ru.moonlightproject.dev.selene.paper.interactive;

import java.util.*;
import ru.moonlightproject.dev.selene.paper.InteractiveCaptchaConfig;

public final class ArenaPlanner {
 public ArenaPlan plan(InteractiveCaptchaConfig config,long seed){
  SplittableRandom random=new SplittableRandom(seed);var platform=config.platform();
  List<ArenaPlan.BlockPlacement> floor=new ArrayList<>();
  for(int x=0;x<platform.width();x++)for(int z=0;z<platform.length();z++){
   int index=switch(platform.floorPattern()){
    case SOLID -> 0;case CHECKERBOARD -> (x+z)%platform.floorMaterials().size();
    case RANDOM -> random.nextInt(platform.floorMaterials().size());};
   floor.add(new ArenaPlan.BlockPlacement(new ArenaPlan.Point(x,0,z),platform.floorMaterials().get(index)));
  }
  List<Map.Entry<String,InteractiveCaptchaConfig.ScenarioConfig>> enabled=config.scenarios().entrySet().stream().filter(e->e.getValue().enabled()).sorted(Map.Entry.comparingByKey()).toList();
  int total=enabled.stream().mapToInt(e->e.getValue().weight()).sum(),roll=random.nextInt(total);String selected=enabled.getFirst().getKey();
  for(var entry:enabled){roll-=entry.getValue().weight();if(roll<0){selected=entry.getKey();break;}}
  ScenarioType type=ScenarioType.fromConfig(selected);
  InteractiveCaptchaConfig.ScenarioConfig scenario=config.scenarios().get(selected);
  int radius=type==ScenarioType.CHOP_TREE?2:type==ScenarioType.BREAK_BLOCK||type==ScenarioType.STAND_ON_BLOCK?1:0;
  int border=platform.decoration().enabled()?2:1;
  int margin=Math.max(config.generation().minTargetDistance(),border+radius);
  List<ArenaPlan.Point> candidates=new ArrayList<>();
  for(int x=margin;x<platform.width()-margin;x++)for(int z=margin;z<platform.length()-margin;z++){
   // Reserve a cross of walkways and the spawn at their intersection.
   if(Math.abs(x-platform.width()/2)<=radius||Math.abs(z-platform.length()/2)<=radius)continue;
   candidates.add(new ArenaPlan.Point(x,1,z));
  }
  if(candidates.isEmpty())throw new GenerationFailure("Platform is too small for "+type+" with the configured target margin");
  ArenaPlan.Point target=candidates.get(random.nextInt(candidates.size()));
  List<ArenaPlan.Point> decoys=new ArrayList<>();
  List<ArenaPlan.Point> free=new ArrayList<>();
  for(int x=border;x<platform.width()-border;x++)for(int z=border;z<platform.length()-border;z++){
   if(Math.abs(x-platform.width()/2)<=1||Math.abs(z-platform.length()/2)<=1)continue;
   if(Math.abs(x-target.x())<=radius+1&&Math.abs(z-target.z())<=radius+1)continue;
   free.add(new ArenaPlan.Point(x,1,z));
  }
  while(!free.isEmpty()&&decoys.size()<config.generation().decoys()){
   ArenaPlan.Point chosen=free.remove(random.nextInt(free.size()));
   decoys.add(chosen);
   free.removeIf(p->Math.abs(p.x()-chosen.x())<=1&&Math.abs(p.z()-chosen.z())<=1);
  }
  String targetMaterial=scenario.targetMaterial().isBlank()?targetMaterial(type):scenario.targetMaterial();
  String challengeItem=scenario.challengeItem().isBlank()?challengeItem(type):scenario.challengeItem();
  return new ArenaPlan(platform.width(),platform.length(),floor,type,target,decoys,targetMaterial,challengeItem);
 }
 private static String targetMaterial(ScenarioType type){return switch(type){
  case BREAK_BLOCK -> "COAL_ORE";case CHOP_TREE -> "OAK_LOG";case TAKE_FROM_CHEST,PUT_IN_CHEST -> "CHEST";
  case ARMOR_STAND -> "ARMOR_STAND";case PRESS_BUTTON -> "STONE_BUTTON";case TOGGLE_LEVER -> "LEVER";
  case STAND_ON_BLOCK -> "GOLD_BLOCK";case ITEM_FRAME -> "ITEM_FRAME";};}
 private static String challengeItem(ScenarioType type){return switch(type){case TAKE_FROM_CHEST,PUT_IN_CHEST,ITEM_FRAME -> "EMERALD";case ARMOR_STAND -> "IRON_CHESTPLATE";default -> "";};}
 public static final class GenerationFailure extends IllegalStateException {public GenerationFailure(String message){super(message);}}
}
