package ru.moonlightproject.dev.selene.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import ru.moonlightproject.dev.selene.core.*;

/**
 * {@code /selene} on Velocity. The console may run everything; a player needs the subcommand
 * permission and a finished Selene login, because in offline mode a permission bound to a nickname
 * would otherwise be usable by anyone who joins under that nickname before authenticating.
 */
final class SeleneCommand implements SimpleCommand {
 static final String ACCOUNT_VIEW="selene.command.account.view";
 static final String ACCOUNT_MANAGE="selene.command.account.manage";
 static final String ATTACK="selene.command.attack";
 static final String STATUS="selene.command.status";
 static final String SESSION="selene.command.session";
 static final String LOGOUT="selene.command.logout";
 private static final List<String> NODES=List.of(ACCOUNT_VIEW,ACCOUNT_MANAGE,ATTACK,STATUS,SESSION,LOGOUT);

 /** Proxy state the command reads or changes; implemented by the plugin. */
 interface Operations {
  void status(CommandSource source);
  void session(CommandSource source,String username);
  void logout(CommandSource source,String username);
  void protection(Invocation invocation);
 }

 private final AccountAdminGateway gateway;private final Supplier<VelocityMessages> messages;private final Logger logger;
 private final Operations operations;private final Predicate<Player> authenticated;private final Supplier<Stream<String>> players;private final Predicate<Player> administrator;
 SeleneCommand(AccountAdminGateway gateway,Supplier<VelocityMessages> messages,Logger logger,Operations operations,Predicate<Player> authenticated,Supplier<Stream<String>> players,Predicate<Player> administrator){
  this.gateway=gateway;this.messages=messages;this.logger=logger;this.operations=operations;this.authenticated=authenticated;this.players=players;this.administrator=administrator;
 }
 /** Console, a player listed in command-admins, or a player holding the permission node. */
 private boolean has(CommandSource source,String node){return source instanceof ConsoleCommandSource||source instanceof Player player&&administrator.test(player)||source.hasPermission(node);}

 @Override public void execute(Invocation invocation){
  CommandSource source=invocation.source();String[] args=invocation.arguments();
  String sub=args.length==0?"help":args[0].toLowerCase(Locale.ROOT);
  if(source instanceof Player player&&!authenticated.test(player)){reply(source,"command-login-first");return;}
  switch(sub){
   case "help" -> help(source);
   case "status" -> {if(allowed(source,STATUS))operations.status(source);}
   case "attack" -> {if(allowed(source,ATTACK))operations.protection(invocation);}
   case "session" -> {if(allowed(source,SESSION)){if(args.length==2&&validName(args[1]))operations.session(source,args[1]);else reply(source,"command-usage-session");}}
   case "logout" -> {if(allowed(source,LOGOUT)){if(args.length==2&&validName(args[1]))operations.logout(source,args[1]);else reply(source,"command-usage-logout");}}
   case "account" -> account(invocation);
   default -> help(source);
  }
 }

