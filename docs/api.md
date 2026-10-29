# Selene Java API (Velocity)

Пакет `ru.moonlightproject.dev.selene.api` внутри jar плагина Selene для Velocity.
Плагину-потребителю достаточно `compileOnly` на jar Selene и зависимости в описании
плагина:

```java
@Plugin(id = "myplugin", dependencies = {@Dependency(id = "selene", optional = true)})
```

## Получение API

```java
Optional<SeleneApi> selene = SeleneApi.find(proxy);
```

`find` каждый раз берёт текущий экземпляр плагина и не хранит глобального состояния —
ссылку можно получать при каждом использовании.

## Методы

| Метод | Поток | Назначение |
|---|---|---|
| `boolean isAuthenticated(Player)` | быстрый | игрок прошёл авторизацию Selene на текущем подключении |
| `CompletionStage<Optional<AccountView>> account(String name)` | бэкенд | данные аккаунта без секретов |
| `CompletionStage<Boolean> resetPassword(String name)` | бэкенд | потребовать новый пароль при следующем входе |
| `CompletionStage<Boolean> resetTotp(String name)` | бэкенд | снять 2FA |
| `CompletionStage<Boolean> deleteAccount(String name)` | бэкенд | удалить аккаунт |
| `MaintenanceState maintenance()` | быстрый | состояние техработ |
| `CompletionStage<MaintenanceState> setMaintenance(enabled, reason, actor)` | бэкенд | включить или выключить техработы во всей сети |

Методы аккаунтов возвращают `false` или `Optional.empty()`, если аккаунта нет, и
завершаются исключением, если бэкенд недоступен. Не вызывайте `join()` в обработчиках
событий Velocity.

`AccountView`: `name`, `premium`, `trustedIp`, `trustedUntil`, `passwordResetPending`,
`totpEnabled`, `activeTrustedIp()`. Хэш пароля, секрет TOTP и коды восстановления через
API недоступны.

## Пример

```java
SeleneApi.find(proxy).ifPresent(selene -> {
  if (!selene.isAuthenticated(player)) {
    return; // игрок ещё в limbo
  }
  selene.account(player.getUsername()).thenAccept(account ->
      account.ifPresent(a -> logger.info("2FA: {}", a.totpEnabled())));
});
```

## Совместная работа с ThirdVelocity

На прокси [ThirdVelocity](https://github.com/Th1rdPlanet/ThirdVelocity) Selene при
`fork-enabled: auto` (по умолчанию) сам подключает ядро:

- уровень атаки ядра повышает уровень защиты Selene;
- `suspicious` ядра (репутация IP, правила клиентов, неизвестные адреса во время атаки)
  включает CAPTCHA для подозрительных игроков;
- счётчики исходящего трафика ядра используются в автоматике `attack.yml`;
- Selene сообщает ядру результаты входа: успешный вход помечает адрес доверенным и
  усиливает связи в поиске твинков (`AltService.recordAuthenticated`), а ошибки пароля и
  CAPTCHA добавляют очки репутации (`auth.failed`, `captcha.failed`).

Баны, GeoIP, твинки и сканирование IP доступны через API ThirdVelocity
(`ThirdVelocity.api()`), см. его `docs/api.md`. Единый плагин модерации может сочетать
оба API: ThirdVelocity — для банов и связей, Selene — для состояния авторизации и
аккаунтов.

Сторонний форк может реализовать `ru.moonlightproject.dev.selene.core.ForkCapabilities`
и зарегистрировать его через `ServiceLoader`. Все методы должны быть быстрыми и
неблокирующими.
