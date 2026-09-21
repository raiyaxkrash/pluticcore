# Отчёт о развёртывании Pocket Odds

## Информация об артефакте
- **Идентификатор мода**: `pocketodds`
- **Версия мода**: `1.0.0`
- **Целевая платформа**: Minecraft 1.20.1, Forge 47.2.0+
- **Имя файла**: `pocketodds-1.0.0.jar`
- **Размер файла**: `98 152` байт
- **Контрольная сумма SHA-256**: `8CB8CC5EC78EDFCC0B03B83DB0B54EE54477E615FB169D6864F12FD83BCE4920`
- **Дата сборки и проверки**: `2026-09-21`

---

## Развёртывание в PrismLauncher
- **Экземпляр PrismLauncher**: `All the Mods 9 - To the Sky - atm9sky`
- **Относительный путь к моду**: `minecraft/mods/pocketodds-1.0.0.jar`
- **Проверка дубликатов**: В каталоге `minecraft/mods` проверено отсутствие других или устаревших версий `pocketodds-*.jar`.
- **Сверка хеш-суммы**: Хеш скопированного в экземпляр JAR полностью совпадает со сборочным артефактом (`8CB8CC5EC78EDFCC0B03B83DB0B54EE54477E615FB169D6864F12FD83BCE4920`).

---

## Результаты многоуровневой верификации

### 1. Автоматическое тестирование (`gradlew test`)
- Набор тестов: `net.pocketodds.MechanicsTest`, `net.pocketodds.RtpSimulationTest`
- Пройдено тестов: **18 из 18** (`BUILD SUCCESSFUL`).
- Проверено:
  - Идемпотентность и атомарный Transactional Outbox в `JackpotSavedData`.
  - Атомарный сброс банка `claimJackpotForTransaction` под единым монитором без сброса свежих взносов.
  - По-стековый перехват исключений `RewardDeliverySink` с сохранением проблемного остатка в outbox и продолжением обработки независимых транзакций.
  - Списание страховки строго после спасения Joker при обычном проигрыше и запись возврата в outbox.
  - Неприменение страховки при катастрофе (трёх черепах).
  - Сохранение выигранной суммы джекпота `wonJackpotAmount` для корректного отображения после сброса пула.
  - Тестирование настоящего предмета `JackpotTokenItem` в `ItemStack` и транслируемых подсказок.
  - 1 000 000 раундов симуляции 3 стратегий «Колоды Судьбы» с подтверждением преимущества казино (RTP < 100%).

### 2. Генерация ресурсов Forge (`gradlew runData`)
- Генератор данных Forge 47.x успешно инициализирован для мода `pocketodds`.
- Ресурсы и конфигурации сформированы без ошибок (`BUILD SUCCESSFUL`).

### 3. Реальный запуск игрового сервера Forge (`gradlew runServer`)
- Проверен запуск выделенного сервера Minecraft 1.20.1 в окружении Forge 47.2.0.
- Логи запуска сервера (`run/logs/latest.log`):
  - `[modloading-worker-0/INFO] [net.pocketodds.PocketOdds/]: Pocket Odds initialized!`
  - `[modloading-worker-0/INFO] [net.pocketodds.PocketOdds/]: Pocket Odds common setup completed.`
  - `[Server thread/DEBUG] [ne.mi.fm.co.ConfigFileTypeHandler/CONFIG]: Loaded TOML config file .\world\serverconfig\pocketodds-server.toml`
  - `[Server thread/INFO] [ne.mi.ga.ForgeGameTestHooks/]: Enabled Gametest Namespaces: [pocketodds]`
  - `[Server thread/INFO] [minecraft/DedicatedServer]: Done (7.704s)! For help, type "help"`
- Исключения и ошибки пакета `net.pocketodds`: **0 обнаружено**.
