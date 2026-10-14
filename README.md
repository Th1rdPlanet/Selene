# Selene

Selene is a development-stage authentication system for Paper and Velocity backed by Rust over MoonLightBridge. It targets Java 21 bytecode and runtimes Java 21–26. Paper 1.21.8 is the primary development target; Paper 1.21.1+ is intended but has not yet been exercised as a full server/client matrix.

Author: **~VicTim~**. Java namespace: `ru.moonlightproject.dev.selene`. Project site namespace: `dev.moonlightproject.ru`.

## Build

```sh
./scripts/bootstrap-framework.sh
./gradlew clean build
cargo test --manifest-path backend/Cargo.toml
```

Artifacts are `paper/build/libs/paper-0.1.0-dev.jar`, `velocity/build/libs/velocity-0.1.0-dev.jar`, and the Rust binary under `backend/target` when built with Cargo. The build embeds the pinned experimental MoonLightBridge revision `20f746fbc9782e356b98494122a8c9b1cafa1d5a`; it does not silently follow `main`.

## Deployment layouts

Standalone Paper connects directly to the Rust backend and uses PacketEvents when `packet-events-mode` resolves to `ENABLED`, so the real inventory never reaches an unauthenticated client. PacketEvents is optional on a dedicated `PROXY_AUTH` server and `AUTO` leaves it disabled there because that server has an isolated empty inventory. In a Velocity network, only Velocity connects to Rust; Paper talks only to Velocity through the Selene mTLS relay.

Each process owns its private key. Copy only certificates or CA material required to establish trust; never copy a node's private key to another node. See [certificate setup](docs/certificates.md), [Paper adapter](docs/paper.md), [Velocity adapter](docs/velocity.md), and [backend](backend/README.md).

This repository contains an operational development build, not a production security certification. A real Paper 1.21.8 + Velocity + Minecraft client packet capture and end-to-end mTLS run remains required before public deployment.
