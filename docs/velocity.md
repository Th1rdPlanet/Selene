# Selene Velocity adapter

Install the shaded Velocity JAR and edit `plugins/selene/velocity.properties`. This file contains only proxy responsibilities: backend, admission, routing, relay and proxy messages. Paper world/UI settings belong only on the authentication server.

`auth-server` and `default-server` are names from `velocity.toml`; Selene does not duplicate their addresses. Unauthenticated players can connect only to `auth-server`. After authentication Selene sends them to the last remembered gameplay server when enabled, otherwise `default-server`.

The relay requires a Paper client certificate trusted by the proxy and also pins the configured SHA-256 leaf certificate fingerprint. It accepts only `begin`, `answer`, `register`, `login`, and `close`, binds every request to the proxy's current player/IP/premium/session facts, and requires the player to be on the configured authentication server. Player plugin messages cannot grant authentication.

NORMAL and LOW do not rate-limit reconnects. MEDIUM rate-limits every IP and requires captcha. STRONG first requires an exact account/IP green pair, then premium players use a verified online-mode connection while offline accounts use their Selene password; captcha is disabled. `attack-auto` selects the bounded local rolling-window controller; all window, recovery, global thresholds and per-IP suspicion thresholds are configurable. Set it to false to use `attack-level` manually. A compatible fork may instead supply the nonblocking `ForkCapabilities` service. `fork-enabled=true` and `medium-before-login=true` fail startup if the capability is absent.

On stock Velocity, Selene can force Mojang authentication for premium account names already known to the backend. Discovering a never-before-seen premium account while the proxy itself is in offline mode is not implemented: it requires the planned fork hook or an explicit premium enrollment path. A claimed launcher, UUID or client property is never accepted as proof. Known premium accounts never fall back to offline mode after online verification fails.

All denial/player text lives under `message.*` in `velocity.properties`. Automatic mode uses connection rate, repeated IP activity and distinct usernames with configurable hysteresis. CPU/queue telemetry, GeoIP, ASN, Tor and VPN feeds are not implemented yet. Such data must never become the sole reason for denial.

The proxy periodically obtains bounded premium/green snapshots from Rust. If STRONG mode starts before a snapshot is available, the green set is empty and admission fails closed.
