package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PresentationStateTest {
 @Test void selectsAConfigurableScreenForEveryVisibleState(){
  assertEquals("connecting",PresentationState.key(new AuthViewState(AuthStage.WAITING_FOR_SESSION,"",false,1)));
  assertEquals("captcha",PresentationState.key(new AuthViewState(AuthStage.WAITING_FOR_CAPTCHA,"id",false,1)));
  assertEquals("login",PresentationState.key(new AuthViewState(AuthStage.WAITING_FOR_LOGIN,"id",false,1)));
  assertEquals("register",PresentationState.key(new AuthViewState(AuthStage.WAITING_FOR_REGISTRATION,"id",false,1)));
  assertEquals("processing",PresentationState.key(new AuthViewState(AuthStage.WAITING_FOR_LOGIN,"id",true,1)));
  assertEquals("processing",PresentationState.key(new AuthViewState(AuthStage.SUBMITTING,"id",false,1)));
 }
}
