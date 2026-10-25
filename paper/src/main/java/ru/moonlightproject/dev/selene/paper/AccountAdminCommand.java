package ru.moonlightproject.dev.selene.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.moonlightproject.dev.selene.core.*;

final class AccountAdminCommand implements CommandExecutor {
 static final String RELOAD="selene.command.reload";
 private final JavaPlugin plugin;private final boolean standalone;private final AccountAdminGateway gateway;private final AuthMessages messages;
 private final java.util.function.Predicate<Player> authenticated;private final java.util.function.Consumer<CommandSender> reload;
 AccountAdminCommand(JavaPlugin plugin,boolean standalone,AccountAdminGateway gateway,AuthMessages messages,java.util.function.Predicate<Player> authenticated,java.util.function.Consumer<CommandSender> reload){this.plugin=plugin;this.standalone=standalone;this.gateway=gateway;this.messages=messages;this.authenticated=authenticated;this.reload=reload;}
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
  // A permission alone is not enough: in offline mode it would follow the nickname into an unauthenticated session.
  boolean console=!(sender instanceof Player);boolean signedIn=console||authenticated.test((Player)sender);
  if(args.length==1&&args[0].equalsIgnoreCase("reload")){
   if(console||signedIn&&sender.hasPermission(RELOAD))reload.accept(sender);else sender.sendMessage(messages.component("messages.admin-console-only"));
   return true;
  }
  AdminCommandParser.Parsed preview=AdminCommandParser.parse(args);
  boolean permitted=signedIn&&preview.valid()&&sender.hasPermission(AdminCommandParser.permission(preview.request().op()));
  AdminCommandParser.Access access=AdminCommandParser.access(console,permitted,standalone);
  if(access==AdminCommandParser.Access.PLAYER_DENIED){sender.sendMessage(messages.component("messages.admin-console-only"));return true;}
  if(access==AdminCommandParser.Access.PROXY_GUIDANCE){sender.sendMessage(messages.component("messages.admin-use-velocity"));return true;}
  AdminCommandParser.Parsed parsed=AdminCommandParser.parse(args);if(!parsed.valid()){sender.sendMessage(messages.component("messages."+(parsed.error().equals("username")?"admin-invalid-username":"admin-help")));return true;}
  long start=System.nanoTime();gateway.admin(parsed.request()).whenComplete((reply,error)->Bukkit.getScheduler().runTask(plugin,()->{
   AdminReply safe=error==null&&reply!=null?reply:new AdminReply("UNAVAILABLE",java.util.List.of(),null,0,false);render(sender,parsed.request(),safe);
   plugin.getLogger().info("Account admin: "+AdminCommandParser.audit(parsed.request().op(),parsed.request().username(),safe.status(),java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()));
  }));return true;
 }
 private void render(CommandSender sender,AdminRequest request,AdminReply reply){
  if(!reply.status().equals("OK")){sender.sendMessage(messages.component("messages."+AdminCommandParser.messageKey(reply.status()),AuthMessages.account(request.username())));return;}
  if(request.op().equals("list")){sender.sendMessage(messages.component("messages.admin-list-header",AuthMessages.literal("page",Integer.toString(reply.page()))));for(AdminReply.Account account:reply.accounts())sender.sendMessage(account("messages.admin-list-row",account));if(reply.hasMore())sender.sendMessage(messages.component("messages.admin-list-more",AuthMessages.literal("page",Integer.toString(reply.page()+1))));}
  else if(request.op().equals("info")&&reply.account()!=null)sender.sendMessage(account("messages.admin-info",reply.account()));
  else sender.sendMessage(messages.component("messages.admin-status-ok",AuthMessages.account(request.username()),AuthMessages.literal("operation",request.op())));
 }
 private net.kyori.adventure.text.Component account(String key,AdminReply.Account account){return messages.component(key,AuthMessages.account(account.name()),AuthMessages.literal("premium",Boolean.toString(account.premium())),AuthMessages.literal("trusted-ip",account.trustedIp().isEmpty()?"—":account.trustedIp()),AuthMessages.literal("trusted-until",Long.toString(account.trustedUntil())),AuthMessages.literal("password-reset",Boolean.toString(account.passwordReset())),AuthMessages.literal("totp-enabled",Boolean.toString(account.totpEnabled())));}
}
