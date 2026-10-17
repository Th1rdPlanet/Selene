package ru.moonlightproject.dev.selene.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import org.slf4j.Logger;
import ru.moonlightproject.dev.selene.core.*;

final class AccountAdminCommand implements SimpleCommand {
 private final java.util.function.Consumer<Invocation> protection;
 private final AccountAdminGateway gateway;private final VelocityMessages messages;private final Logger logger;
 AccountAdminCommand(AccountAdminGateway gateway,VelocityMessages messages,Logger logger){this(gateway,messages,logger, invocation -> invocation.source().sendMessage(messages.component("admin-help")));}
 AccountAdminCommand(AccountAdminGateway gateway,VelocityMessages messages,Logger logger,java.util.function.Consumer<Invocation> protection){this.gateway=gateway;this.messages=messages;this.logger=logger;this.protection=protection;}
 @Override public void execute(Invocation invocation){
  if(!(invocation.source() instanceof ConsoleCommandSource)){invocation.source().sendMessage(messages.component("admin-console-only"));return;}
  if(invocation.arguments().length > 0 && invocation.arguments()[0].equalsIgnoreCase("attack")){protection.accept(invocation);return;}
  AdminCommandParser.Parsed parsed=AdminCommandParser.parse(invocation.arguments());if(!parsed.valid()){invocation.source().sendMessage(messages.component(parsed.error().equals("username")?"admin-invalid-username":"admin-help"));return;}
  long start=System.nanoTime();gateway.admin(parsed.request()).whenComplete((reply,error)->{
   AdminReply safe=error==null&&reply!=null?reply:new AdminReply("UNAVAILABLE",java.util.List.of(),null,0,false);render(invocation,safe);
   logger.info("Selene account admin: {}",AdminCommandParser.audit(parsed.request().op(),parsed.request().username(),safe.status(),java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()));
  });
 }
 private void render(Invocation invocation,AdminReply reply){
  if(!reply.status().equals("OK")){invocation.source().sendMessage(messages.component(AdminCommandParser.messageKey(reply.status()),VelocityMessages.account("")));return;}
  AdminRequest request=AdminCommandParser.parse(invocation.arguments()).request();
  if(request.op().equals("list")){invocation.source().sendMessage(messages.component("admin-list-header",VelocityMessages.literal("page",Integer.toString(reply.page()))));for(AdminReply.Account account:reply.accounts())invocation.source().sendMessage(account(messages,"admin-list-row",account));if(reply.hasMore())invocation.source().sendMessage(messages.component("admin-list-more",VelocityMessages.literal("page",Integer.toString(reply.page()+1))));}
  else if(request.op().equals("info")&&reply.account()!=null)invocation.source().sendMessage(account(messages,"admin-info",reply.account()));
  else invocation.source().sendMessage(messages.component("admin-status-ok",VelocityMessages.account(request.username()),VelocityMessages.literal("operation",request.op())));
 }
 static net.kyori.adventure.text.Component account(VelocityMessages messages,String key,AdminReply.Account account){return messages.component(key,VelocityMessages.account(account.name()),VelocityMessages.literal("premium",Boolean.toString(account.premium())),VelocityMessages.literal("trusted-ip",account.trustedIp().isEmpty()?"—":account.trustedIp()),VelocityMessages.literal("trusted-until",Long.toString(account.trustedUntil())),VelocityMessages.literal("password-reset",Boolean.toString(account.passwordReset())),VelocityMessages.literal("totp-enabled",Boolean.toString(account.totpEnabled())));}
}
