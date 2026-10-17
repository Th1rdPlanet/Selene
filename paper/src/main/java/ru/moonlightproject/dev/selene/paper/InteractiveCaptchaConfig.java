package ru.moonlightproject.dev.selene.paper;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

public record InteractiveCaptchaConfig(String world,int timeoutSeconds,int maxMistakes,int arenaSpacing,
    Isolation isolation,Platform platform,Generation generation,Map<String,ScenarioConfig> scenarios) {
 public enum FloorPattern { SOLID,RANDOM,CHECKERBOARD }
 public record Isolation(boolean hidePlayers,boolean hideTabList,boolean hideNameTags,boolean suppressChat){}
 public record Platform(int width,int length,FloorPattern floorPattern,List<String> floorMaterials,String boundaryMaterial){}
 public record Generation(int attempts,int minTargetDistance,int decoys){}
 public record ScenarioConfig(boolean enabled,int weight){}

 public static InteractiveCaptchaConfig parse(ConfigurationSection root){
  if(root==null)throw new IllegalArgumentException("Missing Paper setting: interactive-captcha");
  String world=text(root,"world");
  int timeout=bounded(root,"timeout-seconds",10,300);
  int mistakes=bounded(root,"max-mistakes",1,10);
  int width=bounded(root,"platform.width",5,32),length=bounded(root,"platform.length",5,32);
  int spacing=bounded(root,"arena-spacing",Math.max(width,length)+16,4096);
  FloorPattern pattern=enumValue(root,"platform.floor-pattern",FloorPattern.class);
  List<String> names=root.getStringList("platform.floor-materials");
  if(names.isEmpty())throw bad("platform.floor-materials","must contain at least one material");
  List<String> floor=names.stream().map(name->material("platform.floor-materials",name)).toList();
  String boundary=material("platform.boundary-material",text(root,"platform.boundary-material"));
  Generation generation=new Generation(bounded(root,"generation.attempts",1,128),
      bounded(root,"generation.min-target-distance",1,30),bounded(root,"generation.decoys",0,32));
  ConfigurationSection section=root.getConfigurationSection("scenarios");
  if(section==null)throw new IllegalArgumentException("Missing Paper setting: interactive-captcha.scenarios");
  Map<String,ScenarioConfig> scenarios=new LinkedHashMap<>();
  for(String key:section.getKeys(false)){
   boolean enabled=section.getBoolean(key+".enabled",true);int weight=bounded(section,key+".weight",1,100);
   scenarios.put(key,new ScenarioConfig(enabled,weight));
  }
  if(scenarios.values().stream().noneMatch(ScenarioConfig::enabled))throw bad("scenarios","must enable at least one scenario");
  return new InteractiveCaptchaConfig(world,timeout,mistakes,spacing,
      new Isolation(root.getBoolean("isolation.hide-players",true),root.getBoolean("isolation.hide-tab-list",true),
          root.getBoolean("isolation.hide-name-tags",true),root.getBoolean("isolation.suppress-chat",true)),
      new Platform(width,length,pattern,List.copyOf(floor),boundary),generation,Map.copyOf(scenarios));
 }
 private static String material(String path,String name){
  String material=name.trim().toUpperCase(Locale.ROOT);
  Set<String> unsafe=Set.of("AIR","CAVE_AIR","VOID_AIR","WATER","LAVA","TNT","FIRE","SOUL_FIRE",
      "NETHER_PORTAL","END_PORTAL","END_GATEWAY","COMMAND_BLOCK","CHAIN_COMMAND_BLOCK",
      "REPEATING_COMMAND_BLOCK","STRUCTURE_BLOCK","JIGSAW","SAND","RED_SAND","GRAVEL");
  if(!material.matches("[A-Z0-9_]+")||unsafe.contains(material))throw bad(path,"contains unsafe material '"+name+"'");
  return material;
 }
 private static <E extends Enum<E>> E enumValue(ConfigurationSection root,String path,Class<E> type){
  try{return Enum.valueOf(type,text(root,path).toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){throw bad(path,"has an unsupported value");}
 }
 private static String text(ConfigurationSection root,String path){String value=root.getString(path);if(value==null||value.isBlank())throw bad(path,"must not be empty");return value.trim();}
 private static int bounded(ConfigurationSection root,String path,int min,int max){int value=root.getInt(path,Integer.MIN_VALUE);if(value<min||value>max)throw bad(path,"must be between "+min+" and "+max);return value;}
 private static IllegalArgumentException bad(String path,String message){return new IllegalArgumentException("interactive-captcha."+path+" "+message);}
}
