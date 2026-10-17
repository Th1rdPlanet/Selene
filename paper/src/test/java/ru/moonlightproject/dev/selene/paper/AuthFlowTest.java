package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.AuthReply;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthFlowTest {
 private static final long NOW=1_000;

 @Test void proxySessionWaitsForAndBindsToken(){
  AuthFlow flow=new AuthFlow();
  AuthSession session=flow.open(new ConnectionKey(UUID.randomUUID(),new Object()),NOW+10_000);
  assertEquals(AuthStage.WAITING_FOR_SESSION,session.view().stage());
  assertTrue(flow.bindToken(session,"token"));
  assertEquals("token",session.view().sessionId());
  assertEquals(AuthStage.SUBMITTING,session.view().stage());
 }

 @Test void repliesDriveCaptchaLoginAndRegistrationStages(){
  AuthFlow flow=new AuthFlow();AuthSession captcha=bound(flow);
  assertTrue(flow.accept(captcha,"token",AuthReply.status("CAPTCHA","token"),NOW));
  assertEquals(AuthStage.WAITING_FOR_CAPTCHA,captcha.view().stage());
  assertTrue(flow.submitCaptcha(captcha,"12345"));
  assertTrue(flow.accept(captcha,"token",AuthReply.status("LOGIN","token"),NOW));
  assertEquals(AuthStage.WAITING_FOR_LOGIN,captcha.view().stage());

  AuthSession registration=bound(flow);
  assertTrue(flow.accept(registration,"token",AuthReply.status("REGISTER","token"),NOW));
  assertEquals(AuthStage.WAITING_FOR_REGISTRATION,registration.view().stage());
 }

 @Test void busySessionSuppressesDuplicateSubmission(){
  AuthFlow flow=new AuthFlow();AuthSession session=atLogin(flow);
  assertTrue(flow.submitLogin(session,"12345678"));
  assertFalse(flow.submitLogin(session,"12345678"));
 }

 @Test void invalidCaptchaReturnsToCaptchaInput(){
  AuthFlow flow=new AuthFlow();AuthSession session=bound(flow);
  flow.accept(session,"token",AuthReply.status("CAPTCHA","token"),NOW);
  assertTrue(flow.submitCaptcha(session,"12345"));
  assertTrue(flow.accept(session,"token",AuthReply.status("CAPTCHA_INVALID","token"),NOW));
  assertEquals(AuthStage.WAITING_FOR_CAPTCHA,session.view().stage());
  assertFalse(session.view().busy());
 }

 @Test void staleCallbackAndWrongSessionTokenAreRejected(){
  AuthFlow flow=new AuthFlow();AuthSession session=atLogin(flow);
  flow.submitLogin(session,"12345678");
  assertFalse(flow.accept(session,"other",AuthReply.status("AUTHENTICATED","other"),NOW));
  assertEquals(AuthStage.SUBMITTING,session.view().stage());
 }

 @Test void expiredSessionClosesInsteadOfAcceptingReply(){
  AuthFlow flow=new AuthFlow();AuthSession session=bound(flow);
  assertFalse(flow.accept(session,"token",AuthReply.status("LOGIN","token"),NOW+20_000));
  assertEquals(AuthStage.CLOSED,session.view().stage());
 }

 @Test void authenticatedReplyCompletesTheFlow(){
  AuthFlow flow=new AuthFlow();AuthSession session=atLogin(flow);flow.submitLogin(session,"12345678");
  assertTrue(flow.accept(session,"token",AuthReply.status("AUTHENTICATED","token"),NOW));
  assertEquals(AuthStage.AUTHENTICATED,session.view().stage());
 }

 @Test void passwordSuccessCanRequireTotpBeforeAuthentication(){
  AuthFlow flow=new AuthFlow();AuthSession session=atLogin(flow);
  assertTrue(flow.submitLogin(session,"12345678"));
  assertTrue(flow.accept(session,"token",AuthReply.status("TOTP_REQUIRED","token"),NOW));
  assertEquals(AuthStage.WAITING_FOR_TOTP,session.view().stage());
  assertTrue(flow.submitTotp(session,"123456"));
  assertTrue(flow.accept(session,"token",AuthReply.status("AUTHENTICATED","token"),NOW));
  assertEquals(AuthStage.AUTHENTICATED,session.view().stage());
 }

 @Test void invalidTotpReturnsToSecondFactorInput(){
  AuthFlow flow=new AuthFlow();AuthSession session=atTotp(flow);
  assertTrue(flow.submitTotp(session,"123456"));
  assertTrue(flow.accept(session,"token",AuthReply.status("TOTP_INVALID","token"),NOW));
  assertEquals(AuthStage.WAITING_FOR_TOTP,session.view().stage());
  assertFalse(session.view().busy());
 }

 @Test void captchaSubmissionCannotUnexpectedlyEnterTotpStage(){
  AuthFlow flow=new AuthFlow();AuthSession session=bound(flow);
  assertTrue(flow.accept(session,"token",AuthReply.status("CAPTCHA","token"),NOW));
  assertTrue(flow.submitCaptcha(session,"12345"));
  assertFalse(flow.accept(session,"token",AuthReply.status("TOTP_REQUIRED","token"),NOW));
  assertEquals(AuthStage.SUBMITTING,session.view().stage());
 }

 @Test void malformedTotpIsRejectedBeforeSubmission(){
  AuthFlow flow=new AuthFlow();AuthSession session=atTotp(flow);
  assertFalse(flow.submitTotp(session,"12345"));
  assertFalse(flow.submitTotp(session,"12345a"));
  assertEquals(AuthStage.WAITING_FOR_TOTP,session.view().stage());
 }

 @Test void reconnectReplacesSessionAndRejectsOldCompletion(){
  AuthFlow flow=new AuthFlow();UUID playerId=UUID.randomUUID();
  AuthSession old=flow.open(new ConnectionKey(playerId,new Object()),NOW+10_000);flow.bindToken(old,"old");
  AuthSession replacement=flow.open(new ConnectionKey(playerId,new Object()),NOW+10_000);flow.bindToken(replacement,"new");
  assertEquals(AuthStage.CLOSED,old.view().stage());
  assertFalse(flow.accept(old,"old",AuthReply.status("AUTHENTICATED","old"),NOW));
  assertSame(replacement,flow.current(playerId));
 }

 private static AuthSession bound(AuthFlow flow){AuthSession session=flow.open(new ConnectionKey(UUID.randomUUID(),new Object()),NOW+10_000);flow.bindToken(session,"token");return session;}
 private static AuthSession atLogin(AuthFlow flow){AuthSession session=bound(flow);flow.accept(session,"token",AuthReply.status("LOGIN","token"),NOW);return session;}
 private static AuthSession atTotp(AuthFlow flow){AuthSession session=atLogin(flow);flow.submitLogin(session,"12345678");flow.accept(session,"token",AuthReply.status("TOTP_REQUIRED","token"),NOW);return session;}
}
