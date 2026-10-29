package ru.moonlightproject.dev.selene.paper;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

final class LimboManager {
 private final JavaPlugin plugin;
 private final String mode,worldName;
 private final double x,y,z;
 private final float yaw,pitch;
 private World world;

 LimboManager(JavaPlugin plugin,ConfigurationSection config){
  this.plugin=plugin;mode=config.getString("mode","MANAGED").toUpperCase(java.util.Locale.ROOT);
  worldName=config.getString("world","selene_limbo");x=config.getDouble("x",0.5);y=config.getDouble("y",64);z=config.getDouble("z",0.5);
  yaw=(float)config.getDouble("yaw",0);pitch=(float)config.getDouble("pitch",0);
  if(!mode.equals("MANAGED")&&!mode.equals("EXISTING"))throw new IllegalArgumentException("limbo.mode must be MANAGED or EXISTING");
  if(worldName.isBlank())throw new IllegalArgumentException("limbo.world must not be blank");
 }

 void load(){
  world=Bukkit.getWorld(worldName);
  if(world==null&&mode.equals("MANAGED"))world=new WorldCreator(worldName).environment(World.Environment.NORMAL).generator(new VoidChunkGenerator()).generateStructures(false).createWorld();
  if(world==null)throw new IllegalStateException("Limbo world is unavailable: "+worldName);
  world.setSpawnLocation(new Location(world,x,y,z,yaw,pitch));
  world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setGameRule(GameRule.DO_WEATHER_CYCLE,false);
  world.setTime(6000);world.setStorm(false);world.setThundering(false);
  if(mode.equals("MANAGED"))placeSupport();
 }

 // The managed limbo is empty void: an invisible 3x3 barrier under the anchor keeps a held player
 // on the ground, so the client does not fall and vanilla does not kick them for floating.
 private void placeSupport(){
  int bx=Location.locToBlock(x),by=Location.locToBlock(y)-1,bz=Location.locToBlock(z);
  if(by<world.getMinHeight())return;
  for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){var block=world.getBlockAt(bx+dx,by,bz+dz);if(block.getType().isAir())block.setType(Material.BARRIER,false);}
 }

 void enter(Player player){if(world==null)throw new IllegalStateException("Limbo is not loaded");player.teleport(new Location(world,x,y,z,yaw,pitch),PlayerTeleportEvent.TeleportCause.PLUGIN);}
 void exit(Player player,PresentationSnapshot snapshot){if(snapshot.location()!=null)player.teleport(snapshot.location(),PlayerTeleportEvent.TeleportCause.PLUGIN);}
 Location anchor(){return new Location(world,x,y,z,yaw,pitch);}

}
