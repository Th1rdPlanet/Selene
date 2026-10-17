# Selene protocol v1

Selene использует UTF-8 JSON поверх raw MoonLightBridge. Игровой method ID — `1397046273` (`0x53454c01`), административный — `1397046274` (`0x53454c02`). Production transport использует mTLS; plaintext разрешается только явно на loopback.

Игровой `AuthRequest` содержит `op`, server-generated UUID `session`, `username`, `ip`, `password`, `answer`, `premium`, `captchaRequired`, `captchaKind`, `challengeNonce`, `totpCode` и `recoveryCode`. Reply содержит `status`, `session`, CAPTCHA PNG/base64 и глифы, одноразовый `challengeNonce`, bounded green/premium-trust snapshot, `retryAfter`, а также ограниченные поля настройки TOTP: `totpUri`, `totpSecret`, `recoveryCodes`, `totpEnabled`. Секретные поля и nonce разрешены только у соответствующей операции и редактируются в `toString`/Debug.

Операции входа: `profile`, `begin`, `answer`, `login`, `register`, `totp_verify`, `close`, `trust`, `snapshot`. После правильного пароля или подтверждённой premium identity backend отвечает `TOTP_REQUIRED`, если у аккаунта включён второй фактор. Только успешный `totp_verify` переводит сессию в `AUTHENTICATED`. TOTP защищён от повторного использования временного шага; recovery-код атомарно удаляется при первом успешном применении.

Для `captchaKind=INTERACTIVE` первый `begin` возвращает `CAPTCHA_INTERACTIVE` и случайный 256-битный base64url nonce. Paper отправляет этот nonce только в `interactive_mistake` или `interactive_complete`. Backend привязывает его к session UUID, IP и имени, делит лимит попыток с цифровой CAPTCHA и принимает завершение один раз. Повторный `begin` не заменяет активное испытание, а повторное завершение отклоняется.

Операции уже авторизованной management-сессии: `totp_status`, `totp_setup_begin`, `totp_setup_confirm`, `totp_disable`, `totp_recovery_regenerate`, `premium_trust`. Setup-secret живёт только в ограниченной сессии до подтверждения. `TOTP_RECOVERY` возвращает новые recovery-коды ровно один раз. Основные статусы: `TOTP_REQUIRED`, `TOTP_INVALID`, `RECOVERY_INVALID`, `TOTP_SETUP`, `TOTP_STATUS`, `TOTP_RECOVERY`, `TOTP_DISABLED`, `TOTP_NOT_ENABLED`, `TOTP_ALREADY_ENABLED`, `PREMIUM_TRUSTED`; штатные ошибки — `INVALID_REQUEST`, `EXPIRED`, `UNAVAILABLE`, `BUSY`, `RATE_LIMITED`.

Proxy relay принимает от Paper только операции текущего игрока на auth-server. Username, IP, premium и CAPTCHA policy повторно берутся из состояния Velocity. Delayed callback обязан совпасть и по объекту подключения, и по session UUID. Клиентские plugin messages на `selene:session` поглощаются proxy и не могут авторизовать игрока.

`AdminRequest` содержит `op`, `username`, `page`, `pageSize`. Операции: `list`, `info`, `delete`, `resetPassword`, `resetTotp`; page size ограничен 50. `AdminReply` возвращает только name, premium, trusted IP/expiry, флаги password reset и `totpEnabled`. Password hash, TOTP secret, recovery hashes, database error и database URL в ответ не попадают. Удаление аккаунта транзакционно удаляет связанный TOTP, recovery и premium trust.

`resetPassword` атомарно заменяет hash на пустой marker, сохраняя premium и trust metadata. Offline-вход с таким marker не может пройти как passwordless login: backend отвечает `REGISTER`, а новый hash записывается только операцией регистрации и только если marker всё ещё пуст. Лицензионная premium-сессия по-прежнему подтверждается online-mode handshake.
