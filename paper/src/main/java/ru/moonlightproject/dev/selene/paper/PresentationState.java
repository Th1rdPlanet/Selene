package ru.moonlightproject.dev.selene.paper;

final class PresentationState {
 private PresentationState(){}
 static String key(AuthViewState state){
  if(state.busy()||state.stage()==AuthStage.SUBMITTING)return "processing";
  return switch(state.stage()){
   case WAITING_FOR_SESSION -> "connecting";
   case WAITING_FOR_CAPTCHA -> "captcha";
   case WAITING_FOR_LOGIN -> "login";
   case WAITING_FOR_REGISTRATION -> "register";
   default -> "processing";
  };
 }
}
