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
  List<Map.Entry<String,InteractiveCaptchaConfig.ScenarioConfig>> enabled=config.scenarios().entrySet().stream().filter(e->e.getValue().enabled()).toList();
  int total=enabled.stream().mapToInt(e->e.getValue().weight()).sum(),roll=random.nextInt(total);String selected=enabled.getFirst().getKey();
  for(var entry:enabled){roll-=entry.getValue().weight();if(roll<0){selected=entry.getKey();break;}}
  ScenarioType type=ScenarioType.fromConfig(selected);
  InteractiveCaptchaConfig.ScenarioConfig scenario=config.scenarios().get(selected);
  ArenaPlan.Point target=point(random,platform.width(),platform.length(),config.generation().minTargetDistance());
  List<ArenaPlan.Point> decoys=new ArrayList<>();Set<ArenaPlan.Point> occupied=new HashSet<>();occupied.add(target);
  int limit=config.generation().attempts()*Math.max(1,config.generation().decoys());
  for(int i=0;decoys.size()<config.generation().decoys()&&i<limit;i++){ArenaPlan.Point p=point(random,platform.width(),platform.length(),1);if(occupied.add(p))decoys.add(p);}
  if(decoys.size()<config.generation().decoys())throw new GenerationFailure("not enough collision-free cells");
  String targetMaterial=scenario.targetMaterial().isBlank()?targetMaterial(type):scenario.targetMaterial();
  String challengeItem=scenario.challengeItem().isBlank()?challengeItem(type):scenario.challengeItem();
  return new ArenaPlan(platform.width(),platform.length(),floor,type,target,decoys,targetMaterial,challengeItem);
 }
 private static ArenaPlan.Point point(SplittableRandom random,int width,int length,int minimum){
  int margin=Math.min(Math.max(1,minimum),Math.max(1,Math.min(width,length)/2-1));
  return new ArenaPlan.Point(random.nextInt(margin,width-margin),1,random.nextInt(margin,length-margin));
 }
 private static String targetMaterial(ScenarioType type){return switch(type){
  case BREAK_BLOCK -> "COAL_ORE";case CHOP_TREE -> "OAK_LOG";case TAKE_FROM_CHEST,PUT_IN_CHEST -> "CHEST";
  case ARMOR_STAND -> "ARMOR_STAND";case PRESS_BUTTON -> "STONE_BUTTON";case TOGGLE_LEVER -> "LEVER";
  case STAND_ON_BLOCK -> "GOLD_BLOCK";case ITEM_FRAME -> "ITEM_FRAME";};}
 private static String challengeItem(ScenarioType type){return switch(type){case TAKE_FROM_CHEST,PUT_IN_CHEST,ITEM_FRAME -> "EMERALD";case ARMOR_STAND -> "IRON_CHESTPLATE";default -> "";};}
 public static final class GenerationFailure extends IllegalStateException {public GenerationFailure(String message){super(message);}}
}
