package ru.moonlightproject.dev.selene.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;
import java.util.Set;

final class VelocityMessages {
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

 static TagResolver literal(String key,String value){return Placeholder.component(key,Component.text(value));}
 static TagResolver player(String value){return literal("player",value);}
 static TagResolver account(String value){return literal("account",value);}
 static TagResolver command(String value){return literal("command",value);}
 static TagResolver seconds(long value){return literal("seconds",Long.toString(value));}
 private static TagResolver validationResolver(){return TagResolver.resolver(player("player"),account("account"),command("command"),seconds(1));}
}
