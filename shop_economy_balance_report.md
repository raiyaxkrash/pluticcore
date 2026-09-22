# Экономический баланс и ценообразование магазина Pocket Odds (ATM9: To the Sky)

## 1. Валютная система и эквиваленты

В моде Pocket Odds магазин полностью функционирует на базе игровых фишек. Базовой расчетной единицей является **кредит (CR)**:
- **1 Медная фишка (Copper)** = `1 CR`
- **1 Золотая фишка (Gold)** = `8 CR` (8 медяков)
- **1 Алмазная фишка (Diamond)** = `64 CR` (8 золотых)
- **1 Незеритовая фишка (Netherite)** = `512 CR` (8 алмазных)

Все платежи осуществляются с точным списанием и гарантированной выдачей сдачи через Outbox транзакций (`shop_change:<UUID>`).

---

## 2. Ценовые диапазоны по этапам прохождения

### Этап 1: Ранняя игра (Early Game)
- **Диапазон цен**: `16 – 512 CR` (до 1 незеритовой фишки).
- **Целевая аудитория**: Игроки, начавшие развитие на скайблоке, собирающие базовые ресурсы через просеивание и первые механизмы.
- **Товары**:
  - `ato_aluminum_ingot`: 64 CR (1 Diamond)
  - `ato_lead_ingot`: 64 CR (1 Diamond)
  - `ato_nickel_ingot`: 128 CR (2 Diamond)
  - `ato_uranium_ingot`: 256 CR (4 Diamond)
  - `ars_berry_pie`: 64 CR (1 Diamond)
  - `botania_livingwood`: 64 CR (1 Diamond)
  - `mek_basic_circuit`: 512 CR (1 Netherite)
  - `mek_alloy_infused`: 512 CR (1 Netherite)
  - `ironjetpacks_strap`: 512 CR (1 Netherite)
  - `fs_compacting_drawer`: 2048 CR (4 Netherite)
- **Обоснование**: Закрывают рутину ручного крафта базовых механизмов и начальной магии. Легко приобретаются за выигрыши на медных и золотых ставках.

---

### Этап 2: Средняя игра (Mid Game)
- **Диапазон цен**: `512 – 8 192 CR` (1 – 16 незеритовых фишек).
- **Целевая аудитория**: Игроки, автоматизирующие переработку руд, строящие МЭ/RS-сети и создающие реактивные ранцы.
- **Товары**:
  - `mek_advanced_circuit`: 1024 CR (2 Netherite)
  - `mek_alloy_reinforced`: 2048 CR (4 Netherite)
  - `flux_dust`: 256 CR (4 Diamond)
  - `flux_core`: 1024 CR (2 Netherite)
  - `flux_point`: 2048 CR (4 Netherite)
  - `flux_plug`: 2048 CR (4 Netherite)
  - `ironjetpacks_basic_coil`: 1024 CR (2 Netherite)
  - `soph_backpack`: 4096 CR (8 Netherite)
  - `soph_magnet_upgrade`: 8192 CR (16 Netherite)
  - `botania_overgrowth_seed`: 8192 CR (16 Netherite, недельный лимит 2)
  - `atm_apple`: 128 CR (2 Diamond)
  - `atm_nugget`: 512 CR (1 Netherite)
- **Обоснование**: Компоненты для беспроводной логистики и хранения. Защищены недельными лимитами покупки для предотвращения обесценивания автоматизации.

---

### Этап 3: Поздняя игра (Late Game)
- **Диапазон цен**: `8 192 – 131 072 CR` (16 – 256 незеритовых фишек).
- **Целевая аудитория**: Игроки на стадии реакторов и синтеза сплавов эндгейма.
- **Товары**:
  - `mek_ultimate_circuit`: 4096 CR (8 Netherite, недельный лимит 2)
  - `atm_vibranium_ingot`: 32 768 CR (64 Netherite, недельный лимит 4, требует достижение Vibranium)
  - `atm_unobtainium_nugget`: 16 384 CR (32 Netherite, недельный лимит 8, требует достижение Unobtainium)
- **Обоснование**: Редчайшие металлы Allthemods, требующие побед на высоких ставках и прогресса в измерениях The Other / End.

---

### Этап 4: Творческие предметы (Creative Tier)
- **Диапазон цен**: `262 144 – 1 048 576 CR` (512 – 2048 незеритовых фишек).
- **Специальные ограничения**:
  1. Лимит: строго `1` штука на игрока (`PER_PLAYER`).
  2. Достижение: `allthemods:allthemodium/atm_star` (получение Звезды AllTheMods).
  3. UI: Всплывающий модальный диалог с подтверждением покупки.
- **Товары**:
  - `create:creative_motor`: 262 144 CR (512 Netherite) — Бесконечная кинетическая энергия Create.
  - `functionalstorage:creative_vending_upgrade`: 262 144 CR (512 Netherite) — Бесконечный объем ящика.
  - `botania:creative_pool`: 524 288 CR (1024 Netherite) — Неисчерпаемый бассейн маны.
  - `ars_nouveau:creative_source_jar`: 524 288 CR (1024 Netherite) — Бесконечный кувшин источника.
  - `powah:energy_cell_creative`: 524 288 CR (1024 Netherite) — Неограниченный FE источник энергии.
  - `pneumaticcraft:creative_compressor`: 524 288 CR (1024 Netherite) — Мгновенное максимальное давление.
  - `refinedstorage:creative_controller`: 1 048 576 CR (2048 Netherite) — Бесплатное питание сети RS.
  - `ae2:creative_energy_cell`: 1 048 576 CR (2048 Netherite) — Бесплатное питание сети AE2.
  - `mekanism:creative_energy_cube`: 1 048 576 CR (2048 Netherite) — Абсолютный энергокуб Mekanism.
- **Обоснование**: Все указанные творческие предметы проверены по скриптам сборки `atm_star_creative.js`. Они не вызывают сбоев сервера и дюпов инвентаря, но дают колоссальную мощь в эндгейме. Игрок может получить их только после крафта первой звезды ATM, выиграв джекпот или накопив состояние в казино.
