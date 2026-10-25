# Selene Network Maintenance Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fail-closed, network-wide maintenance mode controlled through a public Velocity Java API, using the union of vanilla whitelists and exact previously trusted name/IP pairs while bypassing authentication-factor verification.

**Architecture:** Velocity owns the mode, exposes it through `ServiceManager`, maintains per-Paper whitelist snapshots, gates connections, and propagates versioned state. The existing Paper jar gains a lightweight `NETWORK_NODE` role for gameplay servers; auth Paper forwards a Velocity-bound maintenance flag to the backend, where only factor verification is bypassed. All components emit secret-free structured diagnostics.

**Tech Stack:** Java 21, Velocity 3.4 API, Paper 1.21 API, authenticated plugin messaging, Rust/Tokio/Argon2 backend, JUnit 5, Rust tests, Prometheus and OpenTelemetry.

**Spec:** `docs/superpowers/specs/2026-10-25-maintenance-mode-design.md`

## Global Constraints

- No command or HTTP endpoint may enable maintenance; only `SeleneApi` registered in Velocity `ServiceManager` may do so.
- Admission is fail-closed and requires whitelist membership, an existing account, an unexpired stored trusted IP, and exact normalized incoming-IP equality.
- Maintenance must not create accounts, alter trust, extend expiries, or mutate password/TOTP state.
- CAPTCHA, password and TOTP/recovery verification are bypassed only after the connection-bound Velocity admission decision.
- Console-equivalent elevation applies only to `/selene`, only after maintenance admission and authentication completion.
- Additional secret-free debug logging is active on Velocity, backend, auth Paper and every connected gameplay Paper node.
- Maintenance starts disabled after every Velocity restart.
- Vanilla username whitelist membership and an existing Selene account are always required and cannot be disabled.
- `maintenance.yml` strictly configures `require-trusted-ip`, `require-premium`, separate `enable-*`/`verify-*` flags for CAPTCHA/password/TOTP, and `elevate-selene-commands`; verification cannot be enabled for a skipped factor.

## Review Focus

- A stale/disconnected Paper snapshot must stop granting whitelist membership; pin in Task 2 registry tests.
- Username case and IPv4/IPv6 normalization must not create an admission bypass; pin in Task 3 gate tests.
- A client or Paper plugin message must not be able to set the maintenance bypass bit; pin in Task 4 relay-binding tests.
- Out-of-order state and acknowledgement messages must not roll a node backward; pin in Task 2 codec/controller tests.
- Logs and traces must not contain supplied CAPTCHA/password/TOTP/recovery values; pin in Task 5 backend and Task 6 integration tests.

---

### Task 1: Public API and lifecycle-safe controller

**Files:**
- Create: `velocity/src/main/java/ru/moonlightproject/dev/selene/api/SeleneApi.java`
- Create: `velocity/src/main/java/ru/moonlightproject/dev/selene/api/MaintenanceState.java`
- Create: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/MaintenanceController.java`
- Modify: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/SeleneVelocityPlugin.java`
- Test: `velocity/src/test/java/ru/moonlightproject/dev/selene/velocity/MaintenanceControllerTest.java`
- Create: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/MaintenancePolicy.java`
- Create: `velocity/src/main/resources/defaults/maintenance.yml`
- Modify: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/VelocityConfiguration.java`
- Test: `velocity/src/test/java/ru/moonlightproject/dev/selene/velocity/MaintenancePolicyTest.java`

**Interfaces:**
- Produces: `SeleneApi.maintenance()` and `CompletionStage<MaintenanceState> setMaintenance(boolean,String,String)`; immutable `MaintenanceState`; strict `MaintenancePolicy`; monotonic revisions and serialized/idempotent transitions.
- Consumes: connected/acknowledged server counts and whitelist size through controller update methods implemented in Task 2.

- [ ] Write failing tests for disabled startup, validation of blank actor/reason, idempotent calls, monotonic revisions, convergence metadata, strict `maintenance.yml` keys/types/defaults, and non-configurable whitelist/known-account invariants.
- [ ] Run `./gradlew :velocity:test --tests '*MaintenanceControllerTest'` and verify failures are caused by missing API/controller.
- [ ] Implement the API records/controller, official Velocity plugin-instance discovery, and strict policy loading from `maintenance.yml`.
- [ ] Run the focused test and then `./gradlew :velocity:test`; expect PASS.
- [ ] Commit with `feat(api): expose maintenance mode through Velocity services`.

