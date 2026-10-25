# SDD ledger — plan: docs/superpowers/plans/2026-10-25-maintenance-mode.md

Pre-flight: Task 1 API state feeds Task 2 convergence metadata — signatures align through `MaintenanceController` update methods.
Pre-flight: Task 2 protocol/registry feeds Task 3 Paper nodes and Task 4 Velocity gating — versioned message and registry interfaces align.
Pre-flight: Task 4 connection-bound bypass feeds Task 5 DTO/backend — bypass authority stays exclusively on Velocity.
Pre-flight: Tasks 1–5 emit state consumed by Task 6 observability — labels are bounded reason/revision/component values.
Ruling: execute in the existing `feat/selene` checkout — the user requested direct implementation and has rejected unnecessary workflow questions — cost if wrong: current feature-branch work is less isolated, but main remains untouched.
Task 1: Ruling: Velocity 3.4 has no `ServiceManager`; expose `SeleneApi` on the plugin instance and provide lifecycle-aware lookup through `ProxyServer.getPluginManager()` — preserves official discovery and avoids a singleton — cost if wrong: consumers use a Velocity-specific lookup helper instead of Bukkit-style service registration.
Task 1: complete (commits a85317b..85ab25f, tests: ./gradlew :velocity:test --no-daemon → Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html)
Task 2: complete (commits 85ab25f..f678e80, tests: ./gradlew :core:test :velocity:test --no-daemon → Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html)
Task 3: complete (commits f678e80..e6c8caf, tests: ./gradlew :paper:test --no-daemon → Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html)
Task 4: complete (commits e6c8caf..e6c8caf, tests: ./gradlew :velocity:test → Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html)
Task 5: complete (commits 6effffe..6effffe, tests: cargo test --manifest-path backend/Cargo.toml → test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s)
