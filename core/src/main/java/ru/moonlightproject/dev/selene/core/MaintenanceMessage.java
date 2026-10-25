package ru.moonlightproject.dev.selene.core;

import java.util.List;
import java.util.UUID;

public sealed interface MaintenanceMessage permits MaintenanceMessage.State,MaintenanceMessage.Whitelist,MaintenanceMessage.Ack {
 record Policy(boolean requireTrustedIp,boolean requirePremium,boolean enableCaptcha,boolean verifyCaptcha,
               boolean enablePassword,boolean verifyPassword,boolean enableTotp,boolean verifyTotp,
               boolean elevateSeleneCommands){
  public Policy{if(verifyCaptcha&&!enableCaptcha||verifyPassword&&!enablePassword||verifyTotp&&!enableTotp)
   throw new IllegalArgumentException("Cannot verify a disabled factor");}
 }
 record State(long revision,boolean enabled,Policy policy,String actor,String reason) implements MaintenanceMessage{
  public State{if(revision<0)throw new IllegalArgumentException("Invalid revision");policy=java.util.Objects.requireNonNull(policy);
   actor=bounded(actor,256,"actor");reason=bounded(reason,256,"reason");}
 }
 record Entry(String name,UUID uuid){public Entry{name=bounded(name,16,"name");uuid=java.util.Objects.requireNonNull(uuid);
  if(!name.matches("[A-Za-z0-9_]{3,16}"))throw new IllegalArgumentException("Invalid whitelist name");}}
 record Whitelist(String serverId,long revision,List<Entry> entries) implements MaintenanceMessage{
  public Whitelist{serverId=bounded(serverId,64,"serverId");if(serverId.isBlank()||revision<0)throw new IllegalArgumentException("Invalid whitelist snapshot");
   entries=entries==null?List.of():List.copyOf(entries);if(entries.size()>10_000)throw new IllegalArgumentException("Whitelist is too large");}
 }
 record Ack(String serverId,long revision) implements MaintenanceMessage{
  public Ack{serverId=bounded(serverId,64,"serverId");if(serverId.isBlank()||revision<0)throw new IllegalArgumentException("Invalid acknowledgement");}
 }
 private static String bounded(String value,int maximum,String field){String safe=value==null?"":value;
  if(safe.length()>maximum)throw new IllegalArgumentException(field+" is too long");return safe;}
}
