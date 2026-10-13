package ru.moonlightproject.dev.selene.paper;
import java.util.IdentityHashMap;
/** Identity, not UUID: a reconnect cannot inherit a previous channel's permission. */
final class ConnectionGate<T> {
 private final IdentityHashMap<T,Boolean> connections=new IdentityHashMap<>();
 synchronized void open(T connection) { connections.put(connection,false); }
 synchronized boolean authenticate(T connection) { if(!connections.containsKey(connection)) return false; connections.put(connection,true); return true; }
 synchronized boolean authenticated(T connection) { return Boolean.TRUE.equals(connections.get(connection)); }
 synchronized void close(T connection) { connections.remove(connection); }
}
