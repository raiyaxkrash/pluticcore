package net.pocketodds.service;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.gambling.core.*;
import net.pocketodds.gambling.itembet.ItemBetConfigEntry;
import net.pocketodds.gambling.itembet.ItemBetRegistry;
import net.pocketodds.gambling.itembet.ItemBetValidator;
import net.pocketodds.gambling.slot.SlotEvaluator;
import net.pocketodds.gambling.slot.SlotOutcome;
import net.pocketodds.gambling.slot.SlotRewardFactory;
import net.pocketodds.gambling.slot.SlotSymbol;
import net.pocketodds.gui.casino.BetFundingSource;
import net.pocketodds.gui.casino.PayoutDestination;
import net.pocketodds.gui.casino.PocketCasinoMenu;
import net.pocketodds.gui.casino.RouletteBetType;
import net.pocketodds.item.*;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.s2c.CasinoResultSyncS2CPacket;
import net.pocketodds.network.s2c.CasinoStateSyncS2CPacket;
import net.pocketodds.registration.ModItems;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import net.pocketodds.util.RewardDeliverySink;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CasinoGameService {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Set<UUID> processedOperations = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();

    private static final Set<Integer> RED_NUMBERS = Set.of(
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
    );

    public static synchronized boolean checkAndRecordOperation(UUID operationId) {
        if (operationId == null) return false;
        if (!processedOperations.add(operationId)) {
            return false; // Duplicate operation rejected
        }
        if (processedOperations.size() > 10000) {
            Iterator<UUID> it = processedOperations.iterator();
            for (int i = 0; i < 2000 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        return true;
    }

    public static boolean isOnCooldown(UUID playerId) {
        if (playerId == null) return false;
        Long lastTime = playerCooldowns.get(playerId);
        if (lastTime == null) return false;
        return System.currentTimeMillis() - lastTime < 1000L; // 1 second base cooldown between actions
    }

    public static void setCooldown(UUID playerId) {
        if (playerId != null) {
            playerCooldowns.put(playerId, System.currentTimeMillis());
        }
    }

    public static void syncCasinoState(ServerPlayer player) {
        if (player == null) return;
        JackpotSavedData jackpotData = JackpotSavedData.get(player.serverLevel());
        long jackpotAmount = jackpotData.getJackpotAmount();

        ItemStack pouch = CoinPouchService.findFirstPouch(player);
        long pouchCredits = 0L;
        if (pouch != null && !pouch.isEmpty()) {
            UUID pouchUUID = CoinPouchService.getOrCreatePouchUUID(pouch);
            net.pocketodds.data.PouchBalance balance = jackpotData.getOrCreatePouchBalance(pouchUUID, pouch);
            pouchCredits = balance.getTotalCredits();
        }

        long invCredits = CoinPouchService.getInventoryChipCredits(player);

        int streak = 0;
        int potUnits = 0;
        DeckSession deckSession = jackpotData.getActiveDeckSession(player.getUUID());
        if (deckSession != null && deckSession.isActive()) {
            streak = deckSession.getStreak();
            potUnits = deckSession.getPotUnits();
        }

        int[] history = jackpotData.getRecentRouletteHistory();

        ModMessages.sendToPlayer(new CasinoStateSyncS2CPacket(jackpotAmount, pouchCredits, invCredits, streak, potUnits, history), player);
        net.pocketodds.shop.ShopService.syncShopToPlayer(player, jackpotData);
    }

    public static void onPlayerOpenCasino(ServerPlayer player) {
        if (player == null) return;
        syncCasinoState(player);
        JackpotSavedData jackpotData = JackpotSavedData.get(player.serverLevel());
        CasinoGameSession session = jackpotData.getLastGameSession(player.getUUID());
        if (session != null && session.getResult() != null) {
            long elapsedMs = Math.max(0L, System.currentTimeMillis() - session.getGameTimestamp());
            if (elapsedMs <= JackpotSavedData.SESSION_TTL_MS) {
                long elapsedTicks = elapsedMs / 50L;
                int remainingTicks = (int) Math.max(0L, session.getAnimDurationTicks() - elapsedTicks);
                ModMessages.sendToPlayer(new CasinoResultSyncS2CPacket(session.getResult(), remainingTicks), player);
            } else {
                jackpotData.removeLastGameSession(player.getUUID());
            }
        }
    }

    public static BetSnapshot resolveBet(ServerPlayer player, PocketCasinoMenu menu, GameType gameType) {
        if (player == null || menu == null) return null;

        if (menu.getBetFundingSource() == BetFundingSource.ITEM_SLOT) {
            ItemStack item = menu.getBetItem();
            if (item.isEmpty()) return null;

            ItemBetConfigEntry entry = ItemBetRegistry.getEntry(ForgeRegistries.ITEMS.getKey(item.getItem()));
            if (entry == null) {
                return null;
            }

            int count = Math.min(item.getCount(), menu.getBetCount());
            ItemBetValidator.ValidationResult validation = ItemBetValidator.validate(item, count, entry, gameType);
            if (validation != ItemBetValidator.ValidationResult.VALID) {
                return null;
            }

            return BetSnapshot.fromItem(item, count, entry.getUnitCreditValue());
        } else {
            ChipTier tier = menu.getSelectedChipTier();
            int count = menu.getBetCount();
            return BetSnapshot.fromChip(tier != null ? tier : ChipTier.COPPER, Math.max(1, count));
        }
    }

    public static boolean debitBetForMenu(ServerPlayer player, PocketCasinoMenu menu, BetPreparation prep, JackpotSavedData jackpotData) {
        if (player == null || menu == null || prep == null || jackpotData == null) return false;

        BetSnapshot bet = prep.getBetSnapshot();
        if (menu.getBetFundingSource() == BetFundingSource.ITEM_SLOT) {
            ItemStack inSlot = menu.getBetItem();
            if (inSlot.isEmpty() || inSlot.getCount() < bet.getBetCount()) {
                jackpotData.removePreparedBet(prep.getPreparationId());
                return false;
            }
            inSlot.shrink(bet.getBetCount());
            if (inSlot.isEmpty()) {
                menu.setBetItem(ItemStack.EMPTY);
            }
            menu.broadcastChanges();
            return RewardTransactionService.applyDebitTransition(prep, jackpotData);
        } else {
            ItemStack pouch = CoinPouchService.findFirstPouch(player);
            boolean ok = CoinPouchService.debitCredits(player, pouch, bet.getTotalCreditValue(), menu.getBetFundingSource(), jackpotData);
            if (!ok) {
                jackpotData.removePreparedBet(prep.getPreparationId());
                return false;
            }
            return RewardTransactionService.applyDebitTransition(prep, jackpotData);
        }
    }

    public static void spinSlots(ServerPlayer player, PocketCasinoMenu menu, UUID operationId) {
        if (!checkAndRecordOperation(operationId)) return;
        if (isOnCooldown(player.getUUID())) return;
        setCooldown(player.getUUID());

        JackpotSavedData jackpotData = JackpotSavedData.get(player.serverLevel());
        BetSnapshot bet = resolveBet(player, menu, GameType.SLOT);
        if (bet == null) {
            sendErrorResult(player, GameType.SLOT, operationId, "pocketodds.gui.error.invalid_bet");
            return;
        }

        BetPreparation prep = RewardTransactionService.prepareBet(player, GameType.SLOT, bet, jackpotData);
        boolean debited = debitBetForMenu(player, menu, prep, jackpotData);
        if (!debited) {
            sendErrorResult(player, GameType.SLOT, operationId, "pocketodds.gui.error.insufficient_funds");
            return;
        }

        // Calculate slot outcome
        SlotSymbol[] symbols = new SlotSymbol[]{
                SlotSymbol.getRandomSymbol(player.level().random, PocketOddsConfig.SERVER),
                SlotSymbol.getRandomSymbol(player.level().random, PocketOddsConfig.SERVER),
                SlotSymbol.getRandomSymbol(player.level().random, PocketOddsConfig.SERVER)
        };
        SlotOutcome outcome = SlotEvaluator.evaluate(symbols, PocketOddsConfig.SERVER);

        // Joker rescue
        boolean jokerRescued = false;
        if (InventoryUtils.hasJoker(player)) {
            SlotEvaluator.JokerRescueResult rescue = SlotEvaluator.tryRescueWithJoker(symbols, bet.getBetCount(), PocketOddsConfig.SERVER);
            if (rescue.isRescued()) {
                InventoryUtils.consumeJoker(player);
                symbols = rescue.getSymbols();
                outcome = rescue.getOutcome();
                jokerRescued = true;
            }
        }

        // Insurance check
        boolean insured = false;
        if (!outcome.isSkulls() && !outcome.isWin() && !outcome.isJackpot() && InventoryUtils.hasInsurance(player)) {
            InventoryUtils.consumeInsurance(player);
            insured = true;
        }

        // Check 3 Skulls curse
        boolean curse = outcome.isSkulls();
        if (curse) {
            player.addEffect(new MobEffectInstance(MobEffects.WITHER, 160, 0));
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 200, 0));
        }

        // Check Jackpot
        boolean isJackpot = outcome.isJackpot();
        long wonJackpotAmount = 0L;
        if (isJackpot) {
            wonJackpotAmount = jackpotData.getJackpotAmount();
        }

        // Build rewards
        List<ItemStack> rewards = SlotRewardFactory.createRewards(player, outcome, bet, wonJackpotAmount, insured, PocketOddsConfig.SERVER);
        RewardBundle bundle = new RewardBundle(rewards, wonJackpotAmount, isJackpot);

        // Settle & commit in outbox atomically
        UUID rollId = UUID.randomUUID();
        prep.setAssociatedId(rollId);
        jackpotData.settleAndCommitBet(prep, rollId, player.getUUID(), bundle, menu.getPayoutDestination());
        if (isJackpot) {
            jackpotData.claimJackpotForTransaction(rollId);
        }

        // Deliver rewards
        deliverGameReward(player, rollId, menu, jackpotData);

        // Build result & send to client
        CasinoGameResult result = new CasinoGameResult.Builder(GameType.SLOT)
                .operationId(operationId)
                .success(true)
                .slotSymbols(symbols)
                .multiplier(outcome.getMultiplier())
                .wonAmount(Math.round(bet.getBetCount() * outcome.getMultiplier()))
                .jackpot(isJackpot)
                .insured(insured)
                .curse(curse)
                .rewards(rewards)
                .build();

        // Save persistent game session for GUI crash/reconnect/reopen recovery
        CasinoGameSession gameSession = new CasinoGameSession(player.getUUID(), operationId, GameType.SLOT, result, System.currentTimeMillis(), 40);
        jackpotData.saveLastGameSession(player.getUUID(), gameSession);

        menu.setLastResult(result);
        ModMessages.sendToPlayer(new CasinoResultSyncS2CPacket(result), player);
        syncCasinoState(player);
    }

    public static void spinRoulette(ServerPlayer player, PocketCasinoMenu menu, RouletteBetType betType, UUID operationId) {
        if (!checkAndRecordOperation(operationId)) return;
        if (isOnCooldown(player.getUUID())) return;
        setCooldown(player.getUUID());

        JackpotSavedData jackpotData = JackpotSavedData.get(player.serverLevel());
        BetSnapshot bet = resolveBet(player, menu, GameType.ROULETTE);
        if (bet == null) {
            sendErrorResult(player, GameType.ROULETTE, operationId, "pocketodds.gui.error.invalid_bet");
            return;
        }

        BetPreparation prep = RewardTransactionService.prepareBet(player, GameType.ROULETTE, bet, jackpotData);
        boolean debited = debitBetForMenu(player, menu, prep, jackpotData);
        if (!debited) {
            sendErrorResult(player, GameType.ROULETTE, operationId, "pocketodds.gui.error.insufficient_funds");
            return;
        }

        int number = player.level().random.nextInt(37);
        boolean isZero = (number == 0);
        boolean isRed = RED_NUMBERS.contains(number);

        boolean won = false;
        double mult = 0.0;
        if (betType == RouletteBetType.ZERO && isZero) {
            won = true;
            mult = 35.0;
        } else if (betType == RouletteBetType.RED && isRed) {
            won = true;
            mult = 2.0;
        } else if (betType == RouletteBetType.BLACK && !isRed && !isZero) {
            won = true;
            mult = 2.0;
        }

        boolean insured = false;
        List<ItemStack> rewardItems = new ArrayList<>();
        if (won) {
            rewardItems.addAll(RouletteTokenItem.calculateRouletteRewards(bet, mult));
        } else if (InventoryUtils.hasInsurance(player)) {
            InventoryUtils.consumeInsurance(player);
            insured = true;
            double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
            int refund = Math.max(1, (int) Math.round(bet.getBetCount() * rate));
            if (bet.isChipBet()) {
                rewardItems.addAll(ChipUtils.splitChips(bet.getChipTier().getItem(), refund));
            } else {
                rewardItems.addAll(ChipUtils.splitChips(bet.getItemPrototype().getItem(), refund));
            }
        }

        // Record history in persistent JackpotSavedData
        jackpotData.addRouletteNumber(number);

        RewardBundle bundle = new RewardBundle(rewardItems, 0L, false);
        jackpotData.settleAndCommitBet(prep, prep.getPreparationId(), player.getUUID(), bundle, menu.getPayoutDestination());
        deliverGameReward(player, prep.getPreparationId(), menu, jackpotData);

        CasinoGameResult result = new CasinoGameResult.Builder(GameType.ROULETTE)
                .operationId(operationId)
                .success(true)
                .rouletteNumber(number)
                .multiplier(mult)
                .wonAmount(Math.round(bet.getBetCount() * mult))
                .insured(insured)
                .rewards(rewardItems)
                .build();

        // Save persistent game session for GUI recovery
        CasinoGameSession gameSession = new CasinoGameSession(player.getUUID(), operationId, GameType.ROULETTE, result, System.currentTimeMillis(), 40);
        jackpotData.saveLastGameSession(player.getUUID(), gameSession);

        menu.setLastResult(result);
        ModMessages.sendToPlayer(new CasinoResultSyncS2CPacket(result), player);
        syncCasinoState(player);
    }

    public static void rollDice(ServerPlayer player, PocketCasinoMenu menu, UUID operationId) {
        if (!checkAndRecordOperation(operationId)) return;
        if (isOnCooldown(player.getUUID())) return;
        setCooldown(player.getUUID());

        JackpotSavedData jackpotData = JackpotSavedData.get(player.serverLevel());
        BetSnapshot bet = resolveBet(player, menu, GameType.DICE);
        if (bet == null) {
            sendErrorResult(player, GameType.DICE, operationId, "pocketodds.gui.error.invalid_bet");
            return;
        }

        BetPreparation prep = RewardTransactionService.prepareBet(player, GameType.DICE, bet, jackpotData);
        boolean debited = debitBetForMenu(player, menu, prep, jackpotData);
        if (!debited) {
            sendErrorResult(player, GameType.DICE, operationId, "pocketodds.gui.error.insufficient_funds");
            return;
        }

        int d1 = player.level().random.nextInt(6) + 1;
        int d2 = player.level().random.nextInt(6) + 1;
        int sum = d1 + d2;
        boolean isDouble = (d1 == d2);
        boolean isSnakeEyes = (d1 == 1 && d2 == 1);

        double mult = 0.0;
        boolean isWin = false;
        boolean insured = false;
        List<ItemStack> rewardItems = new ArrayList<>();

        if (isSnakeEyes) {
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 120, 0));
            player.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 0));
            if (InventoryUtils.hasInsurance(player)) {
                InventoryUtils.consumeInsurance(player);
                insured = true;
                double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                int refund = Math.max(1, (int) Math.round(bet.getBetCount() * rate));
                rewardItems.addAll(ChipUtils.splitChips(bet.isChipBet() ? bet.getChipTier().getItem() : bet.getItemPrototype().getItem(), refund));
            }
        } else if (sum == 7) {
            isWin = true;
            mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.diceLuckySevenPayout.get() : 3.0;
            rewardItems.addAll(VoidDiceItem.calculateDiceRewards(bet, mult));
        } else if (isDouble) {
            isWin = true;
            mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.diceDoublePayout.get() : 2.0;
            rewardItems.addAll(VoidDiceItem.calculateDiceRewards(bet, mult));
        } else if (sum == 11) {
            isWin = true;
            mult = 2.0;
            rewardItems.addAll(VoidDiceItem.calculateDiceRewards(bet, mult));
        } else if (InventoryUtils.hasInsurance(player)) {
            InventoryUtils.consumeInsurance(player);
            insured = true;
            double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
            int refund = Math.max(1, (int) Math.round(bet.getBetCount() * rate));
            rewardItems.addAll(ChipUtils.splitChips(bet.isChipBet() ? bet.getChipTier().getItem() : bet.getItemPrototype().getItem(), refund));
        }

        RewardBundle bundle = new RewardBundle(rewardItems, 0L, false);
        jackpotData.settleAndCommitBet(prep, prep.getPreparationId(), player.getUUID(), bundle, menu.getPayoutDestination());
        deliverGameReward(player, prep.getPreparationId(), menu, jackpotData);

        CasinoGameResult result = new CasinoGameResult.Builder(GameType.DICE)
                .operationId(operationId)
                .success(true)
                .dice(d1, d2)
                .multiplier(mult)
                .wonAmount(Math.round(bet.getBetCount() * mult))
                .insured(insured)
                .curse(isSnakeEyes)
                .rewards(rewardItems)
                .build();

        // Save persistent game session for GUI recovery
        CasinoGameSession gameSession = new CasinoGameSession(player.getUUID(), operationId, GameType.DICE, result, System.currentTimeMillis(), 20);
        jackpotData.saveLastGameSession(player.getUUID(), gameSession);

        menu.setLastResult(result);
        ModMessages.sendToPlayer(new CasinoResultSyncS2CPacket(result), player);
        syncCasinoState(player);
    }

    public static void drawDeckCard(ServerPlayer player, PocketCasinoMenu menu, UUID operationId) {
        if (!checkAndRecordOperation(operationId)) return;
        if (isOnCooldown(player.getUUID())) return;
        setCooldown(player.getUUID());

        JackpotSavedData jackpotData = JackpotSavedData.get(player.serverLevel());
        DeckSession session = jackpotData.getActiveDeckSession(player.getUUID());

        if (session == null || !session.isActive()) {
            // Start new session
            BetSnapshot bet = resolveBet(player, menu, GameType.DECK);
            if (bet == null) {
                sendErrorResult(player, GameType.DECK, operationId, "pocketodds.gui.error.invalid_bet");
                return;
            }

            BetPreparation prep = RewardTransactionService.prepareBet(player, GameType.DECK, bet, jackpotData);
            boolean debited = debitBetForMenu(player, menu, prep, jackpotData);
            if (!debited) {
                sendErrorResult(player, GameType.DECK, operationId, "pocketodds.gui.error.insufficient_funds");
                return;
            }

            session = new DeckSession(player.getUUID(), bet);
            prep.setAssociatedId(session.getSessionId());
            jackpotData.savePreparedBet(prep);
            jackpotData.saveDeckSession(session);
            RewardTransactionService.commitBet(prep, jackpotData);
        }

        // Draw card
        int roll = player.level().random.nextInt(100);
        int streak = session.getStreak();
        int potUnits = session.getPotUnits();
        BetSnapshot bet = session.getInitialBet();

        boolean curse = false;
        boolean insured = false;
        List<ItemStack> rewardItems = new ArrayList<>();

        if (roll < 35) {
            // Curse
            curse = true;
            if (InventoryUtils.hasInsurance(player)) {
                InventoryUtils.consumeInsurance(player);
                insured = true;
                double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                int refundCount = Math.max(1, (int) Math.round(potUnits * bet.getBetCount() * rate));
                rewardItems.addAll(ChipUtils.splitChips(bet.isChipBet() ? bet.getChipTier().getItem() : bet.getItemPrototype().getItem(), refundCount));
                RewardBundle bundle = new RewardBundle(rewardItems, 0L, false);
                UUID insTxId = RewardTransactionService.enqueueRewardBundle(jackpotData, player.getUUID(), bundle, menu.getPayoutDestination());
                deliverGameReward(player, insTxId, menu, jackpotData);
            } else {
                ItemStack cursedCard = new ItemStack(ModItems.CURSED_CARD.get(), 1);
                RewardBundle curseBundle = new RewardBundle(List.of(cursedCard), 0L, false);
                UUID curseTxId = RewardTransactionService.enqueueRewardBundle(jackpotData, player.getUUID(), curseBundle, menu.getPayoutDestination());
                deliverGameReward(player, curseTxId, menu, jackpotData);
                rewardItems.add(cursedCard);
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 0));
                player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, 0));
            }
            session.setStatus(DeckSession.Status.BUSTED);
            session.setActive(false);
            jackpotData.removeDeckSession(session.getSessionId());
            potUnits = 0;
        } else if (roll < 70) {
            // Patience
            streak++;
            session.setStreak(streak);
            jackpotData.saveDeckSession(session);
        } else if (roll < 88) {
            // Fortune (1.25x)
            double mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.deckFortuneMultiplier.get() : 1.25;
            potUnits = (potUnits <= 1) ? 2 : (int) Math.round(potUnits * mult);
            streak++;
            session.setPotUnits(potUnits);
            session.setStreak(streak);
            jackpotData.saveDeckSession(session);
        } else if (roll < 94) {
            // Riches (1.5x)
            double mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.deckRichesMultiplier.get() : 1.5;
            potUnits = (potUnits <= 1) ? 2 : (int) Math.round(potUnits * mult);
            streak++;
            session.setPotUnits(potUnits);
            session.setStreak(streak);
            jackpotData.saveDeckSession(session);
        } else if (roll < 97) {
            // Joker
            potUnits += 1;
            streak++;
            session.setPotUnits(potUnits);
            session.setStreak(streak);
            jackpotData.saveDeckSession(session);
            ItemStack jokerStack = new ItemStack(ModItems.JOKER.get(), 1);
            RewardBundle jokerBundle = new RewardBundle(List.of(jokerStack), 0L, false);
            UUID jokerTxId = RewardTransactionService.enqueueRewardBundle(jackpotData, player.getUUID(), jokerBundle, menu.getPayoutDestination());
            deliverGameReward(player, jokerTxId, menu, jackpotData);
            rewardItems.add(jokerStack);
        } else {
            // Guardian
            potUnits += 1;
            streak++;
            session.setPotUnits(potUnits);
            session.setStreak(streak);
            jackpotData.saveDeckSession(session);
            ItemStack insStack = new ItemStack(ModItems.INSURANCE.get(), 1);
            RewardBundle insBundle = new RewardBundle(List.of(insStack), 0L, false);
            UUID guardTxId = RewardTransactionService.enqueueRewardBundle(jackpotData, player.getUUID(), insBundle, menu.getPayoutDestination());
            deliverGameReward(player, guardTxId, menu, jackpotData);
            rewardItems.add(insStack);
        }

        CasinoGameResult result = new CasinoGameResult.Builder(GameType.DECK)
                .operationId(operationId)
                .success(true)
                .deck(streak, potUnits)
                .curse(curse)
                .insured(insured)
                .rewards(rewardItems)
                .build();

        // Save persistent game session for GUI recovery
        CasinoGameSession gameSession = new CasinoGameSession(player.getUUID(), operationId, GameType.DECK, result, System.currentTimeMillis(), 15);
        jackpotData.saveLastGameSession(player.getUUID(), gameSession);

        menu.setLastResult(result);
        ModMessages.sendToPlayer(new CasinoResultSyncS2CPacket(result), player);
        syncCasinoState(player);
    }

    public static void cashOutDeck(ServerPlayer player, PocketCasinoMenu menu, UUID operationId) {
        if (!checkAndRecordOperation(operationId)) return;
        if (isOnCooldown(player.getUUID())) return;
        setCooldown(player.getUUID());

        JackpotSavedData jackpotData = JackpotSavedData.get(player.serverLevel());
        DeckSession session = jackpotData.getActiveDeckSession(player.getUUID());

        if (session == null || !session.isActive() || session.getPotUnits() <= 0) {
            sendErrorResult(player, GameType.DECK, operationId, "pocketodds.gui.error.no_active_deck");
            return;
        }

        List<ItemStack> cashoutItems = DeckOfFateItem.calculateDeckCashoutRewards(session);
        UUID cashoutTxId = UUID.nameUUIDFromBytes(("deck_cashout:" + session.getSessionId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RewardBundle bundle = new RewardBundle(cashoutItems, 0L, false);

        jackpotData.commitDeckCashout(session.getSessionId(), cashoutTxId, player.getUUID(), bundle, menu.getPayoutDestination());
        deliverGameReward(player, cashoutTxId, menu, jackpotData);
        jackpotData.markDeckSessionDelivered(session.getSessionId());

        CasinoGameResult result = new CasinoGameResult.Builder(GameType.DECK)
                .operationId(operationId)
                .success(true)
                .deck(session.getStreak(), 0)
                .wonAmount(session.getPotUnits() * session.getInitialBet().getBetCount())
                .rewards(cashoutItems)
                .build();

        // Save persistent game session for GUI recovery
        CasinoGameSession gameSession = new CasinoGameSession(player.getUUID(), operationId, GameType.DECK, result, System.currentTimeMillis(), 0);
        jackpotData.saveLastGameSession(player.getUUID(), gameSession);

        menu.setLastResult(result);
        ModMessages.sendToPlayer(new CasinoResultSyncS2CPacket(result), player);
        syncCasinoState(player);
    }

    public static void deliverGameReward(ServerPlayer player, UUID targetTxId, PocketCasinoMenu menu, JackpotSavedData jackpotData) {
        deliverGameReward(player, targetTxId, menu, jackpotData, InventoryUtils::giveOrDrop);
    }

    public static void deliverGameReward(ServerPlayer player, UUID targetTxId, PocketCasinoMenu menu, JackpotSavedData jackpotData, RewardDeliverySink sink) {
        ItemStack pouch = (player != null) ? CoinPouchService.findFirstPouch(player) : ItemStack.EMPTY;
        deliverGameReward(player, pouch, targetTxId, menu, jackpotData, sink);
    }

    public static void deliverGameReward(ServerPlayer player, ItemStack pouch, UUID targetTxId, PocketCasinoMenu menu, JackpotSavedData jackpotData, RewardDeliverySink sink) {
        if (jackpotData == null || targetTxId == null) return;
        RewardTransactionService.deliverTransaction(targetTxId, player, pouch, jackpotData, sink);
    }

    private static void sendErrorResult(ServerPlayer player, GameType type, UUID operationId, String errorKey) {
        CasinoGameResult result = new CasinoGameResult.Builder(type)
                .operationId(operationId)
                .success(false)
                .messageKey(errorKey)
                .build();
        ModMessages.sendToPlayer(new CasinoResultSyncS2CPacket(result), player);
        FeedbackEffects.sendActionBar(player, Component.translatable(errorKey).withStyle(ChatFormatting.RED));
        syncCasinoState(player);
    }
}