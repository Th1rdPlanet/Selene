package ru.moonlightproject.dev.selene.core;

import org.junit.jupiter.api.Test;
import ru.moonlightproject.bridge.client.MoonLightChannel;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AccountAdminGatewayTest {
 @Test void adminCodecRoundTripsWithoutCredentialFields(){
  AdminRequest request=new AdminRequest("info","Alice",1,50);
  assertEquals(request,JsonCodec.adminRequest(JsonCodec.encode(request)));
  AdminReply reply=new AdminReply("OK",List.of(),new AdminReply.Account("alice",false,"127.0.0.1",123,true),1,false);
  AdminReply decoded=JsonCodec.adminReply(JsonCodec.encode(reply));
  assertEquals("alice",decoded.account().name());
  assertFalse(new String(JsonCodec.encode(reply),java.nio.charset.StandardCharsets.UTF_8).contains("hash"));
 }

 @Test void backendUsesIndependentAdminMethodOnSharedChannel(){
  MoonLightChannel channel=new MoonLightChannel(){
   public CompletableFuture<byte[]> request(int method,byte[] body,Duration timeout){assertEquals(1397046274,method);assertEquals("list",JsonCodec.adminRequest(body).op());return CompletableFuture.completedFuture(JsonCodec.encode(new AdminReply("OK",List.of(),null,1,false)));}
   public CompletableFuture<Void> ping(Duration timeout){return CompletableFuture.completedFuture(null);}public void close(){}
  };
  try(BackendGateway gateway=new BackendGateway(channel,Duration.ofSeconds(1),2)){assertEquals("OK",gateway.admin(new AdminRequest("list","",1,50)).join().status());}
 }
}
