package ru.moonlightproject.dev.selene.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;

final class AuthMessages {
 private final Map<String,String> templates;
 private final MiniMessage miniMessage=MiniMessage.builder().strict(true).build();

 AuthMessages(Map<String,String> templates){
  this.templates=Map.copyOf(templates);
  this.templates.forEach((path,template)->{
   try{miniMessage.deserialize(template,validationResolver());}
   catch(RuntimeException error){throw new IllegalArgumentException("Invalid MiniMessage at "+path+": "+error.getMessage(),error);}
  });
 }

 Component component(String path,TagResolver... resolvers){
  String template=templates.get(path);
  if(template==null)throw new IllegalArgumentException("Missing message template: "+path);
  return miniMessage.deserialize(template,resolvers);
 }

 static TagResolver literal(String key,String value){return Placeholder.component(key,Component.text(value));}
 static TagResolver player(String value){return literal("player",value);}
 static TagResolver account(String value){return literal("account",value);}
 static TagResolver command(String value){return literal("command",value);}
 static TagResolver seconds(long value){return literal("seconds",Long.toString(value));}

 private static TagResolver validationResolver(){return TagResolver.resolver(player("player"),account("account"),command("command"),seconds(1));}
}
