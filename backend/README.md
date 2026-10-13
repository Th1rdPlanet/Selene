# Selene backend

Run root `scripts/bootstrap-framework.sh` first to obtain the pinned MoonLightBridge source, then:

```sh
export SELENE_DATABASE_URL='sqlite://backend/selene.db?mode=rwc'
cargo run --manifest-path backend/Cargo.toml --release -- backend/config.toml
```

The URL also accepts `postgres://...` and `mysql://...`. Schema creation is automatic; SQL parameters are bound and account names canonicalized. Protect the database and environment credentials. SQLite is covered by automated tests; MySQL and PostgreSQL drivers compile but require integration testing against real servers before deployment.

Transport requires server PEM certificate/private key and frontend CA PEM. The frontend must verify the server name and present a certificate signed by that CA. Only authorized trusted Paper/Velocity processes may possess frontend keys: the backend trusts their premium handshake and admission decisions. `development_plaintext = true` is accepted only on a numeric loopback listen address, for smoke tests. Never expose this development listener through a public tunnel.

Raw method 1397046273 implements `docs/protocol.md`. Sessions must be frontend-generated connection UUIDs, bound to normalized name and parsed IP. Authentication transitions use fail-fast serial admission: concurrent operations may return BUSY and frontends should retry with bounded backoff. This favors a simple auditable state machine over high login throughput. Password work uses Argon2id defaults (19 MiB, two iterations, one lane) on bounded blocking workers; worker permits remain held if a caller is canceled. No request/password logging is performed. Session and captcha failures share the configured attempt budget. Challenge answers are private and never serialized.

Pending sessions expire after 180 seconds; successful sessions last 24 hours by default and may qualify for green trust after 600 authenticated seconds. Frontends must send `close` on disconnect, and must never request trust for disconnected players. Green trust is one persisted IP per account, replaced after a newly authenticated session qualifies, with a seven-day default expiry. Closing or expiring a session immediately discards its CAPTCHA and authentication state, then records only its UUID in a separately bounded replay tombstone cache for `pending_seconds`. Tombstones do not consume active-session capacity; frontend UUIDs must still never be reused. Snapshot is bounded to 4096 eligible accounts and a 262144-byte response; exceeding bounds fails closed as UNAVAILABLE. Large installations need paginated snapshots in a future protocol version.

CAPTCHA is a fixed 128×128 distorted digit-only PNG; no visual knobs or plaintext answer are exposed. Actual readability and attack resistance require client/operator evaluation. Backend authentication and trusted-time checks do not establish Minecraft inventory confidentiality; the platform adapters must enforce that separately.

`config-development.toml` exists only for the local Java↔Rust integration smoke. Its plaintext listener is hard-coded to loopback and must not be used as a deployment configuration.

```sh
cargo test --manifest-path backend/Cargo.toml
cargo fmt --manifest-path backend/Cargo.toml -- --check
```
