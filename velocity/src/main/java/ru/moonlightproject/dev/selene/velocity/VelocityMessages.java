package ru.moonlightproject.dev.selene.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;
import java.util.Set;

final class VelocityMessages {
 private ru.moonlightproject.dev.selene.core.LanguageCatalog catalog;
 private Map<String,VelocityMessages> localized=Map.of();
 VelocityMessages(ru.moonlightproject.dev.selene.core.LanguageCatalog catalog){
  this(catalog.templates(catalog.defaultLocale()));
  this.catalog=catalog;
  var views=new java.util.HashMap<String,VelocityMessages>();
  for(String locale:catalog.locales())views.put(locale,new VelocityMessages(catalog.templates(locale)));
  localized=Map.copyOf(views);
 }
 VelocityMessages forLocale(String locale){return catalog==null?this:localized.get(catalog.localeKey(locale));}
 int lineCount(String path){int count=0;while(templates.containsKey(path+"."+count))count++;return count;}
 String template(String path){return templates.getOrDefault(path,"");}
 private final Map<String,String> templates;
 private final MiniMessage miniMessage=MiniMessage.builder().strict(true).build();

 VelocityMessages(Map<String,String> templates){this(templates,Set.of());}
 VelocityMessages(Map<String,String> templates,Set<String> required){
  this.templates=Map.copyOf(templates);
  for(String key:required)if(!this.templates.containsKey(key))throw new IllegalArgumentException("Missing message template: message."+key);
  this.templates.forEach((key,template)->{
   try{miniMessage.deserialize(template,validationResolver());}
   catch(RuntimeException error){throw new IllegalArgumentException("Invalid MiniMessage at message."+key+": "+error.getMessage(),error);}
  });
 }

 Component component(String key,TagResolver... resolvers){
  String template=templates.get(key);
  if(template==null)throw new IllegalArgumentException("Missing message template: message."+key);
  return miniMessage.deserialize(template,resolvers);
 }

 String plain(String key,TagResolver... resolvers){return ru.moonlightproject.dev.selene.core.ConsoleStyle.line(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component(key,resolvers)));}
 /** Inserts another template, e.g. a localized word, as the value of a placeholder. */
 TagResolver part(String key,String template,TagResolver... resolvers){return Placeholder.component(key,component(template,resolvers));}
 TagResolver flag(String key,boolean value){return part(key,value?"value-yes":"value-no");}
 TagResolver text(String key,String value){return value==null||value.isBlank()?part(key,"value-none"):literal(key,value);}
 /** Time left until an epoch second, or "none" when it is unset or already past. */
 TagResolver until(String key,long epochSecond){
  long left=epochSecond-java.time.Instant.now().getEpochSecond();
  return left<=0?part(key,"value-none"):part(key,"value-left",literal("time",duration(left)));
 }
 String duration(long seconds){
  long days=seconds/86400,hours=seconds%86400/3600,minutes=Math.max(1,seconds%3600/60);
  if(days>0)return days+template("unit-days")+(hours>0?" "+hours+template("unit-hours"):"");
  if(hours>0)return hours+template("unit-hours")+" "+seconds%3600/60+template("unit-minutes");
  return minutes+template("unit-minutes");
 }
 static TagResolver literal(String key,String value){return Placeholder.component(key,Component.text(value));}
 static TagResolver player(String value){return literal("player",value);}
 static TagResolver account(String value){return literal("account",value);}
 static TagResolver command(String value){return literal("command",value);}
 static TagResolver seconds(long value){return literal("seconds",Long.toString(value));}
 private static TagResolver validationResolver(){return TagResolver.resolver(player("player"),account("account"),command("command"),seconds(1));}
}
