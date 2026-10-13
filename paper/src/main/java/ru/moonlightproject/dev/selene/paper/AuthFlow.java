package ru.moonlightproject.dev.selene.paper;

import ru.moonlightproject.dev.selene.core.AuthReply;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class AuthFlow {
 private final Map<UUID,AuthSession> sessions=new HashMap<>();

 synchronized AuthSession open(ConnectionKey key,long deadlineMillis){
  AuthSession session=new AuthSession(key,deadlineMillis);
  AuthSession previous=sessions.put(key.playerId(),session);
  if(previous!=null)closeInternal(previous);
  return session;
 }

 synchronized AuthSession current(UUID playerId){return sessions.get(playerId);}

 synchronized boolean bindToken(AuthSession session,String token){
  if(!isCurrent(session)||session.stage!=AuthStage.WAITING_FOR_SESSION||token==null||token.isBlank())return false;
  session.sessionId=token;beginRequest(session,AuthStage.WAITING_FOR_SESSION);return true;
 }

 synchronized boolean submitCaptcha(AuthSession session,String answer){
  if(!AuthInputValidation.captcha(answer).valid()||session.stage!=AuthStage.WAITING_FOR_CAPTCHA)return false;
  return beginRequest(session,AuthStage.WAITING_FOR_CAPTCHA);
 }

 synchronized boolean submitLogin(AuthSession session,String password){
  if(!AuthInputValidation.password(password,8,256).valid()||session.stage!=AuthStage.WAITING_FOR_LOGIN)return false;
  return beginRequest(session,AuthStage.WAITING_FOR_LOGIN);
 }

 synchronized boolean submitRegistration(AuthSession session,String password,String confirmation){
  if(!AuthInputValidation.registration(password,confirmation,8,256).valid()||session.stage!=AuthStage.WAITING_FOR_REGISTRATION)return false;
  return beginRequest(session,AuthStage.WAITING_FOR_REGISTRATION);
 }

 synchronized boolean accept(AuthSession session,String requestSessionId,AuthReply reply,long nowMillis){
  if(!isCurrent(session)||session.stage==AuthStage.CLOSED||nowMillis>=session.deadlineMillis){
   if(isCurrent(session)&&nowMillis>=session.deadlineMillis)close(session);
   return false;
  }
  if(!session.busy||!session.sessionId.equals(requestSessionId)||!session.sessionId.equals(reply.session()))return false;
  AuthStage next=switch(reply.status()){
   case "CAPTCHA","CAPTCHA_INVALID" -> AuthStage.WAITING_FOR_CAPTCHA;
   case "LOGIN","INVALID_CREDENTIALS","ACCOUNT_EXISTS" -> AuthStage.WAITING_FOR_LOGIN;
   case "REGISTER" -> AuthStage.WAITING_FOR_REGISTRATION;
   case "AUTHENTICATED" -> AuthStage.AUTHENTICATED;
   case "EXPIRED" -> AuthStage.CLOSED;
   case "INVALID_REQUEST","UNAVAILABLE","RATE_LIMITED","BUSY" -> session.resumeStage;
   default -> null;
  };
  if(next==null)return false;
  session.busy=false;session.stage=next;
  if(next==AuthStage.CLOSED)sessions.remove(session.key.playerId(),session);
  return true;
 }

 synchronized void close(AuthSession session){if(isCurrent(session))sessions.remove(session.key.playerId(),session);closeInternal(session);}

 synchronized boolean isCurrent(AuthSession session){return session!=null&&sessions.get(session.key.playerId())==session&&session.stage!=AuthStage.CLOSED;}

 private boolean beginRequest(AuthSession session,AuthStage resumeStage){
  if(!isCurrent(session)||session.busy)return false;
  session.resumeStage=resumeStage;session.busy=true;session.stage=AuthStage.SUBMITTING;return true;
 }

 private static void closeInternal(AuthSession session){session.busy=false;session.stage=AuthStage.CLOSED;session.sessionId="";}
}
