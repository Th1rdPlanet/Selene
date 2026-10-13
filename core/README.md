# Selene shared core

Java 21 bytecode; adapters consume `AuthGateway` and the protocol records described in `docs/protocol.md`.

* `BackendGateway.connect(endpoint, context, developmentPlaintext, timeout)` opens the pinned MoonLightBridge raw channel. TLS uses the supplied context, Unix paths must be absolute, and plaintext requires the development flag plus literal loopback address. Up to 128 requests may be in flight; callers receive exceptional futures when capacity is exhausted. There is no implicit reconnect or plaintext fallback.
* `TlsContexts.load(keyStore, keyPasswordEnvironmentName, trustStore, trustPasswordEnvironmentName)` loads PKCS12 identity/trust into an instance SSLContext without altering JVM global TLS settings.
* `RelayGateway(context, host, port, timeout, concurrency)` verifies the proxy hostname and presents the Paper certificate. `RelayServer(context, address, timeout, concurrency, handler)` requires a trusted client certificate. Handler receives the request and lowercase SHA-256 leaf certificate fingerprint. The adapter must bind this fingerprint to its configured auth-server identity and validate player assignment; chain trust alone is insufficient authorization.
* Relays carry exactly one length-prefixed request/reply per connection, cap frames at 256 KiB, bound concurrent work, and close sockets at an absolute deadline including TLS handshake and frame transfer. `close()` terminates active sockets.
* `AdmissionPolicy(capacity, perIpLimit, window)` maintains bounded local maps, exact normalized account/IP green matching, and level-dependent captcha/rate policy. `admit` expects a monotonic millisecond clock. STRONG green-list rejection belongs before backend/platform work in the caller.
* `ForkCapabilities` is an optional nonblocking ServiceLoader SPI; stock platform behavior must remain safe without a provider. `Settings` parses UTF-8 properties and validates typed values; adapters validate role-specific required configuration.

Tests include real JSSE mutual authentication, missing identity rejection, wrong hostname rejection, trickle-frame absolute deadlines, oversized/truncated frames, rate windows, exact green pairs, redacted request logging, strict JSON fields, and backend inflight backpressure. TLS tests generate temporary test identities using the running JDK's keytool. Run `./gradlew :core:test`.
