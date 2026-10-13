package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthInputValidationTest {
 @Test void acceptsPasswordsWithinConfiguredBounds(){
  assertTrue(AuthInputValidation.password("12345678",8,256).valid());
  assertTrue(AuthInputValidation.password("x".repeat(256),8,256).valid());
 }

 @Test void reportsPasswordsOutsideConfiguredBounds(){
  assertEquals("password-too-short",AuthInputValidation.password("1234567",8,256).messageKey());
  assertEquals("password-too-long",AuthInputValidation.password("x".repeat(257),8,256).messageKey());
 }

 @Test void reportsRegistrationConfirmationMismatch(){
  assertEquals("password-mismatch",AuthInputValidation.registration("12345678","87654321",8,256).messageKey());
 }

 @Test void captchaIsExactlyFiveAsciiDigits(){
  assertTrue(AuthInputValidation.captcha("01234").valid());
  for(String value:new String[]{"1234","123456","12a45","１２３４５",""})
   assertEquals("captcha-format",AuthInputValidation.captcha(value).messageKey(),value);
 }

 @Test void expectedBackendStatusesMapToSafeUserMessages(){
  assertEquals("INVALID_REQUEST",AuthInputValidation.messageKeyForStatus("INVALID_REQUEST"));
  assertEquals("EXPIRED",AuthInputValidation.messageKeyForStatus("EXPIRED"));
  assertEquals("UNAVAILABLE",AuthInputValidation.messageKeyForStatus("UNAVAILABLE"));
  assertNull(AuthInputValidation.messageKeyForStatus("CORRUPT_PROTOCOL"));
 }
}
