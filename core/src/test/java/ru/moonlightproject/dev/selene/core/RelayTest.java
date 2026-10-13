package ru.moonlightproject.dev.selene.core;
import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class RelayTest {
  @TempDir static Path temp;
  static SSLContext identity, anonymous;
  @BeforeAll
  static void certificates() throws Exception {
    Path store = temp.resolve("test.p12");
    String tool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    var p = new ProcessBuilder(tool, "-genkeypair", "-alias", "test", "-keyalg", "EC", "-dname",
        "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1", "-storetype", "PKCS12",
        "-keystore", store.toString(), "-storepass", "test-password", "-keypass", "test-password")
                .redirectErrorStream(true)
                .start();
    String out = new String(p.getInputStream().readAllBytes());
    assertEquals(0, p.waitFor(), out);
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(store)) {
      ks.load(in, "test-password".toCharArray());
    }
    var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(ks, "test-password".toCharArray());
    var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(ks);
    identity = SSLContext.getInstance("TLSv1.3");
    identity.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    anonymous = SSLContext.getInstance("TLSv1.3");
    anonymous.init(new KeyManager[0], tm.getTrustManagers(), null);
  }
  @Test
  void authenticatesBothPeersAndSuppliesFingerprint() throws Exception {
    try (var server = new RelayServer(identity, new InetSocketAddress("127.0.0.1", 0),
             Duration.ofSeconds(2), 2,
             (r, fp) -> {
               assertTrue(fp.matches("[a-f0-9]{64}"));
               return CompletableFuture.completedFuture(AuthReply.status("LOGIN", r.session()));
             });
        var gateway =
            new RelayGateway(identity, "localhost", server.port(), Duration.ofSeconds(2), 2)) {
      assertEquals("LOGIN",
          gateway.call(new AuthRequest("begin", "s", "Alice", "127.0.0.1", "", "", false, false))
              .get(5, TimeUnit.SECONDS)
              .status());
    }
  }
  @Test
  void rejectsMissingClientIdentityAndWrongServerHostname() throws Exception {
    try (var server = new RelayServer(identity, new InetSocketAddress("127.0.0.1", 0),
             Duration.ofSeconds(2), 2,
             (r, fp) -> CompletableFuture.completedFuture(AuthReply.status("LOGIN", r.session())));
        var noIdentity =
            new RelayGateway(anonymous, "localhost", server.port(), Duration.ofSeconds(2), 2);
        var wrongHostname =
            new RelayGateway(identity, "127.0.0.1", server.port(), Duration.ofSeconds(2), 2)) {
      var request = new AuthRequest("begin", "s", "Alice", "127.0.0.1", "", "", false, false);
      assertThrows(
          ExecutionException.class, () -> noIdentity.call(request).get(5, TimeUnit.SECONDS));
      assertThrows(
          ExecutionException.class, () -> wrongHostname.call(request).get(5, TimeUnit.SECONDS));
    }
  }
  @Test
  void closesConnectionAtAbsoluteDeadlineEvenWhenPeerTricklesBytes() throws Exception {
    try (var server = new RelayServer(identity, new InetSocketAddress("127.0.0.1", 0),
             Duration.ofMillis(500), 2,
             (r, fp) -> CompletableFuture.completedFuture(AuthReply.status("LOGIN", r.session())));
        var socket =
            (SSLSocket) identity.getSocketFactory().createSocket("localhost", server.port())) {
      socket.startHandshake();
      socket.setSoTimeout(2000);
      var writer = Executors.newSingleThreadScheduledExecutor();
      try {
        writer.scheduleAtFixedRate(() -> {
          try {
            socket.getOutputStream().write(0);
            socket.getOutputStream().flush();
          } catch (Exception ignored) {
          }
        }, 0, 300, TimeUnit.MILLISECONDS);
        long start = System.nanoTime();
        assertEquals(-1, socket.getInputStream().read());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 700,
            "Absolute deadline must cap trickling frame reads");
      } finally {
        writer.shutdownNow();
      }
    }
  }
}