### Task 2: Versioned Paper state protocol and whitelist union

**Files:**
- Create: `core/src/main/java/ru/moonlightproject/dev/selene/core/MaintenanceMessage.java`
- Create: `core/src/main/java/ru/moonlightproject/dev/selene/core/MaintenanceCodec.java`
- Create: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/WhitelistRegistry.java`
- Modify: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/MaintenanceController.java`
- Test: `core/src/test/java/ru/moonlightproject/dev/selene/core/MaintenanceCodecTest.java`
- Test: `velocity/src/test/java/ru/moonlightproject/dev/selene/velocity/WhitelistRegistryTest.java`

**Interfaces:**
- Produces: bounded/versioned snapshot, state and acknowledgement messages; `WhitelistRegistry.replace(serverId,revision,entries)`, `remove(serverId)`, `contains(name)`, `unionSize()`.
- Consumes: Task 1 controller state/revision.

- [ ] Write failing codec tests for round-trip, size limits, malformed input and out-of-order revisions, plus registry tests for union, case normalization, replacement and disconnect removal.
- [ ] Run the two focused test classes and verify RED.
- [ ] Implement a deterministic binary codec with explicit protocol version and bounded names/entry counts; implement per-server registry and convergence tracking.
- [ ] Run `./gradlew :core:test :velocity:test`; expect PASS.
- [ ] Commit with `feat(core): add maintenance state and whitelist protocol`.

### Task 3: Lightweight Paper network node and whitelist publishing

**Files:**
- Create: `paper/src/main/java/ru/moonlightproject/dev/selene/paper/MaintenanceNode.java`
- Create: `paper/src/main/java/ru/moonlightproject/dev/selene/paper/WhitelistSnapshot.java`
- Modify: `paper/src/main/java/ru/moonlightproject/dev/selene/paper/SelenePaper.java`
- Modify: `paper/src/main/resources/config.yml`
- Modify: `paper/src/main/java/ru/moonlightproject/dev/selene/paper/PaperConfigValidator.java`
- Test: `paper/src/test/java/ru/moonlightproject/dev/selene/paper/WhitelistSnapshotTest.java`
- Test: `paper/src/test/java/ru/moonlightproject/dev/selene/paper/MaintenanceNodeTest.java`

**Interfaces:**
- Produces: Paper `NETWORK_NODE` role; startup/reconnect/change snapshot publishing; latest-revision state application and acknowledgements; `boolean maintenanceEnabled()` for auth Paper.
- Consumes: Task 2 messages/codec.

- [ ] Write failing tests for deterministic vanilla-whitelist fingerprints, add/remove changes, old revision rejection, acknowledgement, and additional logging toggle.
- [ ] Run focused Paper tests and verify RED.
- [ ] Implement `NETWORK_NODE` so it skips limbo/backend/auth initialization, publishes `Bukkit.getWhitelistedPlayers()` snapshots on startup and fingerprint changes, and logs maintenance diagnostics on every Paper role.
- [ ] Run `./gradlew :paper:test`; expect PASS.
- [ ] Commit with `feat(paper): synchronize vanilla whitelists across network nodes`.

### Task 4: Fail-closed Velocity admission and connection-bound bypass

**Files:**
- Create: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/MaintenanceAdmission.java`
- Modify: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/ConnectionSessions.java`
- Modify: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/SeleneVelocityPlugin.java`
- Modify: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/SeleneCommand.java`
- Test: `velocity/src/test/java/ru/moonlightproject/dev/selene/velocity/MaintenanceAdmissionTest.java`
- Test: `velocity/src/test/java/ru/moonlightproject/dev/selene/velocity/ConnectionSessionsTest.java`
- Test: `velocity/src/test/java/ru/moonlightproject/dev/selene/velocity/SeleneCommandTest.java`

