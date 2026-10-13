package ru.moonlightproject.dev.selene.core;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.bridge.client.MoonLightChannel;
class BackendGatewayTest {
  @Test
  void boundsInflightWorkUntilUnderlyingCompletion() {
    var pending = new CompletableFuture<byte[]>();
    MoonLightChannel channel = new MoonLightChannel() {
      public CompletableFuture<byte[]> request(int id, byte[] body, Duration timeout) {
        assertEquals(1397046273, id);
        assertEquals("login", JsonCodec.request(body).op());
        return pending;
      }
      public CompletableFuture<Void> ping(Duration timeout) {
        return CompletableFuture.completedFuture(null);
      }
      public void close() {}
    };
    try (var gateway = new BackendGateway(channel, Duration.ofSeconds(1), 1)) {
      var request = new AuthRequest("login", "s", "Alice", "127.0.0.1", "secret", "", false, false);
      var first = gateway.call(request);
      assertFalse(first.isDone());
      assertThrows(CompletionException.class, () -> gateway.call(request).join());
      pending.complete(JsonCodec.encode(AuthReply.status("AUTHENTICATED", "s")));
      assertEquals("AUTHENTICATED", first.join().status());
      assertEquals("AUTHENTICATED", gateway.call(request).join().status());
    }
  }
}
