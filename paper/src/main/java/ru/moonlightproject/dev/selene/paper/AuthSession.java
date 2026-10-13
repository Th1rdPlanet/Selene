package ru.moonlightproject.dev.selene.paper;

import java.util.Objects;
import java.util.UUID;

final class ConnectionKey {
 private final UUID playerId;
 private final Object connection;
 ConnectionKey(UUID playerId,Object connection){this.playerId=Objects.requireNonNull(playerId);this.connection=Objects.requireNonNull(connection);}
 UUID playerId(){return playerId;}
 Object connection(){return connection;}
 boolean sameConnection(ConnectionKey other){return other!=null&&playerId.equals(other.playerId)&&connection==other.connection;}
}

record AuthViewState(AuthStage stage,String sessionId,boolean busy,long deadlineMillis){}

final class AuthSession {
 final ConnectionKey key;
 final long deadlineMillis;
 String sessionId="";
 AuthStage stage=AuthStage.WAITING_FOR_SESSION;
 AuthStage resumeStage=AuthStage.WAITING_FOR_SESSION;
 boolean busy;

 AuthSession(ConnectionKey key,long deadlineMillis){this.key=key;this.deadlineMillis=deadlineMillis;}
 AuthViewState view(){return new AuthViewState(stage,sessionId,busy,deadlineMillis);}
}
