# Selene

Selene is a development-stage authentication system for Paper and Velocity backed by Rust over MoonLightBridge. It targets Java 21 bytecode and runtimes Java 21–26. Paper 1.21.8 is the primary development target; Paper 1.21.1+ is intended but has not yet been exercised as a full server/client matrix.

Author: **~VicTim~**. Java namespace: `ru.moonlightproject.dev.selene`. Project site namespace: `dev.moonlightproject.ru`.

## Build

```sh
./gradlew clean build
cargo test --manifest-path backend/Cargo.toml
```

Artifacts are `paper/build/libs/paper-0.1.0-dev.jar`, `velocity/build/libs/velocity-0.1.0-dev.jar`, and the Rust binary under `backend/target` when built with Cargo. The build uses the published MoonLightBridge 0.6.1 artifacts from Maven Central and crates.io (`moonlightBridgeVersion` in `gradle.properties`, versions in `backend/Cargo.toml`); it never compiles framework sources.

## Deployment layouts

Standalone Paper connects directly to the Rust backend and uses PacketEvents when `packet-events-mode` resolves to `ENABLED`, so the real inventory never reaches an unauthenticated client. PacketEvents is optional on a dedicated `PROXY_AUTH` server and `AUTO` leaves it disabled there because that server has an isolated empty inventory. In a Velocity network, only Velocity connects to Rust; Paper talks only to Velocity through the Selene mTLS relay.

Each process owns its private key. Copy only certificates or CA material required to establish trust; never copy a node's private key to another node. See [certificate setup](docs/certificates.md), [Paper adapter](docs/paper.md), [Velocity adapter](docs/velocity.md), [Java API](docs/api.md), and [backend](backend/README.md).

This repository contains an operational development build, not a production security certification. A real Paper 1.21.8 + Velocity + Minecraft client packet capture and end-to-end mTLS run remains required before public deployment.

### Подтверждение сильной защиты на Velocity

При рекомендации `STRONG` Selene сохраняет эффективный уровень `MEDIUM` и просит
подтвердить ограничение входа командой в консоли прокси:

```text
selene attack status
selene attack confirm
```

Напоминание повторяется раз в 10 секунд, пока запрос актуален. Команда доступна
только консоли. Перед подтверждением Selene повторно проверяет состояние защиты;
после восстановления разрешение сбрасывается. Следующая атака требует нового
подтверждения. Это правило также применяется к рекомендации совместимого форка
и уровню `STRONG` из конфигурации.

Каждое фактическое переключение уровня, включая снижение, записывается в консоль:
прежний и новый уровень, источник решения и причина. Проверка выполняется также
раз в секунду, поэтому восстановление не ждёт следующего подключения.
`attack-level` задаёт нижнюю границу защиты при автоматическом определении;
до подтверждения `STRONG` эта граница ограничивается `MEDIUM`.

### API форка Velocity

`fork-enabled: auto` (по умолчанию) подключает [ThirdVelocity](https://github.com/Th1rdPlanet/ThirdVelocity),
если прокси на нём запущен, или реализацию `ForkCapabilities` через `ServiceLoader`;
`true` требует ядро, `false` отключает. Подробнее — [docs/api.md](docs/api.md).

Форк подключается через Java `ServiceLoader` и реализацию
`ru.moonlightproject.dev.selene.core.ForkCapabilities`. При включённом fork API
плагин проверяет `supportsEarlyAdmission()` и использует только быстрые локальные
методы интерфейса. Сетевые запросы и блокировки в них недопустимы.

`attackLevel()` возвращает уровень, рассчитанный самим ядром, `suspicious(username,
ip)` добавляет локальный признак подозрительности, а `protectionSignals()` передаёт
число исходящих пакетов и байтов за последнюю секунду. Счётчики не должны читать или
сохранять содержимое пакетов. Для недоступного показателя возвращается `-1`; готовая
константа — `ProtectionSignals.UNAVAILABLE`. Selene объединяет сигналы форка со
своими подключениями, ошибками CAPTCHA/пароля и числом незавершённых сессий.
