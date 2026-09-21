# Отчёт о развёртывании Pocket Odds

## Информация об артефакте
- **Идентификатор мода**: `pocketodds`
- **Версия мода**: `1.0.0`
- **Целевая платформа**: Minecraft 1.20.1, Forge 47.2.0+
- **Имя файла**: `pocketodds-1.0.0.jar`
- **Размер файла**: `161 270` байт
- **Контрольная сумма SHA-256**: `055DB75F0ED1B6AFD9C0830D69B34AEB1C85E01E1D5714AB7FC97B5697A0CFFB`
- **Дата сборки и проверки**: `2026-09-21`

---

## Развёртывание в PrismLauncher
- **Экземпляр PrismLauncher**: `All the Mods 9 - To the Sky - atm9sky`
- **Относительный путь к моду**: `minecraft/mods/pocketodds-1.0.0.jar`
- **Проверка дубликатов**: В каталоге `minecraft/mods` проверено отсутствие других или устаревших версий `pocketodds-*.jar`.
- **Сверка хеш-суммы**: Хеш скопированного в экземпляр JAR полностью совпадает со сборочным артефактом (`055DB75F0ED1B6AFD9C0830D69B34AEB1C85E01E1D5714AB7FC97B5697A0CFFB`).

---

## Результаты многоуровневой верификации

### 1. Автоматическое тестирование (`gradlew test`)
- Наборы тестов: `net.pocketodds.MechanicsTest`, `net.pocketodds.ConfigParserTest`, `net.pocketodds.ItemBettingTest`, `net.pocketodds.RtpSimulationTest`
- Пройдено тестов: **52 из 52** (`BUILD SUCCESSFUL`, 100% success rate).
- Проверено:
  - 4-фазный 2PC жизненный цикл предметных ставок (`PREPARED` -> `DEBITED` -> `COMMITTED` -> `REFUND_QUEUED`).
  - Связывание `BetPreparation` с детерминированным `associatedId` (rollId, sessionId, txId) с исключением одновременного возврата `DEBITED` ставки и выплаты выигрыша при crash recovery.
  - Атомарная фиксация кэшаута Колоды (`commitDeckCashout`) под единой синхронизацией в `JackpotSavedData` и автовосстановление кэшаута при сбое сервера.
  - Полноценная поддержка таблицы наград `deckRewardTable` и режимов `PayoutMode` (`SAME_ITEM`, `REWARD_TABLE`, `BOTH`) в Колоде Судьбы.
  - Персистентный settlement в `settleAndCommitBet` для мгновенных игр (`VoidDiceItem`, `RouletteTokenItem`), предотвращающий ложный возврат проигранной ставки при падении сервера.
  - Очистка формата таблицы наград до чистого 6-полевого формата (`itemId;weight;maxCap;creditValue;minBetCredits;maxBetCredits`) с сохранением обратной совместимости для устаревшего 8-полевого формата.
  - Построчная идентификация наград через `UUID lineId` (`RewardLine`) без коллизий одинаковых стаков.
  - Персистентные серверные сессии `DeckSession` в `JackpotSavedData` с валидацией владельца через `DeckOfFateItem.isSessionOwner()`.
  - Все unit-тесты переведены на прямые вызовы реальных production-методов (`debitBet`, `calculateDiceRewards`, `calculateRouletteRewards`, `calculateDeckCashoutRewards`).
  - Аналитическое совпадение математического ожидания и симуляций Monte Carlo в пределах доверительного интервала.

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
