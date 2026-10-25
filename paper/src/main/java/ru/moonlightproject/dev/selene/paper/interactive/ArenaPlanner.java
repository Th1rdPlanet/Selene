package ru.moonlightproject.dev.selene.paper.interactive;

import java.util.*;
import ru.moonlightproject.dev.selene.paper.InteractiveCaptchaConfig;

/** Plans a small floating island: a wavy round surface, a cone of soil and rock under it and light scenery on top. */
public final class ArenaPlanner {
 /** Surface blocks that can carry grass and flowers; other floors stay bare. */
 private static final Set<String> PLANTABLE=Set.of("GRASS_BLOCK","DIRT","COARSE_DIRT","ROOTED_DIRT","PODZOL","MYCELIUM","MOSS_BLOCK");
 public ArenaPlan plan(InteractiveCaptchaConfig config,long seed){
  SplittableRandom random=new SplittableRandom(seed);
  List<Map.Entry<String,InteractiveCaptchaConfig.ScenarioConfig>> enabled=config.scenarios().entrySet().stream().filter(e->e.getValue().enabled()).sorted(Map.Entry.comparingByKey()).toList();
  int total=enabled.stream().mapToInt(e->e.getValue().weight()).sum(),roll=random.nextInt(total);String selected=enabled.getFirst().getKey();
  for(var entry:enabled){roll-=entry.getValue().weight();if(roll<0){selected=entry.getKey();break;}}
  ScenarioType type=ScenarioType.fromConfig(selected);
  // Every attempt draws a new outline; a narrow one may leave no room for the target.
  for(int attempt=0;attempt<config.generation().attempts();attempt++){
   ArenaPlan plan=attempt(config,random,type,config.scenarios().get(selected));
   if(plan!=null)return plan;
  }
  throw new GenerationFailure("Platform is too small for "+type+" with the configured target margin");
 }
 private static ArenaPlan attempt(InteractiveCaptchaConfig config,SplittableRandom random,ScenarioType type,InteractiveCaptchaConfig.ScenarioConfig scenario){
  var platform=config.platform();var decoration=platform.decoration();int width=platform.width(),length=platform.length();
  double[][] inland=shape(width,length,random);int[][] shore=shore(inland);
  ArenaPlan.Point spawn=new ArenaPlan.Point(width/2,1,length/2);
  // Footprint around the target that stays free of decoys and flora.
  int radius=switch(type){case CHOP_TREE -> 2;case BREAK_BLOCK,STAND_ON_BLOCK,PRESS_BUTTON,TOGGLE_LEVER,ITEM_FRAME -> 1;default -> 0;};
  // The ground footprint and a walkway around it stay on land and off the spawn; a canopy may overhang both.
  int ground=Math.min(radius,1)+2,shoreline=Math.max(config.generation().minTargetDistance(),ground);
  List<ArenaPlan.Point> candidates=new ArrayList<>();
  for(int x=0;x<width;x++)for(int z=0;z<length;z++)
   if(shore[x][z]>=shoreline&&distance(x,z,spawn)>=ground)candidates.add(new ArenaPlan.Point(x,1,z));
  if(candidates.isEmpty())return null;
  ArenaPlan.Point target=candidates.get(random.nextInt(candidates.size()));
  int dx=spawn.x()-target.x(),dz=spawn.z()-target.z();
  ArenaPlan.Point front=Math.abs(dx)>=Math.abs(dz)?new ArenaPlan.Point(Integer.signum(dx),0,0):new ArenaPlan.Point(0,0,Integer.signum(dz));

  List<ArenaPlan.BlockPlacement> floor=new ArrayList<>(),scenery=new ArrayList<>();
  String[][] surface=new String[width][length];
  int deepest=Math.min(8,Math.max(2,Math.min(width,length)/2-1));
  for(int x=0;x<width;x++)for(int z=0;z<length;z++){
   if(inland[x][z]<0)continue;
   int index=switch(platform.floorPattern()){
    case SOLID -> 0;case CHECKERBOARD -> (x+z)%platform.floorMaterials().size();
    case RANDOM -> random.nextInt(platform.floorMaterials().size());};
   surface[x][z]=platform.floorMaterials().get(index);
   floor.add(new ArenaPlan.BlockPlacement(new ArenaPlan.Point(x,0,z),surface[x][z]));
   // The underside narrows towards the rim, so the island hangs like a rough cone.
   int depth=decoration.enabled()?1+(int)Math.round(inland[x][z]*deepest)+random.nextInt(2):1;
   for(int y=1;y<=depth;y++){
    String block=y<=2?decoration.soilMaterial():decoration.rockMaterials().get(random.nextInt(decoration.rockMaterials().size()));
    floor.add(new ArenaPlan.BlockPlacement(new ArenaPlan.Point(x,-y,z),block));
   }
   if(decoration.enabled()&&depth<=2&&random.nextInt(4)==0)scenery.add(new ArenaPlan.BlockPlacement(new ArenaPlan.Point(x,-depth-1,z),"HANGING_ROOTS"));
  }

  List<ArenaPlan.Point> decoys=new ArrayList<>(),free=new ArrayList<>();
  for(int x=0;x<width;x++)for(int z=0;z<length;z++){
   if(shore[x][z]<2||distance(x,z,spawn)<=1||near(x,z,target,radius+1))continue;
   free.add(new ArenaPlan.Point(x,1,z));
  }
  while(!free.isEmpty()&&decoys.size()<config.generation().decoys()){
   ArenaPlan.Point chosen=free.remove(random.nextInt(free.size()));
   decoys.add(chosen);
   free.removeIf(p->Math.abs(p.x()-chosen.x())<=1&&Math.abs(p.z()-chosen.z())<=1);
  }

  if(decoration.enabled())for(int x=0;x<width;x++)for(int z=0;z<length;z++){
   ArenaPlan.Point cell=new ArenaPlan.Point(x,1,z);
   if(shore[x][z]<1||distance(x,z,spawn)<=1||near(x,z,target,radius+1)||decoys.contains(cell))continue;
   // Low bushes break up the rim; the barrier behind them is what actually holds the player.
   if(shore[x][z]==1&&random.nextInt(100)<22){
    scenery.add(new ArenaPlan.BlockPlacement(cell,decoration.bushMaterial()));
    if(random.nextInt(5)==0)scenery.add(new ArenaPlan.BlockPlacement(new ArenaPlan.Point(x,2,z),decoration.bushMaterial()));
   }else if(PLANTABLE.contains(surface[x][z])&&random.nextInt(100)<decoration.floraDensity()){
    scenery.add(new ArenaPlan.BlockPlacement(cell,decoration.floraMaterials().get(random.nextInt(decoration.floraMaterials().size()))));
   }
  }

  List<ArenaPlan.Point> boundary=new ArrayList<>();
  for(int x=-1;x<=width;x++)for(int z=-1;z<=length;z++){
   if(land(inland,x,z))continue;
   boolean coast=false;
   for(int ox=-1;ox<=1&&!coast;ox++)for(int oz=-1;oz<=1;oz++)if(land(inland,x+ox,z+oz)){coast=true;break;}
   if(coast)boundary.add(new ArenaPlan.Point(x,1,z));
  }
  String targetMaterial=scenario.targetMaterial().isBlank()?targetMaterial(type):scenario.targetMaterial();
  String challengeItem=scenario.challengeItem().isBlank()?challengeItem(type):scenario.challengeItem();
  return new ArenaPlan(width,length,floor,scenery,boundary,type,target,front,decoys,targetMaterial,challengeItem);
 }
 /** How far inside the outline each cell lies: 0 at the rim, near 1 at the centre, negative over the void. */
 private static double[][] shape(int width,int length,SplittableRandom random){
  double a=random.nextDouble(Math.PI*2),b=random.nextDouble(Math.PI*2),c=random.nextDouble(Math.PI*2);
  double[][] inland=new double[width][length];
  for(int x=0;x<width;x++)for(int z=0;z<length;z++){
   double nx=(x+0.5-width/2.0)/(width/2.0),nz=(z+0.5-length/2.0)/(length/2.0),angle=Math.atan2(nz,nx);
   // A few low harmonics give gentle bays and capes while the island stays star-shaped and connected.
   double edge=0.84+0.07*Math.sin(3*angle+a)+0.05*Math.sin(5*angle+b)+0.03*Math.sin(2*angle+c);
   double d=Math.hypot(nx,nz);
   inland[x][z]=d<=edge?1-d/edge:-1;
  }
  return inland;
 }
 /** Chebyshev distance from each land cell to the nearest void cell; 0 over the void. */
 private static int[][] shore(double[][] inland){
  int width=inland.length,length=inland[0].length;int[][] shore=new int[width][length];
  for(int x=0;x<width;x++)for(int z=0;z<length;z++){
   if(inland[x][z]<0)continue;
   int r=1;
   search:for(;;r++)for(int ox=-r;ox<=r;ox++)for(int oz=-r;oz<=r;oz++)
    if((Math.abs(ox)==r||Math.abs(oz)==r)&&!land(inland,x+ox,z+oz))break search;
   shore[x][z]=r;
  }
  return shore;
 }
 private static boolean land(double[][] inland,int x,int z){return x>=0&&z>=0&&x<inland.length&&z<inland[0].length&&inland[x][z]>=0;}
 private static int distance(int x,int z,ArenaPlan.Point point){return Math.max(Math.abs(x-point.x()),Math.abs(z-point.z()));}
 private static boolean near(int x,int z,ArenaPlan.Point point,int radius){return distance(x,z,point)<=radius;}
 private static String targetMaterial(ScenarioType type){return switch(type){
  case BREAK_BLOCK -> "COAL_ORE";case CHOP_TREE -> "OAK_LOG";case TAKE_FROM_CHEST,PUT_IN_CHEST -> "CHEST";
  case ARMOR_STAND -> "ARMOR_STAND";case PRESS_BUTTON -> "STONE_BUTTON";case TOGGLE_LEVER -> "LEVER";
  case STAND_ON_BLOCK -> "GOLD_BLOCK";case ITEM_FRAME -> "ITEM_FRAME";};}
 private static String challengeItem(ScenarioType type){return switch(type){case TAKE_FROM_CHEST,PUT_IN_CHEST,ITEM_FRAME -> "EMERALD";case ARMOR_STAND -> "IRON_CHESTPLATE";default -> "";};}
 public static final class GenerationFailure extends IllegalStateException {public GenerationFailure(String message){super(message);}}
}
