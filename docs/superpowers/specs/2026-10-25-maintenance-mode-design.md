# Selene maintenance mode design

## Intent

Provide an emergency authentication mode controlled only through a public Java API on Velocity. It keeps the network closed to unknown identities while allowing known operators to enter even when CAPTCHA, password verification, or TOTP services are impaired.

## Public API

The Velocity plugin registers a stable `SeleneApi` interface in Velocity `ServiceManager` during startup and unregisters it during shutdown.

```java
public interface SeleneApi {
    MaintenanceState maintenance();
    CompletionStage<MaintenanceState> setMaintenance(boolean enabled, String reason, String actor);
}
```

`MaintenanceState` contains at least: enabled flag, monotonically increasing revision, activation time, actor, reason, whitelist size, connected/acknowledged Selene server counts, and convergence status. Null or blank actor/reason values are rejected. Calls are serialized and idempotent.

There is deliberately no `/selene maintenance` command and no HTTP endpoint.

## Whitelist source

Every connected Paper server running Selene publishes its current vanilla Bukkit whitelist as a full versioned snapshot to Velocity over the existing authenticated management plugin channel. It publishes on plugin startup, server connection, and whitelist changes detected by a low-frequency fingerprint check. No filesystem sharing is required.

Velocity maintains one snapshot per currently connected server. The effective maintenance whitelist is the case-insensitive union of all current snapshots. A disconnected server's snapshot is removed. Empty, missing, stale, malformed, or unacknowledged snapshots grant no access. UUID is retained for audit data, but admission uses Minecraft's canonical case-insensitive username because offline-mode UUIDs can differ between servers.

## Admission rules

Normal mode is unchanged.

Velocity loads `maintenance.yml`. Vanilla username whitelist membership and an existing Selene account are security invariants and cannot be disabled. The remaining maintenance checks use strict boolean settings:

```yaml
require-trusted-ip: true
require-premium: false
enable-captcha: true
verify-captcha: false
enable-password: true
verify-password: false
enable-totp: true
verify-totp: false
elevate-selene-commands: true
```

Unknown keys and non-boolean values fail plugin startup. The resolved policy and every disabled check are logged when maintenance is enabled and propagated with the revision. Additional maintenance diagnostics are always enabled and are not configurable.

In maintenance mode Velocity rejects a connection before an auth session is created unless all conditions hold:

1. The username occurs in the effective whitelist.
2. A Selene account with that exact canonical username already exists.
3. If `require-trusted-ip` is enabled, the account has a non-empty, unexpired previously recorded trusted IP and the incoming normalized IP exactly equals it.
4. If `require-premium` is enabled, Velocity has cryptographic online-mode proof for that connection and the known Selene account is premium.

Unknown accounts, renamed accounts, new IP addresses, expired/missing trust records, and players present only in a stale server snapshot are rejected. Maintenance never registers an account, updates trusted IP, extends trust expiry, creates premium trust, or modifies password/TOTP state.

After the admission gate succeeds, each factor follows separate `enable-*` and `verify-*` policy. `enable-*=false` skips that stage entirely. `enable-*=true` with `verify-*=false` displays the normal UI but accepts any syntactically valid value. Both true displays and verifies normally. `verify-*=true` with `enable-*=false` is rejected as invalid configuration. The policy is evaluated server-side from the maintenance revision bound to that physical connection; clients cannot request or weaken it.

## Network-wide state

Velocity is the authority. Each state change increments a revision and is broadcast to every connected Selene Paper server over the authenticated management channel. Paper stores only the latest revision and ignores older messages. New or reconnected servers receive the current state and submit their whitelist before being counted as converged.

`setMaintenance` completes with the observed convergence result instead of silently claiming success. The authoritative mode remains active on Velocity even if a Paper acknowledgement is missing; that server is treated as unavailable for authentication until it catches up.

## Commands and permissions

While maintenance is active and `elevate-selene-commands` is enabled, a player who passed the admission gate may run all `/selene` subcommands normally available to the console. This elevation applies only to Selene commands; it does not grant Bukkit operator status, Velocity permissions, or access to commands from other plugins. The existing authenticated-session check remains mandatory.

When maintenance ends, ordinary `selene.command.*` permission checks immediately apply again.

## Logging and observability

Maintenance adds structured console logs on Velocity, the Rust backend, and every connected Paper server. Gameplay servers run the same Selene Paper jar in a lightweight `NETWORK_NODE` role: it does not authenticate players or create a backend connection, but it synchronizes the vanilla whitelist, receives maintenance state and emits the additional maintenance diagnostics. A Paper server without this network node cannot be counted as converged while maintenance is active.

The additional logs cover:

- mode changes, revision, actor and reason;
- server snapshot additions/removals and effective whitelist size;
- state propagation acknowledgements and convergence failures;
- every admission decision with canonical username, normalized IP, server/revision and a reason code;
- authentication-factor bypass stages without logging submitted secrets.

Prometheus exposes mode, revision, whitelist size, connected/acknowledged servers, admissions and rejections by reason. OpenTelemetry spans include maintenance and decision attributes but never password, CAPTCHA, TOTP, recovery code, certificate material, or full account records.

## Failure behavior

The design is fail-closed. Backend unavailability, missing account data, stale whitelist state, malformed messages, revision mismatch, or loss of the Velocity authority rejects new maintenance admissions. Already connected authenticated players are not disconnected merely because one Paper server leaves, unless a separate plugin chooses to do so through its own policy.

On Velocity restart maintenance returns to disabled by default. An integrating plugin must explicitly enable it again; this prevents an accidental permanent bypass after a crash.

## Compatibility and rollout

Protocol messages are versioned. Old Paper nodes that do not support maintenance never acknowledge the state and are excluded from authentication routing while maintenance is active. Normal mode remains compatible with the current protocol.

Implementation will add tests for API lifecycle, whitelist union/removal, exact name/IP admission, fail-closed cases, factor bypass, revision ordering, command elevation scope, secret-free logs, and state convergence.

## Approaches considered

- Velocity `ServiceManager` API: selected because it provides lifecycle-aware discovery without global state.
- Static singleton: rejected because reloads can retain stale state and consumers become coupled to the implementation.
- HTTP control API: rejected because the requested caller is another Java plugin and an HTTP listener would add unnecessary credentials and attack surface.
