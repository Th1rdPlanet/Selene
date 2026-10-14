# Selene на Paper

Соберите `./gradlew :paper:build` и установите `paper/build/libs/paper-0.1.0-dev.jar`. Плагин собирается в bytecode Java 21 и рассчитан на Java 21–26. Целевой диапазон Paper — 1.21.1 и новее; нативные Dialog API изолированы в отдельном классе и используются только когда API присутствует.

`role: PROXY_AUTH` предназначен для отдельного auth-сервера. Такой Paper общается только с mTLS relay Velocity из `relay.properties`; адрес Rust backend на нём отсутствует. `role: STANDALONE` использует `standalone.properties` и подключается к backend напрямую. В каждом role-файле находятся только настройки этого узла. Значения `*-password-env` — имена переменных окружения, а не пароли.

CAPTCHA и ввод учётных данных всегда проходят в limbo. `limbo.mode: MANAGED` создаёт пустой мир `selene_limbo`; `EXISTING` требует уже загруженный мир. Во время проверки Selene блокирует координаты, yaw/pitch, телепорты не от плагинов, урон, атаки, взаимодействие, инвентарь, выбрасывание и подбор предметов, смену слота, публичный чат и посторонние команды. Игрока можно скрыть от остальных. После входа прежние location, scoreboard, XP и эффекты восстанавливаются, если другой плагин не успел заменить состояние, которым владела Selene.

Карта с пятизначной CAPTCHA приходит из Rust backend и показывается в offhand. Ответ вводится обычным сообщением в чат, без команды. Сообщение отменяется до публикации. Для входа используются `/login` и `/register` без аргументов. На Paper с Dialog API открывается форма с одним полем входа или двумя полями регистрации. Fallback принимает пароль отменёнными сообщениями чата; регистрация проходит в два шага. Аргументы `/login <password>` отключены по умолчанию через `secure-input.compatibility-password-arguments: false`, поскольку сторонний плагин способен увидеть исходный command packet.

Все сообщения, labels диалогов, title, subtitle, actionbar и scoreboard поддерживают MiniMessage. В `presentation` каждый канал включается независимо и имеет отдельный формат для `connecting`, `captcha`, `login`, `register` и `processing`. `actionbar` — это строка над хотбаром. `<seconds>` показывает остаток времени; scoreboard принимает 1–15 строк. Полоса опыта и число уровня включаются отдельно. Selene не содержит resource pack: `resource-pack-provider` лишь проверяет наличие Oraxen или ItemsAdder, а `<font:...>` и глифы указываются прямо в шаблонах MiniMessage.

`packet-events-mode: AUTO` включает PacketEvents только в `STANDALONE`, где до входа нужно скрывать настоящий игровой инвентарь. На выделенном `PROXY_AUTH` сервере инвентарь изолирован и PacketEvents не требуется. `ENABLED` и `DISABLED` принудительно меняют это поведение.

Консоль standalone Paper поддерживает `selene account list [page]`, `info <name>`, `delete <name>` и `reset-password <name>`. На `PROXY_AUTH` команда сообщает, что её нужно выполнить в консоли Velocity. Игроки не получают доступ даже с правами оператора.

Старый блок `auth-location` удалён. Замените его на `limbo.*`; при обнаружении legacy-ключа Selene выводит точный путь и останавливает запуск. Плагин также останавливает сервер при отсутствующем limbo-мире, неверных bounds, сломанном MiniMessage, TLS-файле или переменной окружения. Hot reload не поддерживается.
