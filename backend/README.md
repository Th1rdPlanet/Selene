# Selene backend

From the repository root, obtain the pinned MoonLightBridge source once and start the backend through the checked launcher:

```sh
./scripts/bootstrap-framework.sh
export SELENE_DATABASE_URL='sqlite://backend/selene.db?mode=rwc'
export SELENE_TOTP_MASTER_KEY="$(openssl rand -base64 32)"
./scripts/run-backend.sh
```

The launcher validates Cargo, the framework checkout, config, database environment variable and required TLS files before compiling. Pass another config path as its first argument when needed. The backend binds to `127.0.0.1:38191` by default, matching Velocity's default `backend-endpoint`.

The URL also accepts `postgres://...` and `mysql://...`. Schema creation is automatic; SQL parameters are bound and account names canonicalized. Protect the database and environment credentials. SQLite is covered by automated tests; MySQL and PostgreSQL drivers compile but require integration testing against real servers before deployment.

`[totp] enabled = true` requires the environment variable named by `master_key_env` to contain exactly 32 random bytes encoded as Base64. `issuer` is the service name shown by Authenticator applications; `account_label` is the per-account caption and must contain `{username}`. Generate the encryption key once, distribute it only to backend processes that open the same database, and store it in a secret manager. Losing or changing this key makes existing TOTP credentials undecryptable. The backend encrypts TOTP secrets with XChaCha20-Poly1305, stores keyed recovery-code hashes, rejects replayed TOTP time steps, and defaults premium-IP trust to 30 days. Authenticator interoperability uses standard six-digit SHA-1 TOTP with a 30-second period and a configurable adjacent-step window.

Transport requires server PEM certificate/private key and frontend CA PEM. The frontend must verify the server name and present a certificate signed by that CA. Only authorized trusted Paper/Velocity processes may possess frontend keys: the backend trusts their premium handshake and admission decisions. `development_plaintext = true` is accepted only on a numeric loopback listen address, for smoke tests. Never expose this development listener through a public tunnel.

Raw method 1397046273 implements `docs/protocol.md`. Sessions must be frontend-generated connection UUIDs, bound to normalized name and parsed IP. Authentication transitions use fail-fast serial admission: concurrent operations may return BUSY and frontends should retry with bounded backoff. This favors a simple auditable state machine over high login throughput. Password work uses Argon2id defaults (19 MiB, two iterations, one lane) on bounded blocking workers; worker permits remain held if a caller is canceled. No request/password logging is performed. Session and captcha failures share the configured attempt budget. Challenge answers are private and never serialized.

Pending sessions expire after 180 seconds; successful sessions last 24 hours by default and may qualify for green trust after 600 authenticated seconds. Frontends must send `close` on disconnect, and must never request trust for disconnected players. Green trust is one persisted IP per account, replaced after a newly authenticated session qualifies, with a seven-day default expiry. Closing or expiring a session immediately discards its CAPTCHA and authentication state, then records only its UUID in a separately bounded replay tombstone cache for `pending_seconds`. Tombstones do not consume active-session capacity; frontend UUIDs must still never be reused. Snapshot is bounded to 4096 eligible accounts and a 262144-byte response; exceeding bounds fails closed as UNAVAILABLE. Large installations need paginated snapshots in a future protocol version.

CAPTCHA is a fixed 128×128 distorted digit-only PNG; no visual knobs or plaintext answer are exposed. Actual readability and attack resistance require client/operator evaluation. Backend authentication and trusted-time checks do not establish Minecraft inventory confidentiality; the platform adapters must enforce that separately.

`config-development.toml` exists only for the local Java↔Rust integration smoke. Its plaintext listener is hard-coded to loopback and must not be used as a deployment configuration.

```sh
cargo test --manifest-path backend/Cargo.toml
cargo fmt --manifest-path backend/Cargo.toml -- --check
```

`[telemetry] otel = true` exports an OpenTelemetry span for every RPC through OTLP/HTTP to `otlp_endpoint` (Tempo, Grafana Alloy or an OpenTelemetry Collector, default `http://127.0.0.1:4318/v1/traces`) with `service.name` from `service_name`. It is off by default; while off, no exporter is created. Spans carry the method id, request id, sizes, status and handler timing, never request or response bodies. The exporter is bounded and flushed when the backend stops.

`[telemetry] prometheus = true` serves `/metrics` on `prometheus_listen` (default `127.0.0.1:9464`). Besides the MoonLightBridge transport series (`moonlight_bridge_*`) it exports `selene_auth_requests_total{op,status}`, `selene_auth_request_duration_seconds{op}`, `selene_admin_requests_total{op,status}`, `selene_sessions{state}` and `selene_build_info`. Labels come from closed lists; unknown operations and statuses collapse into `other`. The ready-made Grafana, Prometheus and Tempo stack with the Selene dashboard lives in [`../observability`](../observability/README.md).
