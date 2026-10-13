package ru.moonlightproject.dev.selene.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.bridge.client.MoonLightChannel;

class SupervisedChannelTest {
  @Test
  void failedRequestIsNotReplayedAndLaterRequestsUseReplacement() throws Exception {
    AtomicInteger opens = new AtomicInteger();
    AtomicInteger failedCalls = new AtomicInteger();
    MoonLightChannel failed = channel(() -> {
      failedCalls.incrementAndGet();
      return CompletableFuture.failedFuture(new IllegalStateException("disconnected"));
    });
    MoonLightChannel recovered = channel(() -> CompletableFuture.completedFuture(new byte[] {7}));

    try (var supervised = new SupervisedChannel(failed, () -> {
      opens.incrementAndGet();
      return recovered;
    })) {
      assertTrue(supervised.request(1, new byte[0], Duration.ofSeconds(1)).isCompletedExceptionally());
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (opens.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);

      assertArrayEquals(new byte[] {7}, supervised.request(1, new byte[0], Duration.ofSeconds(1)).join());
      assertEquals(1, failedCalls.get());
      assertEquals(1, opens.get());
    }
  }

  private static MoonLightChannel channel(java.util.function.Supplier<CompletableFuture<byte[]>> request) {
    return new MoonLightChannel() {
      @Override public CompletableFuture<byte[]> request(int id, byte[] body, Duration timeout) {
        return request.get();
      }
      @Override public CompletableFuture<Void> ping(Duration timeout) {
        return CompletableFuture.completedFuture(null);
      }
      @Override public void close() { }
    };
  }
}
