package ru.moonlightproject.dev.selene.core;
import java.io.*;
import java.net.*;
import java.security.*;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import javax.net.ssl.*;
public final class RelayServer implements AutoCloseable {
  private final java.util.Set<SSLSocket> active = ConcurrentHashMap.newKeySet();
  private final ScheduledThreadPoolExecutor deadlines = scheduler();
  private final SSLServerSocket listener;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore permits;
  private final int timeout;
  private final BiFunction<AuthRequest, String, CompletableFuture<AuthReply>> handler;
  public RelayServer(SSLContext context, InetSocketAddress address, Duration timeout,
      int concurrency, BiFunction<AuthRequest, String, CompletableFuture<AuthReply>> handler)
      throws IOException {
    if (timeout.toMillis() < 1 || timeout.toMillis() > Integer.MAX_VALUE || concurrency < 1)
      throw new IllegalArgumentException("Invalid relay bounds");
    this.timeout = (int) timeout.toMillis();
    this.handler = java.util.Objects.requireNonNull(handler);
    permits = new Semaphore(concurrency);
    listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
    listener.setNeedClientAuth(true);
    listener.bind(address, concurrency);
    executor.execute(this::accept);
  }
  public int port() {
    return listener.getLocalPort();
  }
  private void accept() {
    while (!listener.isClosed()) {
      try {
        SSLSocket socket = (SSLSocket) listener.accept();
        if (!permits.tryAcquire()) {
          socket.close();
          continue;
        }
        try {
          executor.execute(() -> serve(socket));
        } catch (RejectedExecutionException e) {
          permits.release();
          socket.close();
        }
      } catch (IOException e) {
        if (!listener.isClosed())
          close();
      }
    }
  }
  private void serve(SSLSocket socket) {
    ScheduledFuture<?> deadline = null;
    active.add(socket);
    try (socket) {
      deadline = deadlines.schedule(() -> {
        try {
          socket.close();
        } catch (IOException ignored) {
        }
      }, timeout, TimeUnit.MILLISECONDS);
      socket.setSoTimeout(timeout);
      socket.startHandshake();
      String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          socket.getSession().getPeerCertificates()[0].getEncoded()));
      AuthRequest request = JsonCodec.request(Frames.read(socket.getInputStream()));
      AuthReply reply = handler.apply(request, fingerprint).get(timeout, TimeUnit.MILLISECONDS);
      Frames.write(socket.getOutputStream(), JsonCodec.encode(reply));
    } catch (Exception ignored) {
      // Fail closed; never log request credentials.
    } finally {
      if (deadline != null)
        deadline.cancel(false);
      active.remove(socket);
      permits.release();
    }
  }
  private static ScheduledThreadPoolExecutor scheduler() {
    var scheduler = new ScheduledThreadPoolExecutor(1);
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
  public void close() {
    try {
      listener.close();
    } catch (IOException ignored) {
    }
    for (var socket : active) try {
        socket.close();
      } catch (IOException ignored) {
      }
    executor.shutdownNow();
    deadlines.shutdownNow();
  }
}
