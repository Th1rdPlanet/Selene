package ru.moonlightproject.dev.selene.core;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLContext;
import ru.moonlightproject.bridge.client.MoonLightChannel;
import ru.moonlightproject.bridge.client.MoonLightClient;
public final class BackendGateway implements AuthGateway {
  public static final int METHOD_ID = 1397046273;
  private final MoonLightChannel client;
  private final Semaphore permits;
  private final Duration timeout;
  public BackendGateway(MoonLightChannel client, Duration timeout, int concurrency) {
    if (concurrency < 1 || timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("Invalid backend bounds");
    permits = new Semaphore(concurrency);
    this.client = client;
    this.timeout = timeout;
  }
  public static BackendGateway connect(String endpoint, SSLContext tls,
      boolean allowLoopbackPlaintext, Duration timeout) throws IOException {
    if (timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Invalid timeout");
    URI u = URI.create(endpoint);
    String scheme = u.getScheme();
    MoonLightClient c;
    if ("unix".equals(scheme)) {
      if (u.getPath() == null || !u.getPath().startsWith("/"))
        throw new IllegalArgumentException("Absolute unix path required");
      c = MoonLightClient.unix(Path.of(u.getPath()));
    } else if ("tls".equals(scheme)) {
      if (tls == null)
        throw new IllegalArgumentException("TLS context required");
      c = MoonLightClient.tls(u.getHost(), u.getPort(), tls);
    } else if ("tcp".equals(scheme) && allowLoopbackPlaintext
        && ("127.0.0.1".equals(u.getHost()) || "[::1]".equals(u.getHost())
            || "::1".equals(u.getHost())))
      c = MoonLightClient.tcp(u.getHost(), u.getPort());
    else
      throw new IllegalArgumentException(
          "Only TLS, unix, or explicitly enabled loopback TCP allowed");
    return new BackendGateway(c, timeout, 128);
  }
  public CompletableFuture<AuthReply> call(AuthRequest r) {
    if (!permits.tryAcquire())
      return CompletableFuture.failedFuture(new RejectedExecutionException("Backend busy"));
    try {
      return client.request(METHOD_ID, JsonCodec.encode(r), timeout)
          .whenComplete((reply, error) -> permits.release())
          .thenApply(JsonCodec::reply);
    } catch (RuntimeException e) {
      permits.release();
      return CompletableFuture.failedFuture(e);
    }
  }
  public void close() {
    try {
      client.close();
    } catch (Exception ignored) {
    }
  }
}
