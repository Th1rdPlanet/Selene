# Selene на Velocity

Установите shaded JAR из `velocity/build/libs` и настройте `plugins/selene/velocity.properties`. Здесь находятся только proxy-настройки: Rust backend, mTLS relay для auth Paper, admission, маршрутизация и сообщения. Мир limbo и интерфейс задаются только на auth Paper.

`auth-server` и `default-server` — имена из `velocity.toml`. До авторизации игрок допускается только на auth-server; после успешного входа он возвращается на последний игровой сервер либо на default-server. Velocity создаёт session UUID для физического подключения и доставляет его через точный `ServerConnection`; Paper подтверждает token. Повторная доставка ограничена, а отсутствие подтверждения завершает соединение.

NORMAL и LOW не ограничивают переподключения. LOW включает CAPTCHA только подозрительным IP. MEDIUM включает CAPTCHA и per-IP rate limit для всех. STRONG допускает только точную пару username/IP из зелёного списка; после допуска лицензионный аккаунт может пройти online-mode проверку без пароля. Автоматический режим использует ограниченное окно подключений, число имён с IP и hysteresis. GeoIP/ASN/Tor/VPN источники пока не подключены.

Для неизвестного имени опционально выполняется ограниченный Mojang profile lookup. Он лишь выбирает online-mode handshake; доказательством лицензии остаётся криптографический handshake Velocity. Существующий offline-аккаунт никогда не преобразуется в premium по совпадению имени.

После реального online-mode handshake backend сохраняет точную пару нормализованного ника и канонического IP. Свежий `premiumTrust` snapshot позволяет пропустить повторный Mojang handshake на том же IP, по умолчанию на 30 дней. Изменившийся IP, истёкшая запись, старый/переполненный/некорректный snapshot всегда возвращают обычную проверку Mojang. TOTP при этом не пропускается. Настройки находятся только в `premium.yml`: `premium-trust-enabled`, `premium-trust-ttl-days`, capacity и максимальный возраст snapshot.

Игровая команда `/2fa enable|status|disable|recovery` не принимает секреты аргументами. Она доступна только уже авторизованному игроку, запоминает текущий backend, выдаёт короткоживущий connection-bound management grant и переводит игрока на auth-server. После явного завершения диалога Velocity возвращает игрока на прежний сервер. Алиасы и timeout задаются в `premium.yml`; отключение и обновление recovery-кодов требуют действующий TOTP, а отключение также принимает одноразовый recovery-код.

Все сообщения поддерживают MiniMessage. Динамические значения вставляются literal-компонентами и не могут добавить click/hover tags. Консольная команда `selene account list|info|delete|reset-password|reset-totp` обращается к отдельному backend RPC. `info` и `list` показывают только флаг наличия TOTP, без секретов. Игроки к административной команде не допускаются. Audit-запись содержит только operation, account, status и duration.

Relay использует клиентский сертификат Paper, доверенную CA и pin SHA-256 leaf-сертификата. Закройте relay-bind от внешнего доступа. `fork-enabled=true` требует реализацию `ForkCapabilities`. Неавторизованный игрок маршрутизируется только на auth-сервер с limbo; попытка подключиться к любому игровому backend отклоняется до ответа `AUTHENTICATED`.
