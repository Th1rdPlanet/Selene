package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.AuthReply;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SecureInputTest {
 @Test void captchaChatIsCapturedAndValidatedLocally(){
  AuthFlow flow=new AuthFlow();AuthSession session=captcha(flow);SecureChatInput input=new SecureChatInput();
  assertTrue(input.shouldCapture(UUID.randomUUID(),session));
  assertEquals("captcha-format",input.accept(UUID.randomUUID(),session,"12a").messageKey());
  SecureChatInput.Result result=input.accept(UUID.randomUUID(),session,"01234");
  assertEquals(SecureChatInput.Action.CAPTCHA,result.action());assertEquals("01234",result.secret());
 }

 @Test void stalePromptCannotSubmitIntoReplacementSession(){
  UUID player=UUID.randomUUID();AuthFlow flow=new AuthFlow();AuthSession old=login(flow,player);SecureChatInput input=new SecureChatInput();
  assertTrue(input.beginLogin(player,old));
  AuthSession replacement=login(flow,player);
  assertEquals(SecureChatInput.Action.NONE,input.accept(player,replacement,"sentinel-password").action());
 }

 @Test void registrationFallbackCollectsPasswordAndConfirmationInTwoSteps(){
  UUID player=UUID.randomUUID();AuthFlow flow=new AuthFlow();AuthSession session=registration(flow,player);SecureChatInput input=new SecureChatInput();
  assertTrue(input.beginRegistration(player,session));
  assertEquals(SecureChatInput.Action.AWAIT_CONFIRMATION,input.accept(player,session,"password-one").action());
  SecureChatInput.Result result=input.accept(player,session,"password-one");
  assertEquals(SecureChatInput.Action.REGISTER,result.action());assertEquals("password-one",result.secret());assertEquals("password-one",result.confirmation());
 }

 @Test void secureInputHasNoDialogApiLinkage(){
  String bytes=classBytes(SecureChatInput.class);
  assertFalse(bytes.contains("io/papermc/paper/dialog"));
  assertFalse(bytes.contains("registry/data/dialog"));
 }

 @Test void redactionNeverEmitsPasswordOrCaptcha(){
  String password="sentinel-password",captcha="73915";
  String captured=SensitiveCommandRedactor.redact("/login "+password)+"\n"+SensitiveCommandRedactor.redact("/register "+password+" "+password)+"\n"+SensitiveCommandRedactor.redact("captcha "+captcha);
  assertFalse(captured.contains(password));assertFalse(captured.contains(captcha));assertTrue(captured.contains("<redacted>"));
 }

 @Test void totpAndRecoveryAreCapturedOnlyInTotpStage(){
  UUID player=UUID.randomUUID();AuthFlow flow=new AuthFlow();AuthSession session=totp(flow,player);SecureChatInput input=new SecureChatInput();
  assertTrue(input.shouldCapture(player,session));
  assertEquals(SecureChatInput.Action.TOTP,input.accept(player,session,"012345").action());
  assertEquals(SecureChatInput.Action.RECOVERY,input.accept(player,session,"23456-789AB-CDEFG-HJKLM").action());
  assertEquals("totp-format",input.accept(player,session,"password").messageKey());
 }

 @Test void redactionCoversTotpAndRecoveryCommands(){
  assertEquals("/totp <redacted>",SensitiveCommandRedactor.redact("/totp 012345"));
  assertEquals("/recovery <redacted>",SensitiveCommandRedactor.redact("/recovery 23456-789AB-CDEFG-HJKLM"));
 }

 private static AuthSession captcha(AuthFlow flow){AuthSession session=flow.open(new ConnectionKey(UUID.randomUUID(),new Object()),System.currentTimeMillis()+10_000);flow.bindToken(session,"token");flow.accept(session,"token",AuthReply.status("CAPTCHA","token"),System.currentTimeMillis());return session;}
 private static AuthSession login(AuthFlow flow,UUID player){AuthSession session=flow.open(new ConnectionKey(player,new Object()),System.currentTimeMillis()+10_000);flow.bindToken(session,"token");flow.accept(session,"token",AuthReply.status("LOGIN","token"),System.currentTimeMillis());return session;}
 private static AuthSession registration(AuthFlow flow,UUID player){AuthSession session=flow.open(new ConnectionKey(player,new Object()),System.currentTimeMillis()+10_000);flow.bindToken(session,"token");flow.accept(session,"token",AuthReply.status("REGISTER","token"),System.currentTimeMillis());return session;}
 private static AuthSession totp(AuthFlow flow,UUID player){AuthSession session=flow.open(new ConnectionKey(player,new Object()),System.currentTimeMillis()+10_000);flow.bindToken(session,"token");flow.accept(session,"token",AuthReply.status("LOGIN","token"),System.currentTimeMillis());flow.submitLogin(session,"password-one");flow.accept(session,"token",AuthReply.status("TOTP_REQUIRED","token"),System.currentTimeMillis());return session;}
 private static String classBytes(Class<?> type){try(InputStream in=type.getResourceAsStream(type.getSimpleName()+".class");ByteArrayOutputStream out=new ByteArrayOutputStream()){assertNotNull(in);in.transferTo(out);return out.toString(StandardCharsets.ISO_8859_1);}catch(Exception e){throw new AssertionError(e);}}
}
