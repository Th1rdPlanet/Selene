package ru.moonlightproject.dev.selene.core;
import java.nio.file.*;
import java.security.*;
import java.util.Arrays;
import javax.net.ssl.*;
public final class TlsContexts {
  private TlsContexts() {}
  public static SSLContext load(Path keyStore, String keyPasswordEnv, Path trustStore,
      String trustPasswordEnv) throws Exception {
    char[] key = password(keyPasswordEnv), trust = password(trustPasswordEnv);
    try {
      KeyStore ks = KeyStore.getInstance("PKCS12"), ts = KeyStore.getInstance("PKCS12");
      try (var in = Files.newInputStream(keyStore)) {
        ks.load(in, key);
      }
      try (var in = Files.newInputStream(trustStore)) {
        ts.load(in, trust);
      }
      KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      km.init(ks, key);
      TrustManagerFactory tm =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tm.init(ts);
      SSLContext c = SSLContext.getInstance("TLSv1.3");
      c.init(km.getKeyManagers(), tm.getTrustManagers(), new SecureRandom());
      return c;
    } finally {
      Arrays.fill(key, '\0');
      Arrays.fill(trust, '\0');
    }
  }
  private static char[] password(String name) {
    String s = System.getenv(name);
    if (s == null || s.isEmpty())
      throw new IllegalArgumentException("Missing TLS password environment variable: " + name);
    return s.toCharArray();
  }
}