**Interfaces:**
- Produces: admission decision/reason code honoring `require-trusted-ip` and `require-premium`; `ConnectionSessions.Session.maintenanceRevision()` and policy; server-authoritative maintenance policy in bound backend requests; optionally elevated Selene-only console-equivalent permissions.
- Consumes: Task 1 mode and Task 2 whitelist registry; existing backend green/trusted-IP snapshot.

- [ ] Write failing tests for every invariant/configurable admission predicate, premium cryptographic proof, expired/missing snapshots, case/IP normalization, stale backend snapshot, forged policy fields, and configurable command elevation limited to admitted authenticated sessions.
- [ ] Run focused Velocity tests and verify RED.
- [ ] Implement pre-login gating before session creation, bind revision/flag inside `ConnectionSessions`, broadcast state, process Paper snapshots/acks, and elevate only `SeleneCommand.has(...)` while maintenance is active.
- [ ] Run `./gradlew :velocity:test`; expect PASS.
- [ ] Commit with `feat(velocity): enforce maintenance admission and command scope`.

### Task 5: Backend factor bypass without state mutation

**Files:**
- Modify: `core/src/main/java/ru/moonlightproject/dev/selene/core/AuthRequest.java`
- Modify: `backend/src/lib.rs`
- Modify: `backend/src/service.rs`
- Modify: `backend/src/tests.rs`
- Modify: `core/src/test/java/ru/moonlightproject/dev/selene/core/JsonCodecTest.java`

**Interfaces:**
- Produces: validated `maintenanceRevision` and per-factor verification fields with backend session binding; policy-driven transitions for CAPTCHA, login and TOTP/recovery.
- Consumes: Task 4 server-authoritative bound request fields.

- [ ] Write failing Java/Rust tests proving each disabled factor advances while each enabled factor still rejects invalid input, registration stays denied, trust/password/TOTP rows remain unchanged, and secrets never appear in logs/debug formatting.
- [ ] Run `./gradlew :core:test` and `cargo test --manifest-path backend/Cargo.toml`; verify RED.
- [ ] Add the fields to both DTOs, bind them on `begin`, reject mid-session changes, and bypass only factor comparisons while preserving state-machine and expiry checks.
- [ ] Run the focused suites, then the complete Java and Rust tests; expect PASS.
- [ ] Commit with `feat(backend): bypass factors for admitted maintenance sessions`.

### Task 6: Network-wide diagnostics, metrics, deployment and verification

**Files:**
- Modify: `backend/src/metrics.rs`
- Modify: `backend/src/traces.rs`
- Modify: `velocity/src/main/java/ru/moonlightproject/dev/selene/velocity/VelocityTracing.java`
- Modify: `paper/src/main/resources/languages/ru.json`
- Modify: `paper/src/main/resources/languages/en.json`
- Modify: `velocity/src/main/resources/languages/ru.json`
- Modify: `velocity/src/main/resources/languages/en.json`
- Modify: `docs/paper.md`
- Modify: `docs/velocity.md`
- Create: `server/lobby/plugins/Selene/config.yml` and required node configuration during deployment (runtime files remain ignored)
- Test: relevant Java and Rust observability tests

**Interfaces:**
- Consumes: Tasks 1-5 mode, admission reasons, revisions, snapshots and bypass stages.
- Produces: structured console events on every component, Prometheus maintenance gauges/counters, safe trace attributes, deployed auth/proxy/lobby nodes.

- [ ] Write failing tests for metric labels, state propagation diagnostics, all-server logging enablement, and secret redaction.
- [ ] Run focused tests and verify RED.
- [ ] Implement metrics/logging/docs, build both shaded JARs and backend binary, configure lobby as `NETWORK_NODE`, and deploy to proxy/auth/lobby/backend.
- [ ] Run `./gradlew clean build --no-daemon` and `cargo test --manifest-path backend/Cargo.toml`; expect zero failures.
- [ ] Restart affected services; verify proxy/auth/lobby/backend active, all Paper nodes acknowledged, normal mode unchanged, maintenance test admission is fail-closed, Grafana metrics present, and logs contain no credentials.
- [ ] Commit with `feat: add network-wide maintenance diagnostics` and push `feat/selene` only after fresh verification.
