package ru.moonlightproject.dev.selene.paper;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import ru.moonlightproject.dev.selene.paper.interactive.*;

final class InteractiveCaptchaManager implements AutoCloseable {
 private record Snapshot(ItemStack[] storage,ItemStack[] armor,ItemStack offhand,int held,GameMode mode,Location location){}
 private static final class Arena {
  final Player player;final UUID session;final String nonce;final ArenaPlan plan;final int slot;final Location origin;final Snapshot snapshot;final Consumer<Boolean> result;final Set<UUID> entities=new HashSet<>();boolean finished;
  Arena(Player player,UUID session,String nonce,ArenaPlan plan,int slot,Location origin,Snapshot snapshot,Consumer<Boolean> result){this.player=player;this.session=session;this.nonce=nonce;this.plan=plan;this.slot=slot;this.origin=origin;this.snapshot=snapshot;this.result=result;}
  /** Centre of the block at a plan point: origin already sits in the middle of its block. */
  Location at(ArenaPlan.Point point){return origin.clone().add(point.x(),point.y(),point.z());}
 }
 private final JavaPlugin plugin;private final InteractiveCaptchaConfig config;private final AuthMessages messages;private final ArenaPlanner planner=new ArenaPlanner();private final Map<UUID,Arena> active=new HashMap<>();private final Map<UUID,ArenaPlan> retryPlans=new HashMap<>();private final Map<UUID,Integer> mistakes=new HashMap<>();private final BitSet slots=new BitSet();private final Set<Material> scenery=EnumSet.of(Material.HANGING_ROOTS,Material.LANTERN,Material.OAK_LEAVES);private World world;
 /** Highest block the arena builds above the island surface. */
 private static final int CEILING=8;
 InteractiveCaptchaManager(JavaPlugin plugin,InteractiveCaptchaConfig config,AuthMessages messages){this.plugin=plugin;this.config=config;this.messages=messages;validateMaterials();}
 private void validateMaterials(){for(String floor:config.platform().floorMaterials())material(floor);material(config.platform().boundaryMaterial());var decoration=config.platform().decoration();material(decoration.soilMaterial());for(String rock:decoration.rockMaterials())material(rock);scenery.add(material(decoration.bushMaterial()));for(String flora:decoration.floraMaterials())scenery.add(material(flora));for(var entry:config.scenarios().entrySet()){var scenario=entry.getValue();if(!scenario.enabled())continue;ScenarioType type=ScenarioType.valueOf(entry.getKey().toUpperCase(Locale.ROOT).replace('-','_'));if(type!=ScenarioType.ARMOR_STAND&&type!=ScenarioType.ITEM_FRAME)material(scenario.targetMaterial());if(!scenario.challengeItem().isBlank())item(scenario.challengeItem());}}
 void load(){
  world=Bukkit.getWorld(config.world());if(world==null)world=new WorldCreator(config.world()).environment(World.Environment.NORMAL).generator(new VoidChunkGenerator()).generateStructures(false).createWorld();
  if(world==null)throw new IllegalStateException("Interactive CAPTCHA world is unavailable: "+config.world());
  world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setGameRule(GameRule.DO_WEATHER_CYCLE,false);world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS,false);world.setGameRule(GameRule.RANDOM_TICK_SPEED,0);world.setTime(6000);world.setStorm(false);
 }
 boolean active(Player player){return active.containsKey(player.getUniqueId());}
 void start(Player player,String session,String nonce,Consumer<Boolean> result){
  stop(player,false);int slot=slots.nextClearBit(0);slots.set(slot);int columns=1024;int cellX=slot%columns,cellZ=slot/columns;
  Location origin=new Location(world,cellX*config.arenaSpacing()+0.5,64,cellZ*config.arenaSpacing()+0.5);
  Snapshot snapshot=new Snapshot(player.getInventory().getStorageContents().clone(),player.getInventory().getArmorContents().clone(),player.getInventory().getItemInOffHand().clone(),player.getInventory().getHeldItemSlot(),player.getGameMode(),player.getLocation().clone());
  ArenaPlan plan=retryPlans.computeIfAbsent(player.getUniqueId(),ignored->planner.plan(config,new java.security.SecureRandom().nextLong()));Arena arena=new Arena(player,UUID.fromString(session),nonce,plan,slot,origin,snapshot,result);active.put(player.getUniqueId(),arena);
  try{player.getInventory().clear();player.getInventory().setArmorContents(new ItemStack[4]);player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));build(arena);isolate(player);player.teleport(spawn(arena));player.setGameMode(GameMode.SURVIVAL);instruction(arena);if(!config.display().title())player.clearTitle();render(player,config.timeoutSeconds());}catch(RuntimeException failure){active.remove(player.getUniqueId());slots.clear(slot);restore(arena);throw failure;}
 }
 void stop(Player player,boolean restoreLocation){Arena arena=active.remove(player.getUniqueId());if(arena==null)return;cleanup(arena);if(!restoreLocation)arena.player.teleport(arena.snapshot.location());restore(arena);slots.clear(arena.slot);restoreVisibility(player);}
 void forget(Player player){stop(player,false);retryPlans.remove(player.getUniqueId());mistakes.remove(player.getUniqueId());}
 private void build(Arena a){
  clear(a);
  for(var block:a.plan.floor())place(a.at(block.point()),block.material());
  for(var block:a.plan.scenery())place(a.at(block.point()),block.material());
  // Two rows of barrier follow the coastline, so a jump cannot carry the player over it.
  Material boundary=material(config.platform().boundaryMaterial());for(var point:a.plan.boundary())for(int y=0;y<2;y++)a.at(point).add(0,y,0).getBlock().setType(boundary,false);
  for(var decoy:a.plan.decoys()){
   // Low stones leave the island readable and do not resemble a second task.
   Material stone=switch(Math.floorMod(decoy.x()*31+decoy.z(),3)){
    case 0 -> Material.COBBLESTONE_SLAB;
    case 1 -> Material.MOSSY_COBBLESTONE_SLAB;
    default -> Material.ANDESITE_SLAB;
   };
   a.at(decoy).getBlock().setType(stone,false);
  }
  Location target=a.at(a.plan.target());BlockFace front=face(a.plan.front());switch(a.plan.scenario()){
   case BREAK_BLOCK -> {buildOutcrop(target,front,material(a.plan.targetMaterial()));a.player.getInventory().setItem(0,new ItemStack(Material.STONE_PICKAXE));}
   case CHOP_TREE -> {buildTree(target,material(a.plan.targetMaterial()));a.player.getInventory().setItem(0,new ItemStack(Material.STONE_AXE));}
   case TAKE_FROM_CHEST -> {facing(target,material(a.plan.targetMaterial()),front);if(!(target.getBlock().getState() instanceof Chest chest))throw new IllegalArgumentException("take-from-chest target-material must be a chest");chest.getInventory().addItem(new ItemStack(item(a.plan.challengeItem())));}
   case PUT_IN_CHEST -> {facing(target,material(a.plan.targetMaterial()),front);if(!(target.getBlock().getState() instanceof Chest))throw new IllegalArgumentException("put-in-chest target-material must be a chest");a.player.getInventory().clear();a.player.getInventory().setItem(0,new ItemStack(item(a.plan.challengeItem())));}
   case ARMOR_STAND -> {Location at=target.clone();at.setDirection(new org.bukkit.util.Vector(front.getModX(),0,front.getModZ()));ArmorStand stand=world.spawn(at,ArmorStand.class,spawned->{spawned.setPersistent(false);spawned.getEquipment().setChestplate(new ItemStack(item(a.plan.challengeItem())));});a.entities.add(stand.getUniqueId());}
   case PRESS_BUTTON,TOGGLE_LEVER -> {buildPost(target);facing(a.at(a.plan.fixture()),material(a.plan.targetMaterial()),front);}
   case STAND_ON_BLOCK -> {target.clone().add(0,-1,0).getBlock().setType(material(a.plan.targetMaterial()),false);for(BlockFace face:List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST))target.clone().add(face.getModX(),-1,face.getModZ()).getBlock().setType(Material.POLISHED_ANDESITE,false);}
   case ITEM_FRAME -> {
    buildPost(target);
    // The frame hangs on the solid side of the post facing the player. Fixed frames ignore physics and
    // punches, so the item can only leave through the CAPTCHA; non-persistent ones never outlive a crash.
    ItemFrame frame=world.spawn(a.at(a.plan.fixture()).add(0,0.5,0),ItemFrame.class,spawned->{spawned.setFacingDirection(front,true);spawned.setItem(new ItemStack(item(a.plan.challengeItem())),false);spawned.setFixed(true);spawned.setInvulnerable(true);spawned.setPersistent(false);});
    if(!frame.getLocation().getBlock().getRelative(frame.getAttachedFace()).getType().isSolid()){frame.remove();throw new IllegalStateException("Item frame has no supporting block");}
    a.entities.add(frame.getUniqueId());
   }
  }
 }
 private void place(Location at,String name){var data=material(name).createBlockData();if(data instanceof Leaves leaves)leaves.setPersistent(true);at.getBlock().setBlockData(data,false);}
 /** Places a chest, button or lever turned towards the player; wall fixtures attach to the block behind them. */
 private static void facing(Location at,Material material,BlockFace front){var data=material.createBlockData();if(data instanceof FaceAttachable attachable)attachable.setAttachedFace(FaceAttachable.AttachedFace.WALL);if(data instanceof Directional directional&&directional.getFaces().contains(front))directional.setFacing(front);at.getBlock().setBlockData(data,false);}
 private static BlockFace face(ArenaPlan.Point step){return step.x()>0?BlockFace.EAST:step.x()<0?BlockFace.WEST:step.z()>0?BlockFace.SOUTH:BlockFace.NORTH;}
 private static void buildOutcrop(Location target,BlockFace front,Material ore){
  target.getBlock().setType(ore,false);
  List<Material> stones=List.of(Material.STONE,Material.ANDESITE,Material.COBBLESTONE,Material.MOSSY_COBBLESTONE);
  for(BlockFace face:List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST)){var block=target.getBlock().getRelative(face);block.setType(stones.get(Math.floorMod(block.getX()*7+block.getZ()*13,stones.size())),false);}
  // A stone stacked behind the ore keeps its front face open towards the player.
  target.getBlock().getRelative(front.getOppositeFace()).getRelative(BlockFace.UP).setType(Material.STONE,false);
 }
 private static void buildTree(Location base,Material log){
  for(int y=0;y<4;y++)base.clone().add(0,y,0).getBlock().setType(log,false);
  for(int y=2;y<=5;y++){int radius=y<=3?2:1;for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
   boolean corner=Math.abs(x)==radius&&Math.abs(z)==radius;
   // Trimmed corners round the canopy; the top layer is a small cross.
   if(x==0&&z==0&&y<4||corner&&(y==5||Math.floorMod(base.getBlockX()*3+base.getBlockZ()*5+x*7+z*11+y,3)!=0))continue;
   var data=(Leaves)Material.OAK_LEAVES.createBlockData();data.setPersistent(true);base.clone().add(x,y,z).getBlock().setBlockData(data,false);
  }}
 }
 /** A two-block stone post with a lantern on top; fixtures hang on its upper block. */
 private static void buildPost(Location base){base.getBlock().setType(Material.MOSSY_STONE_BRICKS,false);base.clone().add(0,1,0).getBlock().setType(Material.CHISELED_STONE_BRICKS,false);base.clone().add(0,2,0).getBlock().setType(Material.LANTERN,false);}
 private void instruction(Arena arena){String key="interactive-captcha.instructions."+arena.plan.scenario().name().toLowerCase(Locale.ROOT).replace('_','-');arena.player.sendMessage(messages.forLocale(arena.player.getLocale()).component(key));}
 void render(Player player,long seconds){Arena arena=active.get(player.getUniqueId());if(arena==null)return;AuthMessages localized=messages.forLocale(player.getLocale());String key="interactive-captcha.instructions."+arena.plan.scenario().name().toLowerCase(Locale.ROOT).replace('_','-');var task=localized.component(key);var time=localized.component("interactive-captcha.time",AuthMessages.seconds(seconds));if(config.display().title())player.showTitle(net.kyori.adventure.title.Title.title(localized.component("interactive-captcha.title"),task));else player.clearTitle();if(config.display().actionbar())player.sendActionBar(task.append(net.kyori.adventure.text.Component.space()).append(time));else player.sendActionBar(net.kyori.adventure.text.Component.empty());var objective=player.getScoreboard().getObjective("selene");if(objective!=null){for(String entry:player.getScoreboard().getEntries())player.getScoreboard().resetScores(entry);if(config.display().scoreboard()){objective.displayName(localized.component("interactive-captcha.scoreboard-title"));var taskScore=objective.getScore("§a");taskScore.customName(task);taskScore.setScore(2);var timeScore=objective.getScore("§b");timeScore.customName(time);timeScore.setScore(1);}}}
 boolean move(Player player,Location to){Arena a=active.get(player.getUniqueId());if(a==null)return false;double x=to.getX()-a.origin.getBlockX(),z=to.getZ()-a.origin.getBlockZ();if(x<0||z<0||x>=a.plan.width()||z>=a.plan.length()||to.getY()<a.origin.getY()||to.getY()>a.origin.getY()+5){player.teleport(spawn(a));return true;}if(a.plan.scenario()==ScenarioType.STAND_ON_BLOCK&&to.getBlockX()==a.at(a.plan.target()).getBlockX()&&to.getBlockZ()==a.at(a.plan.target()).getBlockZ())finish(a,true);return true;}
 boolean breakBlock(Player player,Location location){Arena a=active.get(player.getUniqueId());if(a==null)return false;if(scenery.contains(location.getBlock().getType()))return true;Location target=a.at(a.plan.target());boolean treeLog=a.plan.scenario()==ScenarioType.CHOP_TREE&&location.getBlockX()==target.getBlockX()&&location.getBlockZ()==target.getBlockZ()&&location.getBlockY()>=target.getBlockY()&&location.getBlockY()<=target.getBlockY()+3;boolean success=a.plan.scenario()==ScenarioType.BREAK_BLOCK&&sameBlock(target,location)||treeLog;finish(a,success);return true;}
 boolean interact(PlayerInteractEvent event){
  Arena a=active.get(event.getPlayer().getUniqueId());if(a==null)return false;
  var block=event.getClickedBlock();if(block==null||event.getAction()==Action.PHYSICAL)return true;
  // Grass, flowers and leaves are scenery: brushing against them is never an answer.
  if(scenery.contains(block.getType()))return true;
  // A left click only starts digging; BlockBreakEvent judges what was actually broken.
  if(event.getAction()==Action.LEFT_CLICK_BLOCK)return false;
  switch(a.plan.scenario()){
   case PRESS_BUTTON,TOGGLE_LEVER -> finish(a,sameBlock(a.at(a.plan.fixture()),block.getLocation()));
   case TAKE_FROM_CHEST,PUT_IN_CHEST -> {if(sameBlock(a.at(a.plan.target()),block.getLocation()))return false;finish(a,false);}
   default -> finish(a,false);
  }
  return true;
 }
 boolean interactEntity(PlayerInteractEntityEvent event){Arena a=active.get(event.getPlayer().getUniqueId());if(a==null)return false;boolean own=a.entities.contains(event.getRightClicked().getUniqueId());finish(a,own&&(a.plan.scenario()==ScenarioType.ARMOR_STAND||a.plan.scenario()==ScenarioType.ITEM_FRAME));return true;}
 /** A punch takes the item out of a frame in vanilla, so it completes the frame task; other hits are ignored. */
 boolean attackEntity(Player player,Entity entity){Arena a=active.get(player.getUniqueId());if(a==null)return false;if(a.plan.scenario()==ScenarioType.ITEM_FRAME&&a.entities.contains(entity.getUniqueId()))finish(a,true);return true;}
 /** Arena entities are only removed by cleanup, never by physics, explosions or players. */
 boolean protects(Entity entity){for(Arena a:active.values())if(a.entities.contains(entity.getUniqueId()))return true;return false;}
 boolean inventoryClick(InventoryClickEvent event){if(!(event.getWhoClicked() instanceof Player player))return false;Arena a=active.get(player.getUniqueId());if(a==null)return false;if(a.plan.scenario()!=ScenarioType.TAKE_FROM_CHEST&&a.plan.scenario()!=ScenarioType.PUT_IN_CHEST)return true;Material expected=item(a.plan.challengeItem());if(event.getInventory().getHolder() instanceof Chest chest&&sameBlock(a.at(a.plan.target()),chest.getLocation())){boolean top=event.getRawSlot()>=0&&event.getRawSlot()<event.getInventory().getSize();if(a.plan.scenario()==ScenarioType.TAKE_FROM_CHEST&&top&&event.getCurrentItem()!=null&&event.getCurrentItem().getType()==expected)finish(a,true);else if(a.plan.scenario()==ScenarioType.PUT_IN_CHEST&&(top&&event.getCursor().getType()==expected||event.isShiftClick()&&event.getCurrentItem()!=null&&event.getCurrentItem().getType()==expected))finish(a,true);}return true;}
 private void finish(Arena arena,boolean success){if(arena.finished)return;arena.finished=true;if(success){retryPlans.remove(arena.player.getUniqueId());mistakes.remove(arena.player.getUniqueId());}else if(mistakes.merge(arena.player.getUniqueId(),1,Integer::sum)>=config.maxMistakes()){retryPlans.remove(arena.player.getUniqueId());mistakes.put(arena.player.getUniqueId(),0);}arena.result.accept(success);}
 private void isolate(Player player){if(!config.isolation().hidePlayers()&&!config.isolation().hideTabList()&&!config.isolation().hideNameTags())return;for(Player other:Bukkit.getOnlinePlayers())if(!other.equals(player)){other.hidePlayer(plugin,player);player.hidePlayer(plugin,other);}}
 private void restoreVisibility(Player player){if(!config.isolation().hidePlayers()&&!config.isolation().hideTabList()&&!config.isolation().hideNameTags())return;for(Player other:Bukkit.getOnlinePlayers())if(!other.equals(player)){other.showPlayer(plugin,player);player.showPlayer(plugin,other);}}
 private void cleanup(Arena a){for(UUID id:a.entities){Entity entity=Bukkit.getEntity(id);if(entity!=null)entity.remove();}clear(a);}
 /** Empties the arena volume, including entities left behind by an earlier crash in the same slot. */
 private void clear(Arena a){
  int x=a.origin.getBlockX(),y=a.origin.getBlockY(),z=a.origin.getBlockZ(),bottom=-1;
  for(var block:a.plan.floor())bottom=Math.min(bottom,block.point().y());for(var block:a.plan.scenery())bottom=Math.min(bottom,block.point().y());
  for(int dx=-1;dx<=a.plan.width();dx++)for(int dz=-1;dz<=a.plan.length();dz++)for(int dy=bottom;dy<=CEILING;dy++)world.getBlockAt(x+dx,y+dy,z+dz).setType(Material.AIR,false);
  for(Entity entity:world.getNearbyEntities(new BoundingBox(x-1,y+bottom,z-1,x+a.plan.width()+1,y+CEILING+1,z+a.plan.length()+1)))if(!(entity instanceof Player))entity.remove();
 }
 private Location spawn(Arena a){ArenaPlan.Point spawn=a.plan.spawn();return a.origin.clone().add(spawn.x(),spawn.y(),spawn.z());}
 private void restore(Arena a){PlayerInventory inv=a.player.getInventory();inv.setStorageContents(a.snapshot.storage());inv.setArmorContents(a.snapshot.armor());inv.setItemInOffHand(a.snapshot.offhand());inv.setHeldItemSlot(a.snapshot.held());a.player.setGameMode(a.snapshot.mode());a.player.updateInventory();}
 private static boolean sameBlock(Location a,Location b){return a.getWorld()==b.getWorld()&&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();}
 private static Material material(String name){Material material=Material.matchMaterial(name);if(material==null||!material.isBlock()||material.isAir())throw new IllegalArgumentException("Unknown or non-block interactive CAPTCHA material: "+name);return material;}
 private static Material item(String name){Material material=Material.matchMaterial(name);if(material==null||material.isAir())throw new IllegalArgumentException("Unknown interactive CAPTCHA item: "+name);return material;}
 @Override public void close(){for(Arena arena:new ArrayList<>(active.values()))stop(arena.player,false);retryPlans.clear();mistakes.clear();}
}
