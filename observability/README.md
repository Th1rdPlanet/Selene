# Selene observability

Grafana, Prometheus and Tempo in Docker next to a natively running Selene stack. All three use host
networking and listen on loopback only:

| Service    | Address                         |
|------------|---------------------------------|
| Grafana    | http://127.0.0.1:3000 (`admin` / `admin`) |
| Prometheus | http://127.0.0.1:9090           |
| Tempo      | http://127.0.0.1:3200, OTLP/HTTP `127.0.0.1:4318` |

```bash
docker compose -f observability/docker-compose.yml up -d
```

Enable the producers:

- Rust backend, `backend/config.toml`:
  ```toml
  [telemetry]
  otel = true          # traces → Tempo
  prometheus = true    # /metrics on 127.0.0.1:9464 → Prometheus
  ```
- Velocity, `plugins/selene/connection.yml`:
  ```yaml
  telemetry-otel-enabled: true
  ```

Grafana opens on the provisioned **Selene — авторизация** dashboard: sessions, logins and
registrations, CAPTCHA pass rate, TOTP, refusals and overload, latency percentiles per operation,
administrative commands, MoonLightBridge transport and the latest traces.

## Traces

Every backend call produces one distributed trace:

```text
Auth/login                           selene-velocity  Velocity: the call as the proxy sees it
└─ SeleneAuth/Call                   selene-velocity  MoonLightBridge client span
   ├─ json.encode.Auth               selene-velocity  request serialization
   ├─ moonlight.client.prepare       selene-velocity  admission and span start
   ├─ moonlight.client.write         selene-velocity  writer queue and socket flush
   ├─ moonlight.client.wait          selene-velocity  network and backend time
   ├─ Auth/login                     selene-backend   Rust request span, continues the Java trace
   │  ├─ moonlight.server.queue      selene-backend   from frame read to dispatch
   │  ├─ moonlight.server.middleware selene-backend   Selene request scope around the handler
   │  ├─ db.account                  selene-backend   database query
   │  ├─ argon2.verify               selene-backend   password hashing
   │  ├─ db.totp_state               selene-backend   second-factor lookup
   │  └─ moonlight.server.write      selene-backend   response to the socket
   ├─ moonlight.client.dispatch      selene-velocity  completion hand-off
   └─ json.decode.Auth               selene-velocity  reply parsing
```

Selene steps and MoonLightBridge framework stages carry their real start and end times, so the
Tempo waterfall shows where a login spent its time: in plugin work or in the transport. Framework
stages are recorded only for sampled requests. Velocity spans carry `selene.op`, `selene.username` and `selene.status`; spans
never contain passwords, codes or request bodies.
