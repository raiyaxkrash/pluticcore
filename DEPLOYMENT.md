# Отчёт о развёртывании Pocket Odds

## Информация об артефакте
- **Идентификатор мода**: `pocketodds`
- **Версия мода**: `1.0.0`
- **Целевая платформа**: Minecraft 1.20.1, Forge 47.2.0+
- **Имя файла**: `pocketodds-1.0.0.jar`
- **Размер файла**: `158 784` байт
- **Контрольная сумма SHA-256**: `791FDC50A2AF3E70705923550745B82A1FE62D0FE42E52ECDB77C14608B7D660`
- **Дата сборки и проверки**: `2026-09-21`

---

## Развёртывание в PrismLauncher
- **Экземпляр PrismLauncher**: `All the Mods 9 - To the Sky - atm9sky`
- **Относительный путь к моду**: `minecraft/mods/pocketodds-1.0.0.jar`
- **Проверка дубликатов**: В каталоге `minecraft/mods` проверено отсутствие других или устаревших версий `pocketodds-*.jar`.
- **Сверка хеш-суммы**: Хеш скопированного в экземпляр JAR полностью совпадает со сборочным артефактом (`791FDC50A2AF3E70705923550745B82A1FE62D0FE42E52ECDB77C14608B7D660`).

---

## Результаты многоуровневой верификации

### 1. Автоматическое тестирование (`gradlew test`)
- Наборы тестов: `net.pocketodds.MechanicsTest`, `net.pocketodds.ConfigParserTest`, `net.pocketodds.ItemBettingTest`, `net.pocketodds.RtpSimulationTest`
- Пройдено тестов: **49 из 49** (`BUILD SUCCESSFUL`, 100% success rate).
- Проверено:
  - 4-фазный 2PC жизненный цикл предметных ставок (`PREPARED` -> `DEBITED` -> `COMMITTED` -> `REFUND_QUEUED`).
  - Связывание `BetPreparation` с детерминированным `associatedId` (rollId, sessionId, txId) с исключением одновременного возврата `DEBITED` ставки и выплаты выигрыша при crash recovery.
  - Строго идемпотентный кэшаут Колоды Судьбы с терминальным статусом `CASHOUT_COMMITTED`, детерминированным `cashoutTxId` и сохранением состояния в `JackpotSavedData` до выдачи наград.
  - Построчная идентификация наград через `UUID lineId` (`RewardLine`) без коллизий одинаковых стаков.
  - Персистентные серверные сессии `DeckSession` в `JackpotSavedData` с валидацией владельца через `DeckOfFateItem.isSessionOwner()`.
  - Идемпотентный пре-коммит исходов мгновенных игр (`VoidDiceItem`, `RouletteTokenItem`) в outbox до выдачи наград.
  - Превращение Жетона рулетки в многоразовый инструмент со ставками из второй руки (`offhand`).
  - Расчёт взносов в джекпот через целочисленные basis points (`(credits * bps) / 10000L`) и защита от переполнения через `Math.multiplyExact()`.
  - Валидация предметов для ставок: запрет контейнеров (shulker box, bundle, chest with items), зачарований, урона и NBT (при `allowNbt=false`).
  - Масштабирование таблицы наград `ItemRewardTable` от кредитного бюджета с учётом множителя исхода (`budgetCredits = Math.round(betCredits * multiplier)`).
  - Режимы выплат `SAME_ITEM`, `REWARD_TABLE`, `BOTH` (80/20) с несмещённым стохастическим округлением (`probabilistic rounding`), устраняющим инфляцию наград на малых ставках (строгое сохранение house edge казино).
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