 private void account(Invocation invocation){
  CommandSource source=invocation.source();
  AdminCommandParser.Parsed parsed=AdminCommandParser.parse(invocation.arguments());
  if(!parsed.valid()){
   if(has(source,ACCOUNT_VIEW)||has(source,ACCOUNT_MANAGE))
    reply(source,parsed.error().equals("username")?"admin-invalid-username":"admin-help");
   else reply(source,"command-no-permission");
   return;
  }
  if(!allowed(source,AdminCommandParser.permission(parsed.request().op())))return;
  long start=System.nanoTime();String actor=source instanceof Player player?player.getUsername():"console";
  gateway.admin(parsed.request()).whenComplete((reply,error)->{
   AdminReply safe=error==null&&reply!=null?reply:new AdminReply("UNAVAILABLE",List.of(),null,0,false);render(source,parsed.request(),safe);
   logger.info(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[*] admin    » actor:{}  {}"),actor.replaceAll("[^A-Za-z0-9_]","?"),AdminCommandParser.audit(parsed.request().op(),parsed.request().username(),safe.status(),java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()));
  });
 }

 private void render(CommandSource source,AdminRequest request,AdminReply reply){
  VelocityMessages m=messages(source);
  if(!reply.status().equals("OK")){source.sendMessage(m.component(AdminCommandParser.messageKey(reply.status()),VelocityMessages.account(request.username())));return;}
  if(request.op().equals("list")){source.sendMessage(m.component("admin-list-header",VelocityMessages.literal("page",Integer.toString(reply.page()))));for(AdminReply.Account account:reply.accounts())source.sendMessage(account(m,"admin-list-row",account));if(reply.hasMore())source.sendMessage(m.component("admin-list-more",VelocityMessages.literal("page",Integer.toString(reply.page()+1))));}
  else if(request.op().equals("info")&&reply.account()!=null)source.sendMessage(account(m,"admin-info",reply.account()));
  else source.sendMessage(m.component("admin-status-ok",VelocityMessages.account(request.username()),VelocityMessages.literal("operation",request.op())));
 }

 /** Lists only the commands this source may run. */
 private void help(CommandSource source){
  VelocityMessages m=messages(source);source.sendMessage(m.component("command-help-header"));
  boolean any=false;
  for(String node:NODES)if(has(source,node)){source.sendMessage(m.component("command-help-"+node.substring("selene.command.".length()).replace('.','-')));any=true;}
  if(!any)source.sendMessage(m.component("command-no-permission"));
 }

 @Override public List<String> suggest(Invocation invocation){
  CommandSource source=invocation.source();String[] args=invocation.arguments();
  if(source instanceof Player player&&!authenticated.test(player))return List.of();
  if(args.length<=1){
   String prefix=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
   return Stream.of("help","status","attack","session","logout","account").filter(s->s.startsWith(prefix)&&(s.equals("help")||may(source,s))).toList();
  }
  String sub=args[0].toLowerCase(Locale.ROOT),last=args[args.length-1].toLowerCase(Locale.ROOT);
  if(args.length==2&&sub.equals("attack")&&may(source,"attack"))return Stream.of("status","confirm").filter(s->s.startsWith(last)).toList();
  if(args.length==2&&(sub.equals("session")||sub.equals("logout"))&&may(source,sub))return players.get().filter(n->n.toLowerCase(Locale.ROOT).startsWith(last)).sorted().limit(50).toList();
  if(args.length==2&&sub.equals("account"))return Stream.of("list","info","delete","reset-password","reset-totp").filter(s->s.startsWith(last)&&has(source,AdminCommandParser.permission(AdminCommandParser.operation(s)))).toList();
  if(args.length==3&&sub.equals("account")&&!args[1].equalsIgnoreCase("list"))return players.get().filter(n->n.toLowerCase(Locale.ROOT).startsWith(last)).sorted().limit(50).toList();
  return List.of();
 }

 private boolean may(CommandSource source,String sub){
  return switch(sub){case "account"->has(source,ACCOUNT_VIEW)||has(source,ACCOUNT_MANAGE);case "attack"->has(source,ATTACK);case "status"->has(source,STATUS);case "session"->has(source,SESSION);case "logout"->has(source,LOGOUT);default->false;};
 }
 private boolean allowed(CommandSource source,String node){
  if(has(source,node))return true;
  reply(source,"command-no-permission");return false;
 }
 private void reply(CommandSource source,String key){source.sendMessage(messages(source).component(key));}
 private VelocityMessages messages(CommandSource source){
  VelocityMessages base=messages.get();
  if(source instanceof Player player){Locale locale=player.getPlayerSettings().getLocale();return base.forLocale(locale==null?null:locale.toLanguageTag());}
  return base;
 }
 private static boolean validName(String name){return name.matches("[A-Za-z0-9_]{3,16}");}
 static Component account(VelocityMessages messages,String key,AdminReply.Account account){return messages.component(key,VelocityMessages.account(account.name()),VelocityMessages.literal("premium",Boolean.toString(account.premium())),VelocityMessages.literal("trusted-ip",account.trustedIp().isEmpty()?"—":account.trustedIp()),VelocityMessages.literal("trusted-until",Long.toString(account.trustedUntil())),VelocityMessages.literal("password-reset",Boolean.toString(account.passwordReset())),VelocityMessages.literal("totp-enabled",Boolean.toString(account.totpEnabled())));}
}
