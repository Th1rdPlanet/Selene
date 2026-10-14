# Selene protocol v1

Selene использует UTF-8 JSON поверх raw MoonLightBridge. Игровой method ID — `1397046273` (`0x53454c01`), административный — `1397046274` (`0x53454c02`). Production transport использует mTLS; plaintext разрешается только явно на loopback.

Игровой `AuthRequest` содержит `op`, server-generated UUID `session`, `username`, `ip`, `password`, `answer`, `premium`, `captchaRequired`. Reply содержит `status`, `session`, CAPTCHA PNG в base64, bounded green/premium snapshot и `retryAfter`. Пароль и ответ CAPTCHA никогда не возвращаются. Поддержаны `profile`, `begin`, `answer`, `login`, `register`, `close`, `trust`, `snapshot`; штатные ошибки — `INVALID_REQUEST`, `EXPIRED`, `UNAVAILABLE`, `BUSY`, `RATE_LIMITED`.

Proxy relay принимает от Paper только операции текущего игрока на auth-server. Username, IP, premium и CAPTCHA policy повторно берутся из состояния Velocity. Delayed callback обязан совпасть и по объекту подключения, и по session UUID. Клиентские plugin messages на `selene:session` поглощаются proxy и не могут авторизовать игрока.

`AdminRequest` содержит `op`, `username`, `page`, `pageSize`. Операции: `list`, `info`, `delete`, `resetPassword`; page size ограничен 50. `AdminReply` возвращает только name, premium, trusted IP/expiry и флаг password reset. Password hash, database error и database URL в ответ не попадают. RPC имеет отдельный semaphore, поэтому административный запрос не занимает authentication permit.

`resetPassword` атомарно заменяет hash на пустой marker, сохраняя premium и trust metadata. Offline-вход с таким marker не может пройти как passwordless login: backend отвечает `REGISTER`, а новый hash записывается только операцией регистрации и только если marker всё ещё пуст. Лицензионная premium-сессия по-прежнему подтверждается online-mode handshake.
