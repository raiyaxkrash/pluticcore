# Отчёт о развёртывании Pocket Odds

## Информация об артефакте
- **Идентификатор мода**: `pocketodds`
- **Версия мода**: `1.0.0`
- **Целевая платформа**: Minecraft 1.20.1, Forge 47.2.0+
- **Имя файла**: `pocketodds-1.0.0.jar`
- **Размер файла**: `234 956` байт
- **Контрольная сумма SHA-256**: `6447FAAFAB68A9428D7DD7EA25A60348801DBB7BB93ACDBBD5B88553F7B50499`
- **Дата сборки и проверки**: `2026-09-21`

---

## Развёртывание в PrismLauncher
- **Экземпляр PrismLauncher**: `All the Mods 9 - To the Sky - atm9sky`
- **Относительный путь к моду**: `minecraft/mods/pocketodds-1.0.0.jar`
- **Проверка дубликатов**: В каталоге `minecraft/mods` проверено отсутствие других или устаревших версий `pocketodds-*.jar`.
- **Сверка хеш-суммы**: Хеш скопированного в экземпляр JAR полностью совпадает со сборочным артефактом (`6447FAAFAB68A9428D7DD7EA25A60348801DBB7BB93ACDBBD5B88553F7B50499`).

---

## Результаты многоуровневой верификации

### 1. Автоматическое тестирование (`gradlew test`)
- Наборы тестов: `net.pocketodds.CasinoGuiTest`, `net.pocketodds.MechanicsTest`, `net.pocketodds.ConfigParserTest`, `net.pocketodds.ItemBettingTest`, `net.pocketodds.RtpSimulationTest`
- Пройдено тестов: **62 из 62** (`BUILD SUCCESSFUL`, 100% success rate).
- Проверено:
  - Единый предмет `Pocket Casino` (`pocket_casino`) с полноценным графическим интерфейсом и вкладками для всех 4 игр и джекпота.
  - Предмет `Coin Pouch` (`coin_pouch`) с собственным инвентарным GUI для хранения, пополнения, вывода и атомарного списания фишек всех 4 номиналов.
  - Дедупликация и защита от спама C2S сетевых пакетов (`operationId`).
  - Полноценная клиентская анимация (вращение барабанов, колесо рулетки, бросок костей, вытягивание карт) только ПОСЛЕ фиксации исхода в серверном transactional outbox.
  - Сохранение 4-фазного 2PC жизненного цикла предметных и фишечных ставок (`PREPARED` -> `DEBITED` -> `COMMITTED` -> `REFUND_QUEUED`).
  - Редирект старых предметов (`PocketSlotItem`, `RouletteTokenItem`, `VoidDiceItem`, `DeckOfFateItem`) в единый GUI с аннотацией `@Deprecated`.
  - Валидация предмета в выделенном слоте ставок (`SlotItemBet`): запрет контейнеров (Shulker, Bundle), повреждённых предметов и NBT/зачарований.
  - Строгая инкапсуляция списания ставок: `debitBet` в production требует `ServerPlayer != null` и физически списывает предметы; внутренний переход состояния `applyDebitTransition` сделан package-private, а для тестов вынесен вспомогательный `TestBetHelper` в тестовом пакете.
  - Персистентный журнал квитанций завершённых выплат (`completedReceipts`) и статус `DELIVERED` в `DeckSession`, исключающие повторное начисление уже доставленных наград при перезапуске сервера после сбоя.
  - Корректное формирование сообщений кэшаута Колоды из фактически выданных предметов `cashoutItems` (`pocketodds.deck.cashed_out_items`) для всех режимов (`SAME_ITEM`, `REWARD_TABLE`, `BOTH`), а также отдельное сообщение возврата страховки при проклятии.
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
