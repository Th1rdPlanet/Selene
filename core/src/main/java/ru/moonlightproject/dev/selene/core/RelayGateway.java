package ru.moonlightproject.dev.selene.core;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import javax.net.ssl.*;
public final class RelayGateway implements AuthGateway {
  private final java.util.Set<SSLSocket> active = ConcurrentHashMap.newKeySet();
  private final ScheduledThreadPoolExecutor deadlines = scheduler();
  private final SSLContext context;
  private final String host;
  private final int port, timeout;
  private final ExecutorService executor;
  private final Semaphore permits;
  public RelayGateway(
      SSLContext context, String host, int port, Duration timeout, int concurrency) {
    if (context == null || host == null || port < 1 || port > 65535 || timeout.toMillis() < 1
        || timeout.toMillis() > Integer.MAX_VALUE || concurrency < 1)
      throw new IllegalArgumentException("Invalid relay configuration");
    this.context = context;
    this.host = host;
    this.port = port;
    this.timeout = (int) timeout.toMillis();
    permits = new Semaphore(concurrency);
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }
  public CompletableFuture<AuthReply> call(AuthRequest request) {
    if (!permits.tryAcquire())
      return CompletableFuture.failedFuture(new RejectedExecutionException("Relay busy"));
    try {
      return CompletableFuture.supplyAsync(() -> {
        try (var socket = (SSLSocket) context.getSocketFactory().createSocket()) {
          active.add(socket);
          ScheduledFuture<?> deadline = deadlines.schedule(() -> {
            try {
              socket.close();
            } catch (java.io.IOException ignored) {
            }
          }, timeout, TimeUnit.MILLISECONDS);
          try {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            var p = socket.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(p);
            socket.startHandshake();
            Frames.write(socket.getOutputStream(), JsonCodec.encode(request));
            return JsonCodec.reply(Frames.read(socket.getInputStream()));
          } finally {
            deadline.cancel(false);
            active.remove(socket);
          }
        } catch (Exception e) {
          throw new CompletionException(e);
        } finally {
          permits.release();
        }
      }, executor);
    } catch (RejectedExecutionException e) {
      permits.release();
      return CompletableFuture.failedFuture(e);
    }
  }
  private static ScheduledThreadPoolExecutor scheduler() {
    var scheduler = new ScheduledThreadPoolExecutor(1);
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
  public void close() {
    for (var socket : active) try {
        socket.close();
      } catch (java.io.IOException ignored) {
      }
    executor.shutdownNow();
    deadlines.shutdownNow();
  }
}
