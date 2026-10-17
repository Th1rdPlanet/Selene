package ru.moonlightproject.dev.selene.paper;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import ru.moonlightproject.dev.selene.paper.interactive.*;

final class InteractiveCaptchaManager implements AutoCloseable {
 private record Snapshot(ItemStack[] storage,ItemStack[] armor,ItemStack offhand,int held,GameMode mode,Location location){}
 private static final class Arena {
  final Player player;final UUID session;final String nonce;final ArenaPlan plan;final int slot;final Location origin;final Snapshot snapshot;final Consumer<Boolean> result;final Set<UUID> entities=new HashSet<>();boolean finished;
  Arena(Player player,UUID session,String nonce,ArenaPlan plan,int slot,Location origin,Snapshot snapshot,Consumer<Boolean> result){this.player=player;this.session=session;this.nonce=nonce;this.plan=plan;this.slot=slot;this.origin=origin;this.snapshot=snapshot;this.result=result;}
  Location at(ArenaPlan.Point point){return origin.clone().add(point.x(),point.y(),point.z());}
 }
 private final JavaPlugin plugin;private final InteractiveCaptchaConfig config;private final AuthMessages messages;private final ArenaPlanner planner=new ArenaPlanner();private final Map<UUID,Arena> active=new HashMap<>();private final Map<UUID,ArenaPlan> retryPlans=new HashMap<>();private final Map<UUID,Integer> mistakes=new HashMap<>();private final BitSet slots=new BitSet();private World world;
 InteractiveCaptchaManager(JavaPlugin plugin,InteractiveCaptchaConfig config,AuthMessages messages){this.plugin=plugin;this.config=config;this.messages=messages;validateMaterials();}
 private void validateMaterials(){for(String floor:config.platform().floorMaterials())material(floor);material(config.platform().boundaryMaterial());var decoration=config.platform().decoration();material(decoration.borderMaterial());material(decoration.railingMaterial());material(decoration.lampBaseMaterial());material(decoration.lightMaterial());for(var entry:config.scenarios().entrySet()){var scenario=entry.getValue();if(!scenario.enabled())continue;ScenarioType type=ScenarioType.valueOf(entry.getKey().toUpperCase(Locale.ROOT).replace('-','_'));if(type!=ScenarioType.ARMOR_STAND&&type!=ScenarioType.ITEM_FRAME)material(scenario.targetMaterial());if(!scenario.challengeItem().isBlank())item(scenario.challengeItem());}}
 void load(){
  world=Bukkit.getWorld(config.world());if(world==null)world=new WorldCreator(config.world()).environment(World.Environment.NORMAL).generator(new VoidChunkGenerator()).generateStructures(false).createWorld();
  if(world==null)throw new IllegalStateException("Interactive CAPTCHA world is unavailable: "+config.world());
  world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setGameRule(GameRule.DO_WEATHER_CYCLE,false);world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS,false);world.setTime(6000);world.setStorm(false);
 }
 boolean active(Player player){return active.containsKey(player.getUniqueId());}
 void start(Player player,String session,String nonce,Consumer<Boolean> result){
  stop(player,false);int slot=slots.nextClearBit(0);slots.set(slot);int columns=1024;int cellX=slot%columns,cellZ=slot/columns;
  Location origin=new Location(world,cellX*config.arenaSpacing()+0.5,64,cellZ*config.arenaSpacing()+0.5);
  Snapshot snapshot=new Snapshot(player.getInventory().getStorageContents().clone(),player.getInventory().getArmorContents().clone(),player.getInventory().getItemInOffHand().clone(),player.getInventory().getHeldItemSlot(),player.getGameMode(),player.getLocation().clone());
  ArenaPlan plan=retryPlans.computeIfAbsent(player.getUniqueId(),ignored->planner.plan(config,new java.security.SecureRandom().nextLong()));Arena arena=new Arena(player,UUID.fromString(session),nonce,plan,slot,origin,snapshot,result);active.put(player.getUniqueId(),arena);
  try{player.getInventory().clear();player.getInventory().setArmorContents(new ItemStack[4]);player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));build(arena);isolate(player);player.teleport(origin.clone().add(config.platform().width()/2.0,2,config.platform().length()/2.0));player.setGameMode(GameMode.SURVIVAL);instruction(arena);if(!config.display().title())player.clearTitle();render(player,config.timeoutSeconds());}catch(RuntimeException failure){active.remove(player.getUniqueId());slots.clear(slot);restore(arena);throw failure;}
 }
 void stop(Player player,boolean restoreLocation){Arena arena=active.remove(player.getUniqueId());if(arena==null)return;cleanup(arena);if(!restoreLocation)arena.player.teleport(arena.snapshot.location());restore(arena);slots.clear(arena.slot);restoreVisibility(player);}
 void forget(Player player){stop(player,false);retryPlans.remove(player.getUniqueId());mistakes.remove(player.getUniqueId());}
 private void build(Arena a){
  int baseX=a.origin.getBlockX(),baseY=a.origin.getBlockY(),baseZ=a.origin.getBlockZ();
  for(int x=-1;x<=a.plan.width();x++)for(int z=-1;z<=a.plan.length();z++)for(int y=0;y<=8;y++)world.getBlockAt(baseX+x,baseY+y,baseZ+z).setType(Material.AIR,false);
  var decoration=config.platform().decoration();Material border=material(decoration.borderMaterial());
  for(var block:a.plan.floor()){boolean edge=block.point().x()<2||block.point().z()<2||block.point().x()>=a.plan.width()-2||block.point().z()>=a.plan.length()-2;world.getBlockAt(baseX+block.point().x(),baseY,baseZ+block.point().z()).setType(decoration.enabled()&&edge?border:material(block.material()),false);}
  if(decoration.enabled()){
   decoratePlatform(a,baseX,baseY,baseZ,decoration);
   // Connect all four entrances with an unobstructed, contrasting path.
   for(int x=2;x<a.plan.width()-2;x++)world.getBlockAt(baseX+x,baseY,baseZ+a.plan.length()/2).setType(border,false);
   for(int z=2;z<a.plan.length()-2;z++)world.getBlockAt(baseX+a.plan.width()/2,baseY,baseZ+z).setType(border,false);
   world.getBlockAt(baseX+a.plan.width()/2,baseY,baseZ+a.plan.length()/2).setType(material(decoration.lightMaterial()),false);
  }
  Material boundary=material(config.platform().boundaryMaterial());for(int x=-1;x<=a.plan.width();x++){world.getBlockAt(baseX+x,baseY+1,baseZ-1).setType(boundary,false);world.getBlockAt(baseX+x,baseY+1,baseZ+a.plan.length()).setType(boundary,false);}for(int z=0;z<a.plan.length();z++){world.getBlockAt(baseX-1,baseY+1,baseZ+z).setType(boundary,false);world.getBlockAt(baseX+a.plan.width(),baseY+1,baseZ+z).setType(boundary,false);}
  for(var decoy:a.plan.decoys()){
   // Low stones leave the arena readable and do not resemble a second task.
   Material stone=switch(Math.floorMod(decoy.x()*31+decoy.z(),3)){
    case 0 -> Material.COBBLESTONE_SLAB;
    case 1 -> Material.MOSSY_COBBLESTONE_SLAB;
    default -> Material.ANDESITE_SLAB;
   };
   world.getBlockAt(baseX+decoy.x(),baseY+decoy.y(),baseZ+decoy.z()).setType(stone,false);
  }
  Location target=a.at(a.plan.target());switch(a.plan.scenario()){
   case BREAK_BLOCK -> {target.getBlock().setType(material(a.plan.targetMaterial()),false);for(BlockFace face:List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST))target.getBlock().getRelative(face).setType(Material.STONE,false);}
   case CHOP_TREE -> buildTree(target,material(a.plan.targetMaterial()));
   case TAKE_FROM_CHEST -> {target.clone().add(0,-1,0).getBlock().setType(Material.BRICKS,false);target.getBlock().setType(material(a.plan.targetMaterial()),false);if(!(target.getBlock().getState() instanceof Chest chest))throw new IllegalArgumentException("take-from-chest target-material must be a chest");chest.getInventory().addItem(new ItemStack(item(a.plan.challengeItem())));}
   case PUT_IN_CHEST -> {target.clone().add(0,-1,0).getBlock().setType(Material.BRICKS,false);target.getBlock().setType(material(a.plan.targetMaterial()),false);if(!(target.getBlock().getState() instanceof Chest))throw new IllegalArgumentException("put-in-chest target-material must be a chest");a.player.getInventory().clear();a.player.getInventory().setItem(0,new ItemStack(item(a.plan.challengeItem())));}
   case ARMOR_STAND -> {target.clone().add(0,-1,0).getBlock().setType(Material.CHISELED_STONE_BRICKS,false);ArmorStand stand=world.spawn(target.clone().add(0.5,0,0.5),ArmorStand.class);stand.getEquipment().setChestplate(new ItemStack(item(a.plan.challengeItem())));a.entities.add(stand.getUniqueId());}
   case PRESS_BUTTON -> {buildControlPillar(target);target.clone().add(0,3,0).getBlock().setType(material(a.plan.targetMaterial()),false);}
   case TOGGLE_LEVER -> {buildControlPillar(target);target.clone().add(0,3,0).getBlock().setType(material(a.plan.targetMaterial()),false);}
   case STAND_ON_BLOCK -> {target.clone().add(0,-1,0).getBlock().setType(material(a.plan.targetMaterial()),false);for(BlockFace face:List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST))target.clone().add(face.getModX(),-1,face.getModZ()).getBlock().setType(Material.QUARTZ_BLOCK,false);}
   case ITEM_FRAME -> {target.getBlock().setType(Material.CHISELED_STONE_BRICKS,false);ItemFrame frame=world.spawn(target.clone().add(0.5,1.01,0.5),ItemFrame.class);frame.setFacingDirection(BlockFace.UP,true);frame.setItem(new ItemStack(item(a.plan.challengeItem())));a.entities.add(frame.getUniqueId());}
  }
 }
 private void decoratePlatform(Arena a,int baseX,int baseY,int baseZ,InteractiveCaptchaConfig.Decoration decoration){Material railing=material(decoration.railingMaterial()),base=material(decoration.lampBaseMaterial()),light=material(decoration.lightMaterial());int middleX=a.plan.width()/2,middleZ=a.plan.length()/2;for(int x=1;x<a.plan.width()-1;x++)if(Math.abs(x-middleX)>1){world.getBlockAt(baseX+x,baseY+1,baseZ).setType(railing,false);world.getBlockAt(baseX+x,baseY+1,baseZ+a.plan.length()-1).setType(railing,false);}for(int z=1;z<a.plan.length()-1;z++)if(Math.abs(z-middleZ)>1){world.getBlockAt(baseX,baseY+1,baseZ+z).setType(railing,false);world.getBlockAt(baseX+a.plan.width()-1,baseY+1,baseZ+z).setType(railing,false);}for(int[] corner:new int[][]{{1,1},{a.plan.width()-2,1},{1,a.plan.length()-2},{a.plan.width()-2,a.plan.length()-2}}){for(int y=1;y<=2;y++)world.getBlockAt(baseX+corner[0],baseY+y,baseZ+corner[1]).setType(base,false);world.getBlockAt(baseX+corner[0],baseY+3,baseZ+corner[1]).setType(light,false);}}
 private static void buildTree(Location base,Material log){for(int y=0;y<4;y++)base.clone().add(0,y,0).getBlock().setType(log,false);for(int y=2;y<=4;y++){int radius=y==4?1:2;for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)if(Math.abs(x)+Math.abs(z)<=radius+1&&!(x==0&&z==0&&y<4)){
    var leaf=base.clone().add(x,y,z).getBlock();
    var data=(org.bukkit.block.data.type.Leaves)Material.OAK_LEAVES.createBlockData();
    data.setPersistent(true);leaf.setBlockData(data,false);
   }}}
 private static void buildControlPillar(Location base){for(int y=0;y<=2;y++)base.clone().add(0,y,0).getBlock().setType(y==1?Material.CHISELED_STONE_BRICKS:Material.STONE_BRICKS,false);}
 private void instruction(Arena arena){String key="interactive-captcha.instructions."+arena.plan.scenario().name().toLowerCase(Locale.ROOT).replace('_','-');arena.player.sendMessage(messages.forLocale(arena.player.getLocale()).component(key));}
 void render(Player player,long seconds){Arena arena=active.get(player.getUniqueId());if(arena==null)return;AuthMessages localized=messages.forLocale(player.getLocale());String key="interactive-captcha.instructions."+arena.plan.scenario().name().toLowerCase(Locale.ROOT).replace('_','-');var task=localized.component(key);var time=localized.component("interactive-captcha.time",AuthMessages.seconds(seconds));if(config.display().title())player.showTitle(net.kyori.adventure.title.Title.title(localized.component("interactive-captcha.title"),task));else player.clearTitle();if(config.display().actionbar())player.sendActionBar(task.append(net.kyori.adventure.text.Component.space()).append(time));else player.sendActionBar(net.kyori.adventure.text.Component.empty());var objective=player.getScoreboard().getObjective("selene");if(objective!=null){for(String entry:player.getScoreboard().getEntries())player.getScoreboard().resetScores(entry);if(config.display().scoreboard()){objective.displayName(localized.component("interactive-captcha.scoreboard-title"));var taskScore=objective.getScore("§a");taskScore.customName(task);taskScore.setScore(2);var timeScore=objective.getScore("§b");timeScore.customName(time);timeScore.setScore(1);}}}
 boolean move(Player player,Location to){Arena a=active.get(player.getUniqueId());if(a==null)return false;double x=to.getX()-a.origin.getX(),z=to.getZ()-a.origin.getZ();if(x<0||z<0||x>=a.plan.width()||z>=a.plan.length()||to.getY()<a.origin.getY()||to.getY()>a.origin.getY()+5){player.teleport(a.origin.clone().add(a.plan.width()/2.0,2,a.plan.length()/2.0));return true;}if(a.plan.scenario()==ScenarioType.STAND_ON_BLOCK&&to.getBlockX()==a.at(a.plan.target()).getBlockX()&&to.getBlockZ()==a.at(a.plan.target()).getBlockZ())finish(a,true);return true;}
 boolean breakBlock(Player player,Location location){Arena a=active.get(player.getUniqueId());if(a==null)return false;Location target=a.at(a.plan.target());boolean treeLog=a.plan.scenario()==ScenarioType.CHOP_TREE&&location.getBlockX()==target.getBlockX()&&location.getBlockZ()==target.getBlockZ()&&location.getBlockY()>=target.getBlockY()&&location.getBlockY()<=target.getBlockY()+3;boolean success=a.plan.scenario()==ScenarioType.BREAK_BLOCK&&sameBlock(target,location)||treeLog;finish(a,success);return true;}
 boolean interact(PlayerInteractEvent event){Arena a=active.get(event.getPlayer().getUniqueId());if(a==null)return false;if(event.getClickedBlock()==null)return true;Location expected=a.at(a.plan.target());boolean success=false;if(a.plan.scenario()==ScenarioType.PRESS_BUTTON||a.plan.scenario()==ScenarioType.TOGGLE_LEVER)success=sameBlock(expected.clone().add(0,3,0),event.getClickedBlock().getLocation());else if(a.plan.scenario()==ScenarioType.TAKE_FROM_CHEST||a.plan.scenario()==ScenarioType.PUT_IN_CHEST)success=sameBlock(expected,event.getClickedBlock().getLocation());if(success&&(a.plan.scenario()==ScenarioType.TAKE_FROM_CHEST||a.plan.scenario()==ScenarioType.PUT_IN_CHEST))return false;if(success)finish(a,true);else if(event.getAction()!=Action.PHYSICAL)finish(a,false);return true;}
 boolean interactEntity(PlayerInteractEntityEvent event){Arena a=active.get(event.getPlayer().getUniqueId());if(a==null)return false;boolean success=(a.plan.scenario()==ScenarioType.ARMOR_STAND&&event.getRightClicked() instanceof ArmorStand)||(a.plan.scenario()==ScenarioType.ITEM_FRAME&&event.getRightClicked() instanceof ItemFrame);finish(a,success);return true;}
 boolean inventoryClick(InventoryClickEvent event){if(!(event.getWhoClicked() instanceof Player player))return false;Arena a=active.get(player.getUniqueId());if(a==null)return false;if(a.plan.scenario()!=ScenarioType.TAKE_FROM_CHEST&&a.plan.scenario()!=ScenarioType.PUT_IN_CHEST)return true;Material expected=item(a.plan.challengeItem());if(event.getInventory().getHolder() instanceof Chest chest&&sameBlock(a.at(a.plan.target()),chest.getLocation())){boolean top=event.getRawSlot()>=0&&event.getRawSlot()<event.getInventory().getSize();if(a.plan.scenario()==ScenarioType.TAKE_FROM_CHEST&&top&&event.getCurrentItem()!=null&&event.getCurrentItem().getType()==expected)finish(a,true);else if(a.plan.scenario()==ScenarioType.PUT_IN_CHEST&&(top&&event.getCursor().getType()==expected||event.isShiftClick()&&event.getCurrentItem()!=null&&event.getCurrentItem().getType()==expected))finish(a,true);}return true;}
 private void finish(Arena arena,boolean success){if(arena.finished)return;arena.finished=true;if(success){retryPlans.remove(arena.player.getUniqueId());mistakes.remove(arena.player.getUniqueId());}else if(mistakes.merge(arena.player.getUniqueId(),1,Integer::sum)>=config.maxMistakes()){retryPlans.remove(arena.player.getUniqueId());mistakes.put(arena.player.getUniqueId(),0);}arena.result.accept(success);}
 private void isolate(Player player){if(!config.isolation().hidePlayers()&&!config.isolation().hideTabList()&&!config.isolation().hideNameTags())return;for(Player other:Bukkit.getOnlinePlayers())if(!other.equals(player)){other.hidePlayer(plugin,player);player.hidePlayer(plugin,other);}}
 private void restoreVisibility(Player player){if(!config.isolation().hidePlayers()&&!config.isolation().hideTabList()&&!config.isolation().hideNameTags())return;for(Player other:Bukkit.getOnlinePlayers())if(!other.equals(player)){other.showPlayer(plugin,player);player.showPlayer(plugin,other);}}
 private void cleanup(Arena a){for(UUID id:a.entities){Entity entity=Bukkit.getEntity(id);if(entity!=null)entity.remove();}int x=a.origin.getBlockX(),y=a.origin.getBlockY(),z=a.origin.getBlockZ();for(int dx=-1;dx<=a.plan.width();dx++)for(int dz=-1;dz<=a.plan.length();dz++)for(int dy=0;dy<=8;dy++)world.getBlockAt(x+dx,y+dy,z+dz).setType(Material.AIR,false);}
 private void restore(Arena a){PlayerInventory inv=a.player.getInventory();inv.setStorageContents(a.snapshot.storage());inv.setArmorContents(a.snapshot.armor());inv.setItemInOffHand(a.snapshot.offhand());inv.setHeldItemSlot(a.snapshot.held());a.player.setGameMode(a.snapshot.mode());a.player.updateInventory();}
 private static boolean sameBlock(Location a,Location b){return a.getWorld()==b.getWorld()&&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();}
 private static Material material(String name){Material material=Material.matchMaterial(name);if(material==null||!material.isBlock()||material.isAir())throw new IllegalArgumentException("Unknown or non-block interactive CAPTCHA material: "+name);return material;}
 private static Material item(String name){Material material=Material.matchMaterial(name);if(material==null||material.isAir())throw new IllegalArgumentException("Unknown interactive CAPTCHA item: "+name);return material;}
 @Override public void close(){for(Arena arena:new ArrayList<>(active.values()))stop(arena.player,false);retryPlans.clear();mistakes.clear();}
}
