package ru.moonlightproject.dev.selene.paper;

import org.bukkit.Location;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Map;

final class PresentationSnapshot {
 private final Location location;
 final Scoreboard scoreboard;
 final float experience;
 final int level,totalExperience;
 final Map<PotionEffectType,PotionEffect> effects;
 Scoreboard ownedScoreboard;
 float ownedExperience;
 int ownedLevel;
 boolean ownsExperience;
 final Map<PotionEffectType,PotionEffect> ownedEffects=new java.util.HashMap<>();

 PresentationSnapshot(Location location,Scoreboard scoreboard,float experience,int level,int totalExperience,Map<PotionEffectType,PotionEffect> effects){
  this.location=location;this.scoreboard=scoreboard;this.experience=experience;this.level=level;this.totalExperience=totalExperience;this.effects=Map.copyOf(effects);
 }
 Location location(){return location;}

 static <T> T scoreboardToRestore(T current,T owned,T previous){return current==owned?previous:null;}
 static boolean mayRestoreExperience(float currentExperience,int currentLevel,float ownedExperience,int ownedLevel){return Float.compare(currentExperience,ownedExperience)==0&&currentLevel==ownedLevel;}
 static boolean mayRestoreEffect(int currentAmplifier,boolean currentAmbient,boolean currentParticles,boolean currentIcon,int ownedAmplifier,boolean ownedAmbient,boolean ownedParticles,boolean ownedIcon){
  return currentAmplifier==ownedAmplifier&&currentAmbient==ownedAmbient&&currentParticles==ownedParticles&&currentIcon==ownedIcon;
 }
}
