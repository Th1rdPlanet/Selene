package ru.moonlightproject.dev.selene.paper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class SecureChatInput {
 enum Action {NONE,CAPTCHA,LOGIN,AWAIT_CONFIRMATION,REGISTER,TOTP,RECOVERY}
 record Result(Action action,String secret,String confirmation,String messageKey){
  static Result none(){return new Result(Action.NONE,"","","");}
  static Result error(String key){return new Result(Action.NONE,"","",key);}
 }
 private enum Mode {LOGIN,REGISTER_PASSWORD,REGISTER_CONFIRMATION}
 private record Pending(AuthSession session,String sessionId,Mode mode,String firstPassword){}
 private final Map<UUID,Pending> pending=new ConcurrentHashMap<>();

 boolean beginLogin(UUID playerId,AuthSession session){if(session.view().stage()!=AuthStage.WAITING_FOR_LOGIN)return false;pending.put(playerId,new Pending(session,session.view().sessionId(),Mode.LOGIN,""));return true;}
 boolean beginRegistration(UUID playerId,AuthSession session){if(session.view().stage()!=AuthStage.WAITING_FOR_REGISTRATION)return false;pending.put(playerId,new Pending(session,session.view().sessionId(),Mode.REGISTER_PASSWORD,""));return true;}
 boolean shouldCapture(UUID playerId,AuthSession session){return Set.of(AuthStage.WAITING_FOR_CAPTCHA,AuthStage.WAITING_FOR_TOTP).contains(session.view().stage())||matches(session,pending.get(playerId));}
 void clear(UUID playerId){pending.remove(playerId);}

 Result accept(UUID playerId,AuthSession session,String text){
  if(session.view().stage()==AuthStage.WAITING_FOR_CAPTCHA){AuthInputValidation.ValidationResult validation=AuthInputValidation.captcha(text);return validation.valid()?new Result(Action.CAPTCHA,text,"",""):Result.error(validation.messageKey());}
  if(session.view().stage()==AuthStage.WAITING_FOR_TOTP){
   if(AuthInputValidation.totp(text).valid())return new Result(Action.TOTP,text,"","");
   if(AuthInputValidation.recovery(text).valid())return new Result(Action.RECOVERY,text,"","");
   return Result.error("totp-format");
  }
  Pending current=pending.get(playerId);if(!matches(session,current)){pending.remove(playerId);return Result.none();}
  if(current.mode==Mode.LOGIN){AuthInputValidation.ValidationResult validation=AuthInputValidation.password(text,8,256);if(!validation.valid())return Result.error(validation.messageKey());pending.remove(playerId);return new Result(Action.LOGIN,text,"","");}
  if(current.mode==Mode.REGISTER_PASSWORD){AuthInputValidation.ValidationResult validation=AuthInputValidation.password(text,8,256);if(!validation.valid())return Result.error(validation.messageKey());pending.put(playerId,new Pending(session,current.sessionId,Mode.REGISTER_CONFIRMATION,text));return new Result(Action.AWAIT_CONFIRMATION,"","","");}
  AuthInputValidation.ValidationResult validation=AuthInputValidation.registration(current.firstPassword,text,8,256);pending.remove(playerId);return validation.valid()?new Result(Action.REGISTER,current.firstPassword,text,""):Result.error(validation.messageKey());
 }

 private static boolean matches(AuthSession session,Pending current){return current!=null&&current.session==session&&current.sessionId.equals(session.view().sessionId())&&!current.sessionId.isEmpty();}
}
