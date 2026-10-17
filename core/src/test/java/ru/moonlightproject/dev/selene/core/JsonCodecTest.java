package ru.moonlightproject.dev.selene.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonCodecTest {
 @Test void secondFactorRequestRoundTripsWithoutLeakingCredentials(){
  AuthRequest request=new AuthRequest("totp_verify","session","VicTim","127.0.0.1","password","captcha",false,true,"123456","ABCD-EFGH");
  AuthRequest decoded=JsonCodec.request(JsonCodec.encode(request));
  assertEquals("123456",decoded.totpCode());
  assertEquals("ABCD-EFGH",decoded.recoveryCode());
  assertFalse(request.toString().contains("password"));
  assertFalse(request.toString().contains("captcha"));
  assertFalse(request.toString().contains("123456"));
  assertFalse(request.toString().contains("ABCD-EFGH"));
 }

 @Test void secondFactorReplyRoundTripsImmutableSecurityState(){
  PremiumTrust trust=new PremiumTrust("2001:db8::1",1_800_000_000L);
  AuthReply reply=new AuthReply("TOTP_SETUP","session","","",Map.of(),List.of(),0,
   "otpauth://totp/Selene%3AVicTim?secret=ABC","ABCD EFGH",List.of("AAAA-BBBB"),true,Map.of("victim",trust));
  AuthReply decoded=JsonCodec.reply(JsonCodec.encode(reply));
  assertEquals(reply.totpUri(),decoded.totpUri());
  assertEquals("ABCD EFGH",decoded.totpSecret());
  assertEquals(List.of("AAAA-BBBB"),decoded.recoveryCodes());
  assertTrue(decoded.totpEnabled());
  assertEquals(trust,decoded.premiumTrust().get("victim"));
  assertThrows(UnsupportedOperationException.class,()->decoded.recoveryCodes().add("leak"));
  assertThrows(UnsupportedOperationException.class,()->decoded.premiumTrust().put("other",trust));
  assertFalse(reply.toString().contains("otpauth"));
  assertFalse(reply.toString().contains("ABCD"));
  assertFalse(reply.toString().contains("AAAA-BBBB"));
 }

 @Test void decoderRejectsWrongSecondFactorTypesAndOversizedValues(){
  assertThrows(IllegalArgumentException.class,()->JsonCodec.request(bytes("{\"totpCode\":6}")));
  assertThrows(IllegalArgumentException.class,()->JsonCodec.reply(bytes("{\"recoveryCodes\":\"code\"}")));
  assertThrows(IllegalArgumentException.class,()->JsonCodec.request(bytes("{\"totpCode\":\""+"1".repeat(65)+"\"}")));
  assertThrows(IllegalArgumentException.class,()->JsonCodec.reply(bytes("{\"totpUri\":\""+"x".repeat(2049)+"\"}")));
 }

 @Test void premiumTrustRejectsHostnamesAndInvalidExpiry(){
  assertThrows(IllegalArgumentException.class,()->new PremiumTrust("localhost",1));
  assertThrows(IllegalArgumentException.class,()->new PremiumTrust("127.0.0.1",-1));
  assertDoesNotThrow(()->new PremiumTrust("127.0.0.1",0));
 }

 private static byte[] bytes(String json){return json.getBytes(StandardCharsets.UTF_8);}
}
