package ru.moonlightproject.dev.selene.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.moonlightproject.dev.selene.core.*;

final class AccountAdminCommand implements CommandExecutor {
 private final JavaPlugin plugin;private final boolean standalone;private final AccountAdminGateway gateway;private final AuthMessages messages;
 AccountAdminCommand(JavaPlugin plugin,boolean standalone,AccountAdminGateway gateway,AuthMessages messages){this.plugin=plugin;this.standalone=standalone;this.gateway=gateway;this.messages=messages;}
 @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
  AdminCommandParser.Access access=AdminCommandParser.access(!(sender instanceof Player),standalone);
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
