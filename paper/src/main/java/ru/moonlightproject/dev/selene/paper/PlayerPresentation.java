package ru.moonlightproject.dev.selene.paper;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PlayerPresentation {
 private final JavaPlugin plugin;private final AuthMessages messages;private final ConfigurationSection config;
 PlayerPresentation(JavaPlugin plugin,AuthMessages messages,ConfigurationSection config){this.plugin=plugin;this.messages=messages;this.config=config;}

 PresentationSnapshot begin(Player player){
  Map<PotionEffectType,PotionEffect> effects=new HashMap<>();for(PotionEffect effect:player.getActivePotionEffects())effects.put(effect.getType(),effect);
  PresentationSnapshot snapshot=new PresentationSnapshot(player.getLocation().clone(),player.getScoreboard(),player.getExp(),player.getLevel(),player.getTotalExperience(),effects);
  applyEffect(player,snapshot,PotionEffectType.BLINDNESS,"effects.blindness");applyEffect(player,snapshot,PotionEffectType.DARKNESS,"effects.darkness");
  if(config.getBoolean("presentation.scoreboard.enabled",true)){
   Scoreboard board=Bukkit.getScoreboardManager().getNewScoreboard();Objective objective=board.registerNewObjective("selene","dummy",messages.forLocale(player.getLocale()).component("presentation.scoreboard.states.connecting.title"));objective.setDisplaySlot(DisplaySlot.SIDEBAR);
   snapshot.ownedScoreboard=board;player.setScoreboard(board);
  }
  return snapshot;
 }

 void render(Player player,PresentationSnapshot snapshot,AuthViewState state,long nowMillis){
  long seconds=Countdown.secondsRemaining(nowMillis,state.deadlineMillis());
  String screen=PresentationState.key(state);var time=AuthMessages.seconds(seconds);
  if(config.getBoolean("presentation.title.enabled",true))player.showTitle(Title.title(messages.forLocale(player.getLocale()).component("presentation.title.states."+screen+".title",time),messages.forLocale(player.getLocale()).component("presentation.title.states."+screen+".subtitle",time),Title.Times.times(Duration.ofMillis(config.getLong("presentation.title.fade-in-millis",0)),Duration.ofMillis(config.getLong("presentation.title.stay-millis",1200)),Duration.ofMillis(config.getLong("presentation.title.fade-out-millis",200)))));
  if(config.getBoolean("presentation.actionbar.enabled",true))player.sendActionBar(messages.forLocale(player.getLocale()).component("presentation.actionbar.states."+screen,time));
  if(config.getBoolean("presentation.scoreboard.enabled",true)&&snapshot.ownedScoreboard!=null){
   Objective objective=snapshot.ownedScoreboard.getObjective("selene");if(objective!=null){objective.displayName(messages.forLocale(player.getLocale()).component("presentation.scoreboard.states."+screen+".title",time));for(String entry:snapshot.ownedScoreboard.getEntries())snapshot.ownedScoreboard.resetScores(entry);int limit=Math.min(15,messages.forLocale(player.getLocale()).lineCount("presentation.scoreboard.states."+screen+".lines"));for(int i=0;i<limit;i++){Score score=objective.getScore("§"+Integer.toHexString(i));score.customName(messages.forLocale(player.getLocale()).component("presentation.scoreboard.states."+screen+".lines."+i,time));score.setScore(limit-i);}}
  }
  if(config.getBoolean("presentation.experience.bar-enabled",true)||config.getBoolean("presentation.experience.level-enabled",true)){
   float fraction=Countdown.fraction(nowMillis,state.deadlineMillis()-Math.max(10,config.getLong("timeout-seconds",120))*1000,state.deadlineMillis());
   snapshot.ownedExperience=config.getBoolean("presentation.experience.bar-enabled",true)?fraction:player.getExp();snapshot.ownedLevel=config.getBoolean("presentation.experience.level-enabled",true)?(int)Math.min(Integer.MAX_VALUE,seconds):player.getLevel();
   snapshot.ownsExperience=true;
   if(config.getBoolean("presentation.experience.bar-enabled",true))player.setExp(snapshot.ownedExperience);if(config.getBoolean("presentation.experience.level-enabled",true))player.setLevel(snapshot.ownedLevel);
  }
 }

 void restore(Player player,PresentationSnapshot snapshot){
  Scoreboard board=PresentationSnapshot.scoreboardToRestore(player.getScoreboard(),snapshot.ownedScoreboard,snapshot.scoreboard);if(board!=null)player.setScoreboard(board);
  if(snapshot.ownsExperience&&PresentationSnapshot.mayRestoreExperience(player.getExp(),player.getLevel(),snapshot.ownedExperience,snapshot.ownedLevel)){player.setExp(snapshot.experience);player.setLevel(snapshot.level);player.setTotalExperience(snapshot.totalExperience);}
  for(Map.Entry<PotionEffectType,PotionEffect> entry:snapshot.ownedEffects.entrySet()){
   PotionEffect current=player.getPotionEffect(entry.getKey()),owned=entry.getValue();
   if(current!=null&&PresentationSnapshot.mayRestoreEffect(current.getAmplifier(),current.isAmbient(),current.hasParticles(),current.hasIcon(),owned.getAmplifier(),owned.isAmbient(),owned.hasParticles(),owned.hasIcon())){
    player.removePotionEffect(entry.getKey());PotionEffect previous=snapshot.effects.get(entry.getKey());if(previous!=null)player.addPotionEffect(previous);
   }
  }
  player.clearTitle();
 }

 private void applyEffect(Player player,PresentationSnapshot snapshot,PotionEffectType type,String path){
  if(!config.getBoolean(path))return;PotionEffect owned=new PotionEffect(type,Integer.MAX_VALUE,0,false,false,false);snapshot.ownedEffects.put(type,owned);player.addPotionEffect(owned);
 }
}
