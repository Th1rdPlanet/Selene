package ru.moonlightproject.dev.selene.velocity;

import java.util.*;
import ru.moonlightproject.dev.selene.core.MaintenanceMessage;

final class WhitelistRegistry {
 private record Snapshot(long revision,Set<String> names){}
 private final Map<String,Snapshot> servers=new HashMap<>();
 synchronized boolean replace(String serverId,long revision,List<MaintenanceMessage.Entry> entries){
  Snapshot current=servers.get(serverId);if(current!=null&&revision<=current.revision())return false;
  Set<String> names=new HashSet<>();for(var entry:entries)names.add(entry.name().toLowerCase(Locale.ROOT));
  servers.put(serverId,new Snapshot(revision,Set.copyOf(names)));return true;
 }
 synchronized void remove(String serverId){servers.remove(serverId);}
 synchronized boolean contains(String username){return username!=null&&servers.values().stream().anyMatch(s->s.names().contains(username.toLowerCase(Locale.ROOT)));}
 synchronized int unionSize(){Set<String> union=new HashSet<>();servers.values().forEach(s->union.addAll(s.names()));return union.size();}
 synchronized int serverCount(){return servers.size();}
}
