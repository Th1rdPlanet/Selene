package ru.moonlightproject.dev.selene.core;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import ru.moonlightproject.bridge.client.MoonLightChannel;

/** Reconnects failed channels in the background and never replays an interrupted request. */
final class SupervisedChannel implements MoonLightChannel {
  @FunctionalInterface interface Connector { MoonLightChannel open() throws IOException; }

  private final Connector connector;
  private final AtomicReference<MoonLightChannel> active;
  private final AtomicBoolean reconnecting = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon().name("selene-backend-reconnect").factory());
  private volatile long delayMillis = 100;

  SupervisedChannel(MoonLightChannel initial, Connector connector) {
    this.active = new AtomicReference<>(initial);
    this.connector = connector;
  }

  @Override public CompletableFuture<byte[]> request(int methodId, byte[] body, Duration deadline) {
    MoonLightChannel channel = active.get();
    if (channel == null) {
      scheduleReconnect(0);
      return CompletableFuture.failedFuture(new IOException("Backend connection is unavailable"));
    }
    CompletableFuture<byte[]> result;
    try {
      result = channel.request(methodId, body, deadline);
    } catch (RuntimeException error) {
      invalidate(channel);
      return CompletableFuture.failedFuture(error);
    }
    result.whenComplete((ignored, error) -> { if (error != null) invalidate(channel); });
    return result;
  }

  @Override public CompletableFuture<Void> ping(Duration timeout) {
    MoonLightChannel channel = active.get();
    if (channel == null) return CompletableFuture.failedFuture(new IOException("Backend connection is unavailable"));
    CompletableFuture<Void> result = channel.ping(timeout);
    result.whenComplete((ignored, error) -> { if (error != null) invalidate(channel); });
    return result;
  }

  private void invalidate(MoonLightChannel channel) {
    if (active.compareAndSet(channel, null)) {
      closeQuietly(channel);
      scheduleReconnect(delayMillis);
    }
  }

  private void scheduleReconnect(long delay) {
    if (closed.get() || !reconnecting.compareAndSet(false, true)) return;
    scheduler.schedule(this::reconnect, delay, TimeUnit.MILLISECONDS);
  }

  private void reconnect() {
    if (closed.get()) { reconnecting.set(false); return; }
    try {
      MoonLightChannel replacement = connector.open();
      if (closed.get()) closeQuietly(replacement);
      else {
        active.set(replacement);
        delayMillis = 100;
      }
      reconnecting.set(false);
    } catch (IOException | RuntimeException error) {
      long retry = delayMillis;
      delayMillis = Math.min(5_000, delayMillis * 2);
      reconnecting.set(false);
      scheduleReconnect(retry);
    }
  }

  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    scheduler.shutdownNow();
    closeQuietly(active.getAndSet(null));
  }

  private static void closeQuietly(MoonLightChannel channel) {
    if (channel == null) return;
    try { channel.close(); } catch (Exception ignored) { }
  }
}
