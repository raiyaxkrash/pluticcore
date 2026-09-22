package net.pocketodds.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.gambling.core.BetPreparation;
import net.pocketodds.gambling.core.CasinoGameSession;
import net.pocketodds.gambling.core.DeckSession;
import net.pocketodds.gambling.core.PouchDepositEscrow;
import net.pocketodds.gambling.core.RewardBundle;
import net.pocketodds.gambling.core.RewardLine;
import net.pocketodds.gui.casino.PayoutDestination;
import net.pocketodds.item.ChipItem;
import net.pocketodds.item.ChipTier;
import net.pocketodds.item.DeckOfFateItem;
import net.pocketodds.shop.ShopLimitPeriod;
import net.pocketodds.shop.ShopPurchaseTransaction;
import net.pocketodds.util.ChipUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class JackpotSavedData extends SavedData {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    public static final String DATA_NAME = "pocketodds_jackpot";
    private long jackpotAmount;

    // Transactional outbox for rewards
    private final List<RewardTransaction> pendingTransactions = new ArrayList<>();
    private final Map<UUID, CompoundTag> activeSessions = new HashMap<>();

    // 2PC prepared bets and server-side deck sessions
    private final Map<UUID, BetPreparation> preparedBets = new ConcurrentHashMap<>();
    private final Map<UUID, DeckSession> deckSessions = new ConcurrentHashMap<>();
    private final Set<UUID> completedReceipts = ConcurrentHashMap.newKeySet();

    // Authoritative server-side pouch balances (pouchUUID -> PouchBalance)
    private final Map<UUID, PouchBalance> pouchBalances = new ConcurrentHashMap<>();
    private final Map<UUID, PouchDepositEscrow> pendingDeposits = new ConcurrentHashMap<>();

    // Casino GUI: Last game sessions for replay/animation recovery (playerUUID -> CasinoGameSession)
    private final Map<UUID, CasinoGameSession> lastPlayerSessions = new ConcurrentHashMap<>();

    // Shop transactions and player purchase limits
    private final Map<UUID, ShopPurchaseTransaction> shopPurchases = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, PlayerPurchaseRecord>> playerShopLimits = new ConcurrentHashMap<>();

    // Roulette history (last 10 winning numbers)
    private final List<Integer> rouletteHistory = new ArrayList<>();

    public static class PlayerPurchaseRecord {
        private int count;
        private long lastEpochDay;
        private long lastEpochWeek;

        public PlayerPurchaseRecord(int count, long lastEpochDay, long lastEpochWeek) {
            this.count = count;
            this.lastEpochDay = lastEpochDay;
            this.lastEpochWeek = lastEpochWeek;
        }

        public int getCount() { return count; }
        public long getLastEpochDay() { return lastEpochDay; }
        public long getLastEpochWeek() { return lastEpochWeek; }

        public void increment(long currentDay, long currentWeek, ShopLimitPeriod period) {
            if (period == ShopLimitPeriod.DAILY) {
                if (lastEpochDay != currentDay) {
                    count = 0;
                    lastEpochDay = currentDay;
                }
            } else if (period == ShopLimitPeriod.WEEKLY) {
                if (lastEpochWeek != currentWeek) {
                    count = 0;
                    lastEpochWeek = currentWeek;
                }
            }
            this.count++;
            this.lastEpochDay = currentDay;
            this.lastEpochWeek = currentWeek;
        }

        public int getEffectiveCount(long currentDay, long currentWeek, ShopLimitPeriod period) {
            if (period == ShopLimitPeriod.DAILY) {
                if (lastEpochDay != currentDay) return 0;
            } else if (period == ShopLimitPeriod.WEEKLY) {
                if (lastEpochWeek != currentWeek) return 0;
            }
            return count;
        }

        public CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Count", count);
            tag.putLong("LastDay", lastEpochDay);
            tag.putLong("LastWeek", lastEpochWeek);
            return tag;
        }

        public static PlayerPurchaseRecord fromNbt(CompoundTag tag) {
            return new PlayerPurchaseRecord(
                    tag.getInt("Count"),
                    tag.getLong("LastDay"),
                    tag.getLong("LastWeek")
            );
        }
    }

    public static class RewardTransaction {
        private final UUID transactionId;
        private final UUID playerUUID;
        private final List<RewardLine> lines;
        private final boolean jackpot;
        private final long jackpotAmount;
        private boolean jackpotClaimed;
        private PayoutDestination payoutDestination;

        public RewardTransaction(UUID transactionId, UUID playerUUID, List<ItemStack> items) {
            this(transactionId, playerUUID, items, false, 0L, false, PayoutDestination.INVENTORY);
        }

        public RewardTransaction(UUID transactionId, UUID playerUUID, List<ItemStack> items, boolean jackpot, long jackpotAmount, boolean jackpotClaimed) {
            this(transactionId, playerUUID, items, jackpot, jackpotAmount, jackpotClaimed, PayoutDestination.INVENTORY);
        }

        public RewardTransaction(UUID transactionId, UUID playerUUID, List<ItemStack> items, boolean jackpot, long jackpotAmount, boolean jackpotClaimed, PayoutDestination payoutDestination) {
            this.transactionId = transactionId != null ? transactionId : UUID.randomUUID();
            this.playerUUID = playerUUID;
            this.jackpot = jackpot;
            this.jackpotAmount = jackpotAmount;
            this.jackpotClaimed = jackpotClaimed;
            this.payoutDestination = payoutDestination != null ? payoutDestination : PayoutDestination.INVENTORY;
            this.lines = new ArrayList<>();
            if (items != null) {
                for (ItemStack stack : items) {
                    if (stack != null && !stack.isEmpty()) {
                        this.lines.add(new RewardLine(stack));
                    }
                }
            }
        }

        public static RewardTransaction fromLines(UUID transactionId, UUID playerUUID, List<RewardLine> lines, boolean jackpot, long jackpotAmount, boolean jackpotClaimed) {
            return fromLines(transactionId, playerUUID, lines, jackpot, jackpotAmount, jackpotClaimed, PayoutDestination.INVENTORY);
        }

        public static RewardTransaction fromLines(UUID transactionId, UUID playerUUID, List<RewardLine> lines, boolean jackpot, long jackpotAmount, boolean jackpotClaimed, PayoutDestination payoutDestination) {
            RewardTransaction tx = new RewardTransaction(transactionId, playerUUID, Collections.emptyList(), jackpot, jackpotAmount, jackpotClaimed, payoutDestination);
            if (lines != null) {
                for (RewardLine line : lines) {
                    if (line != null && !line.getStack().isEmpty()) {
                        tx.lines.add(new RewardLine(line.getLineId(), line.getStack(), line.isDelivered()));
                    }
                }
            }
            return tx;
        }

        public static RewardTransaction fromBundle(UUID transactionId, UUID playerUUID, RewardBundle bundle) {
            return fromBundle(transactionId, playerUUID, bundle, PayoutDestination.INVENTORY);
        }

        public static RewardTransaction fromBundle(UUID transactionId, UUID playerUUID, RewardBundle bundle, PayoutDestination payoutDestination) {
            if (bundle == null || bundle.isEmpty()) {
                return new RewardTransaction(transactionId, playerUUID, Collections.emptyList(), false, 0L, false, payoutDestination);
            }
            List<ItemStack> items = new ArrayList<>(bundle.getItems());
            return new RewardTransaction(transactionId, playerUUID, items, bundle.isJackpot(), bundle.getJackpotCredits(), false, payoutDestination);
        }

        public UUID getTransactionId() {
            return transactionId;
        }

        public UUID getPlayerUUID() {
            return playerUUID;
        }

        public boolean isJackpot() {
            return jackpot;
        }

        public long getJackpotAmount() {
            return jackpotAmount;
        }

        public boolean isJackpotClaimed() {
            return jackpotClaimed;
        }

        void setJackpotClaimed(boolean claimed) {
            this.jackpotClaimed = claimed;
        }

        public List<RewardLine> getLines() {
            List<RewardLine> copy = new ArrayList<>(lines.size());
            for (RewardLine line : lines) {
                copy.add(new RewardLine(line.getLineId(), line.getStack(), line.isDelivered()));
            }
            return Collections.unmodifiableList(copy);
        }

        public List<ItemStack> getItems() {
            List<ItemStack> copy = new ArrayList<>();
            for (RewardLine line : lines) {
                if (!line.isDelivered() && !line.getStack().isEmpty()) {
                    copy.add(line.getStack());
                }
            }
            return Collections.unmodifiableList(copy);
        }

        public synchronized boolean confirmDeliveredLine(UUID lineId) {
            for (Iterator<RewardLine> it = lines.iterator(); it.hasNext(); ) {
                RewardLine line = it.next();
                if (line.getLineId().equals(lineId)) {
                    line.setDelivered(true);
                    it.remove();
                    return true;
                }
            }
            return false;
        }

        public synchronized boolean removeDeliveredItem(ItemStack stack) {
            for (Iterator<RewardLine> it = lines.iterator(); it.hasNext(); ) {
                RewardLine line = it.next();
                if (!line.isDelivered() && ItemStack.isSameItemSameTags(line.getStack(), stack)) {
                    if (line.getStack().getCount() <= stack.getCount()) {
                        it.remove();
                    } else {
                        ItemStack item = line.getStack();
                        item.shrink(stack.getCount());
                        it.remove();
                        lines.add(new RewardLine(line.getLineId(), item, false));
                    }
                    return true;
                }
            }
            return false;
        }

        public boolean isEmpty() {
            for (RewardLine line : lines) {
                if (!line.isDelivered() && !line.getStack().isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        public PayoutDestination getPayoutDestination() {
            return payoutDestination != null ? payoutDestination : PayoutDestination.INVENTORY;
        }

        public void setPayoutDestination(PayoutDestination payoutDestination) {
            this.payoutDestination = payoutDestination != null ? payoutDestination : PayoutDestination.INVENTORY;
        }

        public CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("TransactionId", transactionId);
            tag.putUUID("PlayerUUID", playerUUID);
            if (jackpot) {
                tag.putBoolean("Jackpot", true);
                tag.putLong("JackpotAmount", jackpotAmount);
                tag.putBoolean("JackpotClaimed", jackpotClaimed);
            }
            if (payoutDestination != null) {
                tag.putString("PayoutDestination", payoutDestination.name());
            }
            ListTag listTag = new ListTag();
            for (RewardLine line : lines) {
                if (!line.isDelivered() && !line.getStack().isEmpty()) {
                    listTag.add(line.toNbt());
                }
            }
            tag.put("Lines", listTag);
            return tag;
        }

        public static RewardTransaction fromNbt(CompoundTag tag) {
            UUID txId = tag.contains("TransactionId") ? tag.getUUID("TransactionId") : UUID.randomUUID();
            UUID pId = tag.getUUID("PlayerUUID");
            boolean jackpot = tag.getBoolean("Jackpot");
            long jackpotAmount = tag.getLong("JackpotAmount");
            boolean jackpotClaimed = tag.getBoolean("JackpotClaimed");
            PayoutDestination dest = PayoutDestination.INVENTORY;
            if (tag.contains("PayoutDestination")) {
                try {
                    dest = PayoutDestination.valueOf(tag.getString("PayoutDestination"));
                } catch (IllegalArgumentException ignored) {}
            }

            List<RewardLine> lines = new ArrayList<>();
            if (tag.contains("Lines", Tag.TAG_LIST)) {
                ListTag listTag = tag.getList("Lines", Tag.TAG_COMPOUND);
                for (int i = 0; i < listTag.size(); i++) {
                    RewardLine line = RewardLine.fromNbt(listTag.getCompound(i));
                    if (!line.getStack().isEmpty() && !line.isDelivered()) {
                        lines.add(line);
                    }
                }
            } else if (tag.contains("Items", Tag.TAG_LIST)) {
                // Legacy v1 fallback
                ListTag listTag = tag.getList("Items", Tag.TAG_COMPOUND);
                for (int i = 0; i < listTag.size(); i++) {
                    ItemStack stack = ItemStack.of(listTag.getCompound(i));
                    if (!stack.isEmpty()) {
                        lines.add(new RewardLine(stack));
                    }
                }
            }
            return fromLines(txId, pId, lines, jackpot, jackpotAmount, jackpotClaimed, dest);
        }
    }

    public static final long DEFAULT_JACKPOT_MAX_LIMIT = 10_000_000L;

    public static long getMaxJackpotAmount() {
        if (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded()) {
            return PocketOddsConfig.SERVER.jackpotMaxAmount.get();
        }
        return DEFAULT_JACKPOT_MAX_LIMIT;
    }

    public JackpotSavedData() {
        long baseAmount = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? PocketOddsConfig.SERVER.jackpotBaseAmount.get() : 100L;
        this.jackpotAmount = Math.min(baseAmount, getMaxJackpotAmount());
    }

    public JackpotSavedData(long initialAmount) {
        this.jackpotAmount = Math.min(Math.max(0L, initialAmount), getMaxJackpotAmount());
    }

    public static JackpotSavedData load(CompoundTag tag) {
        long amount = tag.getLong("JackpotAmount");
        long baseAmount = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? PocketOddsConfig.SERVER.jackpotBaseAmount.get() : 100L;
        long maxCap = getMaxJackpotAmount();
        if (amount < baseAmount) {
            amount = baseAmount;
        } else if (amount > maxCap) {
            amount = maxCap;
        }
        JackpotSavedData data = new JackpotSavedData(amount);

        // Load transactional outbox
        if (tag.contains("PendingTransactions", Tag.TAG_LIST)) {
            ListTag txList = tag.getList("PendingTransactions", Tag.TAG_COMPOUND);
            for (int i = 0; i < txList.size(); i++) {
                CompoundTag txTag = txList.getCompound(i);
                data.pendingTransactions.add(RewardTransaction.fromNbt(txTag));
            }
        }

        // Migrate legacy PendingRewards format if present
        if (tag.contains("PendingRewards", Tag.TAG_COMPOUND)) {
            CompoundTag pendingTag = tag.getCompound("PendingRewards");
            for (String key : pendingTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    ListTag listTag = pendingTag.getList(key, Tag.TAG_COMPOUND);
                    List<ItemStack> items = new ArrayList<>();
                    for (int i = 0; i < listTag.size(); i++) {
                        ItemStack stack = ItemStack.of(listTag.getCompound(i));
                        if (!stack.isEmpty()) {
                            items.add(stack);
                        }
                    }
                    if (!items.isEmpty()) {
                        data.pendingTransactions.add(new RewardTransaction(UUID.randomUUID(), uuid, items));
                    }
                } catch (IllegalArgumentException e) {
                    LOGGER.error("Pocket Odds: Failed to parse legacy pending reward for key '{}': {}", key, e.getMessage(), e);
                }
            }
        }

        // Load active sessions
        if (tag.contains("ActiveSessions", Tag.TAG_COMPOUND)) {
            CompoundTag sessionsTag = tag.getCompound("ActiveSessions");
            for (String key : sessionsTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    data.activeSessions.put(uuid, sessionsTag.getCompound(key));
                } catch (IllegalArgumentException e) {
                    LOGGER.error("Pocket Odds: Failed to parse active session key '{}': {}", key, e.getMessage(), e);
                }
            }
        }

        // Load completed receipts BEFORE deck recovery to prevent duplicate restore
        if (tag.contains("CompletedReceipts", Tag.TAG_LIST)) {
            ListTag receiptList = tag.getList("CompletedReceipts", Tag.TAG_STRING);
            for (int i = 0; i < receiptList.size(); i++) {
                try {
                    data.completedReceipts.add(UUID.fromString(receiptList.getString(i)));
                } catch (IllegalArgumentException e) {
                    LOGGER.error("Pocket Odds: Failed to parse completed receipt '{}': {}", receiptList.getString(i), e.getMessage());
                }
            }
        }

        // Load pouch balances
        if (tag.contains("PouchBalances", Tag.TAG_COMPOUND)) {
            CompoundTag pouchesTag = tag.getCompound("PouchBalances");
            for (String key : pouchesTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    data.pouchBalances.put(uuid, PouchBalance.fromNbt(pouchesTag.getCompound(key)));
                } catch (IllegalArgumentException e) {
                    LOGGER.error("Pocket Odds: Failed to parse pouch balance key '{}': {}", key, e.getMessage());
                }
            }
        }

        // Load pending deposits & crash recovery
        if (tag.contains("PendingDeposits", Tag.TAG_COMPOUND)) {
            CompoundTag depTag = tag.getCompound("PendingDeposits");
            for (String key : depTag.getAllKeys()) {
                try {
                    PouchDepositEscrow escrow = PouchDepositEscrow.fromNbt(depTag.getCompound(key));
                    if (escrow != null && escrow.getStatus() == PouchDepositEscrow.Status.DEBITED) {
                        // Crash recovery: Deposit was confirmed debited from player inventory,
                        // safely complete credit to the pouch balance
                        PouchBalance balance = data.pouchBalances.computeIfAbsent(escrow.getPouchUUID(), id -> new PouchBalance());
                        balance.addCount(escrow.getTier(), escrow.getCount());
                        LOGGER.warn("Pocket Odds Crash Recovery: Restored debited pouch deposit {} ({} of {}) to pouch {}",
                                escrow.getDepositId(), escrow.getCount(), escrow.getTier(), escrow.getPouchUUID());
                    } else if (escrow != null && escrow.getStatus() == PouchDepositEscrow.Status.PREPARED) {
                        // Prepared deposit loaded: retain in pendingDeposits for inventory snapshot audit upon player login
                        data.pendingDeposits.put(escrow.getDepositId(), escrow);
                        LOGGER.info("Pocket Odds Crash Recovery: Retained prepared pouch deposit {} for player {} (awaiting login audit)",
                                escrow.getDepositId(), escrow.getPlayerUUID());
                    }
                } catch (Exception e) {
                    LOGGER.error("Pocket Odds: Failed to recover pending deposit key '{}': {}", key, e.getMessage());
                }
            }
        }

        // Load last player sessions (only non-expired)
        if (tag.contains("LastPlayerSessions", Tag.TAG_COMPOUND)) {
            CompoundTag sessTag = tag.getCompound("LastPlayerSessions");
            long now = System.currentTimeMillis();
            for (String key : sessTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    CasinoGameSession session = CasinoGameSession.fromNbt(sessTag.getCompound(key));
                    if (session != null && (now - session.getGameTimestamp()) <= SESSION_TTL_MS) {
                        data.lastPlayerSessions.put(uuid, session);
                    }
                } catch (Exception e) {
                    LOGGER.error("Pocket Odds: Failed to parse last player session key '{}': {}", key, e.getMessage());
                }
            }
        }

        // Load roulette history
        if (tag.contains("RouletteHistory", Tag.TAG_INT_ARRAY)) {
            int[] histArr = tag.getIntArray("RouletteHistory");
            for (int val : histArr) {
                data.rouletteHistory.add(val);
            }
        }

        // Load server-side deck sessions BEFORE prepared bets so crash recovery can inspect active deck sessions
        if (tag.contains("DeckSessions", Tag.TAG_COMPOUND)) {
            CompoundTag deckTag = tag.getCompound("DeckSessions");
            for (String key : deckTag.getAllKeys()) {
                try {
                    DeckSession session = DeckSession.fromNbt(deckTag.getCompound(key));
                    data.deckSessions.put(session.getSessionId(), session);

                    // Recovery for CASHOUT_COMMITTED sessions where crash happened before outbox enqueue was completed
                    if (session.getStatus() == DeckSession.Status.CASHOUT_COMMITTED && session.getPotUnits() > 0) {
                        UUID cashoutTxId = UUID.nameUUIDFromBytes(("deck_cashout:" + session.getSessionId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        if (data.isReceiptCompleted(cashoutTxId) || data.isReceiptCompleted(session.getSessionId())) {
                            LOGGER.info("Pocket Odds Crash Recovery: Cashout for session {} was already completed (receipt present). Discarding session.", session.getSessionId());
                            continue;
                        }
                        if (!data.hasPendingTransaction(cashoutTxId)) {
                            LOGGER.warn("Pocket Odds Crash Recovery: Restoring missing cashout transaction {} for session {} of player {}",
                                    cashoutTxId, session.getSessionId(), session.getPlayerUUID());
                            List<ItemStack> cashoutItems = DeckOfFateItem.calculateDeckCashoutRewards(session);
                            if (!cashoutItems.isEmpty()) {
                                data.enqueueRewardTransaction(RewardTransaction.fromBundle(
                                        cashoutTxId, session.getPlayerUUID(), new RewardBundle(cashoutItems, 0L, false)
                                ));
                            }
                        }
                    } else if (session.getStatus() == DeckSession.Status.DELIVERED) {
                        // Completed and delivered session: no recovery needed
                        continue;
                    }
                } catch (Exception e) {
                    LOGGER.error("Pocket Odds: Failed to load deck session [key={}]: {}", key, e.getMessage(), e);
                }
            }
        }

        // Load prepared bets with 2PC crash recovery
        if (tag.contains("PreparedBets", Tag.TAG_COMPOUND)) {
            CompoundTag prepTag = tag.getCompound("PreparedBets");
            for (String key : prepTag.getAllKeys()) {
                try {
                    BetPreparation prep = BetPreparation.fromNbt(prepTag.getCompound(key));
                    if (prep.getStatus() == BetPreparation.PreparationStatus.DEBITED) {
                        UUID assocId = prep.getAssociatedId();
                        boolean alreadyCommitted = false;

                        if (assocId != null) {
                            if (data.hasPendingTransaction(assocId)) {
                                alreadyCommitted = true;
                            } else if (data.deckSessions.containsKey(assocId)) {
                                alreadyCommitted = true;
                            } else {
                                for (CompoundTag sessionTag : data.activeSessions.values()) {
                                    if (sessionTag.contains("RollId") && assocId.equals(sessionTag.getUUID("RollId"))) {
                                        alreadyCommitted = true;
                                        break;
                                    }
                                }
                            }
                        }

                        if (!alreadyCommitted && data.hasPendingTransaction(prep.getPreparationId())) {
                            alreadyCommitted = true;
                        }

                        if (alreadyCommitted) {
                            LOGGER.info("Pocket Odds Crash Recovery: Debited bet {} for player {} has already committed outcome/session. Skipping refund.",
                                    prep.getPreparationId(), prep.getPlayerUUID());
                            continue;
                        }

                        // CRASH RECOVERY: Items were debited before crash, but NO game outcome or session was ever committed!
                        // Safely create a persistent refund transaction in the outbox.
                        List<ItemStack> refund = new ArrayList<>();
                        if (prep.getBetSnapshot().isChipBet()) {
                            refund.addAll(ChipUtils.splitChips(prep.getBetSnapshot().getChipTier().getItem(), prep.getBetSnapshot().getBetCount()));
                        } else {
                            ItemStack proto = prep.getBetSnapshot().getItemPrototype();
                            int max = proto.getMaxStackSize();
                            int rem = prep.getBetSnapshot().getBetCount();
                            while (rem > 0) {
                                int take = Math.min(rem, max);
                                ItemStack s = proto.copy();
                                s.setCount(take);
                                refund.add(s);
                                rem -= take;
                            }
                        }
                        RewardTransaction refundTx = new RewardTransaction(UUID.randomUUID(), prep.getPlayerUUID(), refund);
                        data.pendingTransactions.add(refundTx);
                        prep.setStatus(BetPreparation.PreparationStatus.REFUND_QUEUED);
                        LOGGER.warn("Pocket Odds Crash Recovery: Debited bet {} for player {} had uncommitted game state. Queued refund {}.",
                                prep.getPreparationId(), prep.getPlayerUUID(), refundTx.getTransactionId());
                    } else if (prep.getStatus() == BetPreparation.PreparationStatus.PREPARED) {
                        // PREPARED: items were not yet debited from player before crash -> discard safely
                        continue;
                    }
                    data.preparedBets.put(prep.getPreparationId(), prep);
                } catch (Exception e) {
                    LOGGER.error("Pocket Odds: Failed to load or recover prepared bet [key={}]: {}", key, e.getMessage(), e);
                }
            }
        }

        // Load shop purchases with 2PC recovery
        if (tag.contains("ShopPurchases", Tag.TAG_LIST)) {
            ListTag purchaseList = tag.getList("ShopPurchases", Tag.TAG_COMPOUND);
            for (int i = 0; i < purchaseList.size(); i++) {
                try {
                    ShopPurchaseTransaction tx = ShopPurchaseTransaction.fromNbt(purchaseList.getCompound(i));
                    data.shopPurchases.put(tx.getOperationId(), tx);
                } catch (Exception e) {
                    LOGGER.error("Pocket Odds: Failed to load shop purchase: {}", e.getMessage());
                }
            }
        }
        // Load player shop limits before recovering transactions so limit increments are not overwritten
        if (tag.contains("PlayerShopLimits", Tag.TAG_COMPOUND)) {
            CompoundTag limitsTag = tag.getCompound("PlayerShopLimits");
            for (String pKey : limitsTag.getAllKeys()) {
                try {
                    UUID pUUID = UUID.fromString(pKey);
                    CompoundTag pMapTag = limitsTag.getCompound(pKey);
                    Map<String, PlayerPurchaseRecord> recMap = new ConcurrentHashMap<>();
                    for (String offerKey : pMapTag.getAllKeys()) {
                        recMap.put(offerKey, PlayerPurchaseRecord.fromNbt(pMapTag.getCompound(offerKey)));
                    }
                    data.playerShopLimits.put(pUUID, recMap);
                } catch (Exception ignored) {}
            }
        }

        // Recover shop purchases
        for (ShopPurchaseTransaction tx : data.shopPurchases.values()) {
            if (tx.getState() == ShopPurchaseTransaction.State.PREPARED) {
                // Clean PREPARED without actual debits is cancelled without refund
                if (!tx.hasActualDebits()) {
                    tx.setState(ShopPurchaseTransaction.State.CANCELLED);
                    LOGGER.info("Pocket Odds Crash Recovery: Clean PREPARED shop purchase {} cancelled without refund.", tx.getOperationId());
                } else {
                    queueShopRefund(data, tx);
                    tx.setState(ShopPurchaseTransaction.State.REFUND_QUEUED);
                }
            } else if (tx.getState() == ShopPurchaseTransaction.State.REFUND_QUEUED) {
                // Recover previously saved REFUND_QUEUED state to ensure outbox contains the refund
                if (tx.hasActualDebits()) {
                    queueShopRefund(data, tx);
                } else {
                    tx.setState(ShopPurchaseTransaction.State.CANCELLED);
                }
            } else if (tx.getState() == ShopPurchaseTransaction.State.PAYMENT_DEBITED
                    || tx.getState() == ShopPurchaseTransaction.State.REWARD_QUEUED) {
                // Payment was debited before crash: restore reward and change into outbox
                UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + tx.getOperationId()).getBytes(StandardCharsets.UTF_8));
                if (data.getTransaction(rewardTxId) == null && !data.isReceiptCompleted(rewardTxId) && !tx.getRewardStack().isEmpty()) {
                    RewardTransaction rewardTx = new RewardTransaction(rewardTxId, tx.getPlayerUUID(), Collections.singletonList(tx.getRewardStack()));
                    data.pendingTransactions.add(rewardTx);
                }
                if (tx.getChangeCredits() > 0 && !tx.getChangeStacks().isEmpty()) {
                    UUID changeTxId = UUID.nameUUIDFromBytes(("shop_change:" + tx.getOperationId()).getBytes(StandardCharsets.UTF_8));
                    if (data.getTransaction(changeTxId) == null && !data.isReceiptCompleted(changeTxId)) {
                        RewardTransaction changeTx = new RewardTransaction(changeTxId, tx.getPlayerUUID(), tx.getChangeStacks());
                        data.pendingTransactions.add(changeTx);
                    }
                }
                if (!tx.isLimitRecorded()) {
                    data.incrementPlayerPurchaseCount(tx.getPlayerUUID(), tx.getOfferId(), tx.getLimitPeriod());
                    tx.setLimitRecorded(true);
                }
                tx.setState(ShopPurchaseTransaction.State.COMMITTED);
            }
        }

        return data;
    }

    private static void queueShopRefund(JackpotSavedData data, ShopPurchaseTransaction tx) {
        UUID refundTxId = UUID.nameUUIDFromBytes(("shop_refund:" + tx.getOperationId()).getBytes(StandardCharsets.UTF_8));
        if (data.getTransaction(refundTxId) == null && !data.isReceiptCompleted(refundTxId)) {
            List<ItemStack> refundStacks = tx.createActualRefundStacks();
            if (!refundStacks.isEmpty()) {
                RewardTransaction refundTx = new RewardTransaction(refundTxId, tx.getPlayerUUID(), refundStacks);
                data.pendingTransactions.add(refundTx);
                LOGGER.warn("Pocket Odds Crash Recovery: Shop purchase {} had recorded actual debits. Queued refund {}.",
                        tx.getOperationId(), refundTxId);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("DataVersion", 2);
        tag.putLong("JackpotAmount", this.jackpotAmount);

        // Save transactional outbox
        if (!pendingTransactions.isEmpty()) {
            ListTag txList = new ListTag();
            for (RewardTransaction tx : pendingTransactions) {
                txList.add(tx.toNbt());
            }
            tag.put("PendingTransactions", txList);
        }

        // Save active sessions
        Map<UUID, net.pocketodds.gambling.slot.SlotRollSession> live = net.pocketodds.gambling.tracker.ActiveRollTracker.getActiveSessions();
        if (!live.isEmpty()) {
            CompoundTag sessionsTag = new CompoundTag();
            for (Map.Entry<UUID, net.pocketodds.gambling.slot.SlotRollSession> entry : live.entrySet()) {
                sessionsTag.put(entry.getKey().toString(), entry.getValue().toNbt());
            }
            tag.put("ActiveSessions", sessionsTag);
        } else if (!activeSessions.isEmpty()) {
            CompoundTag sessionsTag = new CompoundTag();
            for (Map.Entry<UUID, CompoundTag> entry : activeSessions.entrySet()) {
                sessionsTag.put(entry.getKey().toString(), entry.getValue());
            }
            tag.put("ActiveSessions", sessionsTag);
        }

        // Save prepared bets
        if (!preparedBets.isEmpty()) {
            CompoundTag prepTag = new CompoundTag();
            for (Map.Entry<UUID, BetPreparation> entry : preparedBets.entrySet()) {
                prepTag.put(entry.getKey().toString(), entry.getValue().toNbt());
            }
            tag.put("PreparedBets", prepTag);
        }

        // Save deck sessions (active or pending cashout completion)
        if (!deckSessions.isEmpty()) {
            CompoundTag deckTag = new CompoundTag();
            for (Map.Entry<UUID, DeckSession> entry : deckSessions.entrySet()) {
                DeckSession s = entry.getValue();
                if (s.isActive() || s.getStatus() == DeckSession.Status.CASHOUT_COMMITTED) {
                    deckTag.put(entry.getKey().toString(), s.toNbt());
                }
            }
            tag.put("DeckSessions", deckTag);
        }

        // Save completed receipts (capped to 5000)
        if (!completedReceipts.isEmpty()) {
            ListTag receiptList = new ListTag();
            int count = 0;
            for (UUID id : completedReceipts) {
                receiptList.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
                if (++count >= 5000) break;
            }
            tag.put("CompletedReceipts", receiptList);
        }

        // Save pouch balances
        if (!pouchBalances.isEmpty()) {
            CompoundTag pouchesTag = new CompoundTag();
            for (Map.Entry<UUID, PouchBalance> entry : pouchBalances.entrySet()) {
                pouchesTag.put(entry.getKey().toString(), entry.getValue().toNbt());
            }
            tag.put("PouchBalances", pouchesTag);
        }

        // Save pending pouch deposits
        if (!pendingDeposits.isEmpty()) {
            CompoundTag depositsTag = new CompoundTag();
            for (Map.Entry<UUID, PouchDepositEscrow> entry : pendingDeposits.entrySet()) {
                depositsTag.put(entry.getKey().toString(), entry.getValue().toNbt());
            }
            tag.put("PendingDeposits", depositsTag);
        }

        // Save last player sessions
        cleanupExpiredSessions();
        if (!lastPlayerSessions.isEmpty()) {
            CompoundTag sessionsTag = new CompoundTag();
            for (Map.Entry<UUID, CasinoGameSession> entry : lastPlayerSessions.entrySet()) {
                sessionsTag.put(entry.getKey().toString(), entry.getValue().toNbt());
            }
            tag.put("LastPlayerSessions", sessionsTag);
        }

        // Save roulette history
        if (!rouletteHistory.isEmpty()) {
            int[] histArr = new int[rouletteHistory.size()];
            for (int i = 0; i < histArr.length; i++) {
                histArr[i] = rouletteHistory.get(i);
            }
            tag.putIntArray("RouletteHistory", histArr);
        }

        // Save shop purchases
        if (!shopPurchases.isEmpty()) {
            ListTag purchaseList = new ListTag();
            for (ShopPurchaseTransaction tx : shopPurchases.values()) {
                purchaseList.add(tx.toNbt());
            }
            tag.put("ShopPurchases", purchaseList);
        }

        // Save player shop limits
        if (!playerShopLimits.isEmpty()) {
            CompoundTag limitsTag = new CompoundTag();
            for (Map.Entry<UUID, Map<String, PlayerPurchaseRecord>> entry : playerShopLimits.entrySet()) {
                CompoundTag pMapTag = new CompoundTag();
                for (Map.Entry<String, PlayerPurchaseRecord> recEntry : entry.getValue().entrySet()) {
                    pMapTag.put(recEntry.getKey(), recEntry.getValue().toNbt());
                }
                limitsTag.put(entry.getKey().toString(), pMapTag);
            }
            tag.put("PlayerShopLimits", limitsTag);
        }

        return tag;
    }

    public long getJackpotAmount() {
        return Math.min(jackpotAmount, getMaxJackpotAmount());
    }

    public void addContributionBasisPoints(long totalCredits, int basisPoints) {
        if (totalCredits <= 0 || basisPoints <= 0) {
            return;
        }
        long contribution;
        try {
            long product = Math.multiplyExact(totalCredits, (long) basisPoints);
            contribution = Math.max(1L, product / 10_000L);
        } catch (ArithmeticException e) {
            java.math.BigInteger bigProd = java.math.BigInteger.valueOf(totalCredits)
                    .multiply(java.math.BigInteger.valueOf(basisPoints))
                    .divide(java.math.BigInteger.valueOf(10_000L));
            contribution = bigProd.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            contribution = Math.max(1L, contribution);
        }

        long maxCap = getMaxJackpotAmount();
        if (this.jackpotAmount >= maxCap) {
            this.jackpotAmount = maxCap;
            return;
        }

        if (maxCap - this.jackpotAmount < contribution) {
            this.jackpotAmount = maxCap;
        } else {
            this.jackpotAmount += contribution;
        }
        setDirty();
    }

    public void addContribution(long betValue) {
        if (betValue <= 0) {
            return;
        }
        int bps = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? (int) Math.round(PocketOddsConfig.SERVER.jackpotContributionRate.get() * 10_000.0)
                : 500;
        addContributionBasisPoints(betValue, bps);
    }

    public long claimJackpot() {
        long payout = Math.min(this.jackpotAmount, getMaxJackpotAmount());
        long baseAmount = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? PocketOddsConfig.SERVER.jackpotBaseAmount.get() : 100L;
        this.jackpotAmount = baseAmount;
        setDirty();
        return payout;
    }

    // --- Prepared Bet (2PC) Methods ---

    public void savePreparedBet(BetPreparation prep) {
        if (prep != null) {
            preparedBets.put(prep.getPreparationId(), prep);
            setDirty();
        }
    }

    public BetPreparation getPreparedBet(UUID preparationId) {
        if (preparationId == null) return null;
        return preparedBets.get(preparationId);
    }

    public void removePreparedBet(UUID preparationId) {
        if (preparationId != null && preparedBets.remove(preparationId) != null) {
            setDirty();
        }
    }

    public Map<UUID, BetPreparation> getPreparedBets() {
        return Collections.unmodifiableMap(preparedBets);
    }

    // --- Deck Session Methods ---

    public void saveDeckSession(DeckSession session) {
        if (session != null) {
            deckSessions.put(session.getSessionId(), session);
            setDirty();
        }
    }

    public DeckSession getDeckSession(UUID sessionId) {
        if (sessionId == null) return null;
        return deckSessions.get(sessionId);
    }

    public DeckSession getActiveDeckSession(UUID playerUUID) {
        if (playerUUID == null) return null;
        for (DeckSession s : deckSessions.values()) {
            if (s.getPlayerUUID().equals(playerUUID) && s.isActive()) {
                return s;
            }
        }
        return null;
    }

    public void removeDeckSession(UUID sessionId) {
        if (sessionId != null && deckSessions.remove(sessionId) != null) {
            setDirty();
        }
    }

    // --- Pouch Balance Methods ---

    private PouchBalance getInternalPouchBalance(UUID pouchUUID, ItemStack initialStack) {
        if (pouchUUID == null) return new PouchBalance();
        return pouchBalances.computeIfAbsent(pouchUUID, id -> {
            setDirty();
            if (initialStack != null && !initialStack.isEmpty()) {
                return new PouchBalance(
                        net.pocketodds.service.CoinPouchService.getChipCountFromNbt(initialStack, net.pocketodds.item.ChipTier.COPPER),
                        net.pocketodds.service.CoinPouchService.getChipCountFromNbt(initialStack, net.pocketodds.item.ChipTier.GOLD),
                        net.pocketodds.service.CoinPouchService.getChipCountFromNbt(initialStack, net.pocketodds.item.ChipTier.DIAMOND),
                        net.pocketodds.service.CoinPouchService.getChipCountFromNbt(initialStack, net.pocketodds.item.ChipTier.NETHERITE)
                );
            }
            return new PouchBalance();
        });
    }

    public synchronized PouchBalance getOrCreatePouchBalance(UUID pouchUUID) {
        return getOrCreatePouchBalance(pouchUUID, null);
    }

    public synchronized PouchBalance getOrCreatePouchBalance(UUID pouchUUID, ItemStack initialStack) {
        return getInternalPouchBalance(pouchUUID, initialStack).copy();
    }

    public synchronized PouchBalance getPouchBalance(UUID pouchUUID) {
        if (pouchUUID == null) return new PouchBalance();
        PouchBalance b = pouchBalances.get(pouchUUID);
        return b != null ? b.copy() : new PouchBalance();
    }

    public synchronized void setPouchBalance(UUID pouchUUID, PouchBalance balance) {
        if (pouchUUID != null && balance != null) {
            pouchBalances.put(pouchUUID, balance.copy());
            setDirty();
        }
    }

    public synchronized boolean debitPouchChips(UUID pouchUUID, Map<ChipTier, Long> debits) {
        if (pouchUUID == null || debits == null || debits.isEmpty()) return true;
        PouchBalance internal = pouchBalances.get(pouchUUID);
        if (internal == null) return false;
        for (Map.Entry<ChipTier, Long> e : debits.entrySet()) {
            if (e.getValue() > 0 && internal.getCount(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        for (Map.Entry<ChipTier, Long> e : debits.entrySet()) {
            if (e.getValue() > 0) {
                internal.addCount(e.getKey(), -e.getValue());
            }
        }
        setDirty();
        return true;
    }

    public synchronized boolean creditPouchChips(UUID pouchUUID, Map<ChipTier, Long> credits) {
        if (pouchUUID == null || credits == null || credits.isEmpty()) return true;
        PouchBalance internal = pouchBalances.computeIfAbsent(pouchUUID, id -> new PouchBalance());
        for (Map.Entry<ChipTier, Long> e : credits.entrySet()) {
            if (e.getValue() > 0) {
                internal.addCount(e.getKey(), e.getValue());
            }
        }
        setDirty();
        return true;
    }

    public synchronized PouchBalance depositToPouch(UUID pouchUUID, ChipTier tier, int count, ItemStack initialStack) {
        if (pouchUUID == null || tier == null || count <= 0) return getOrCreatePouchBalance(pouchUUID, initialStack);
        PouchBalance internal = getInternalPouchBalance(pouchUUID, initialStack);
        if (internal.canAdd(tier, count)) {
            internal.addCount(tier, count);
            setDirty();
        }
        return internal.copy();
    }

    public synchronized RewardTransaction withdrawFromPouch(UUID pouchUUID, UUID playerUUID, ChipTier tier, int requestedCount, ItemStack initialStack) {
        if (pouchUUID == null || playerUUID == null || tier == null || requestedCount <= 0) return null;
        PouchBalance internal = getInternalPouchBalance(pouchUUID, initialStack);
        long current = internal.getCount(tier);
        int take = (int) Math.min((long) requestedCount, current);
        if (take <= 0) return null;

        internal.addCount(tier, -take);
        List<ItemStack> stacks = ChipUtils.splitChips(tier.getItem(), take);
        UUID txId = UUID.randomUUID();
        RewardTransaction tx = new RewardTransaction(txId, playerUUID, stacks);
        pendingTransactions.add(tx);
        setDirty();
        return tx;
    }

    public synchronized int withdrawFromPouch(UUID playerUUID, UUID pouchUUID, ChipTier tier, int requestedCount) {
        RewardTransaction tx = withdrawFromPouch(pouchUUID, playerUUID, tier, requestedCount, null);
        if (tx == null) return 0;
        return tx.getItems().stream().mapToInt(ItemStack::getCount).sum();
    }

    public synchronized boolean debitCreditsFromPouch(UUID pouchUUID, long credits) {
        return debitCreditsFromPouch(pouchUUID, credits, null);
    }

    public synchronized boolean debitCreditsFromPouch(UUID pouchUUID, long credits, ItemStack initialStack) {
        if (pouchUUID == null || credits <= 0) return false;
        PouchBalance internal = getInternalPouchBalance(pouchUUID, initialStack);
        if (internal.getTotalCredits() < credits) return false;
        net.pocketodds.service.CoinPouchService.deductCreditsFromPouchBalance(internal, credits);
        setDirty();
        return true;
    }

    public synchronized void addCreditsToPouch(UUID pouchUUID, long credits) {
        addCreditsToPouch(pouchUUID, credits, null);
    }

    public synchronized void addCreditsToPouch(UUID pouchUUID, long credits, ItemStack initialStack) {
        if (pouchUUID == null || credits <= 0) return;
        PouchBalance internal = getInternalPouchBalance(pouchUUID, initialStack);
        net.pocketodds.service.CoinPouchService.addCreditsToPouchBalance(internal, credits);
        setDirty();
    }

    public Map<UUID, PouchBalance> getPouchBalances() {
        Map<UUID, PouchBalance> copies = new HashMap<>();
        for (Map.Entry<UUID, PouchBalance> e : pouchBalances.entrySet()) {
            copies.put(e.getKey(), e.getValue().copy());
        }
        return Collections.unmodifiableMap(copies);
    }

    // --- Pouch Escrow (2PC Deposit) ---

    public synchronized PouchDepositEscrow preparePouchDeposit(UUID playerUUID, UUID pouchUUID, ChipTier tier, int count) {
        return preparePouchDeposit(UUID.randomUUID(), playerUUID, pouchUUID, tier, count, -1);
    }

    public synchronized PouchDepositEscrow preparePouchDeposit(UUID playerUUID, UUID pouchUUID, ChipTier tier, int count, int expectedPlayerChipCount) {
        return preparePouchDeposit(UUID.randomUUID(), playerUUID, pouchUUID, tier, count, expectedPlayerChipCount);
    }

    public synchronized PouchDepositEscrow preparePouchDeposit(UUID depositId, UUID playerUUID, UUID pouchUUID, ChipTier tier, int count) {
        return preparePouchDeposit(depositId, playerUUID, pouchUUID, tier, count, -1);
    }

    public synchronized PouchDepositEscrow preparePouchDeposit(UUID depositId, UUID playerUUID, UUID pouchUUID, ChipTier tier, int count, int expectedPlayerChipCount) {
        if (depositId == null || playerUUID == null || pouchUUID == null || tier == null || count <= 0) return null;
        PouchDepositEscrow escrow = new PouchDepositEscrow(depositId, playerUUID, pouchUUID, tier, count, expectedPlayerChipCount, PouchDepositEscrow.Status.PREPARED, System.currentTimeMillis());
        pendingDeposits.put(depositId, escrow);
        setDirty();
        return escrow;
    }

    /**
     * Transitions an escrow deposit to the DEBITED state.
     * This confirms that chips were physically removed from the player's inventory.
     *
     * Crash-recovery semantics:
     * - Only deposits marked as DEBITED will be credited directly upon restart.
     * - Deposits remaining in PREPARED state undergo an inventory snapshot audit on player login
     *   to verify if chips were debited before crash, returning missing chips via outbox transaction.
     */
    public synchronized boolean markDepositDebited(UUID depositId) {
        if (depositId == null) return false;
        PouchDepositEscrow escrow = pendingDeposits.get(depositId);
        if (escrow != null && escrow.getStatus() == PouchDepositEscrow.Status.PREPARED) {
            escrow.setStatus(PouchDepositEscrow.Status.DEBITED);
            setDirty();
            return true;
        }
        return false;
    }

    public synchronized boolean commitPouchDeposit(UUID depositId) {
        return commitPouchDeposit(depositId, null);
    }

    public synchronized boolean commitPouchDeposit(UUID depositId, ItemStack initialStack) {
        if (depositId == null) return false;
        PouchDepositEscrow escrow = pendingDeposits.get(depositId);
        if (escrow == null || escrow.getStatus() != PouchDepositEscrow.Status.DEBITED) {
            return false;
        }
        PouchBalance internal = getInternalPouchBalance(escrow.getPouchUUID(), initialStack);
        if (!internal.canAdd(escrow.getTier(), escrow.getCount())) {
            return false;
        }
        pendingDeposits.remove(depositId);
        escrow.setStatus(PouchDepositEscrow.Status.COMMITTED);
        internal.addCount(escrow.getTier(), escrow.getCount());
        setDirty();
        return true;
    }

    public synchronized void abortPouchDeposit(UUID depositId) {
        if (depositId != null && pendingDeposits.remove(depositId) != null) {
            setDirty();
        }
    }

    /**
     * Audits uncommitted PREPARED deposits for a player logging in.
     * If player inventory reflects that chips were debited before a server crash (current <= expected - take),
     * safely recovers the chips by enqueueing a refund transaction in the outbox.
     * If chips were not debited, safely discards the uncommitted deposit without duplicate credits.
     */
    public synchronized void auditAndResolvePendingDeposits(ServerPlayer player) {
        if (player == null) return;
        auditAndResolvePendingDeposits(player.getUUID(),
                tier -> net.pocketodds.util.InventoryUtils.countChips(player, tier),
                refundTx -> {
                    enqueueRewardTransaction(refundTx);
                    int count = !refundTx.getItems().isEmpty() ? refundTx.getItems().get(0).getCount() : 0;
                    String tierId = !refundTx.getItems().isEmpty() && refundTx.getItems().get(0).getItem() instanceof net.pocketodds.item.ChipItem ci
                            ? ci.getTier().getId() : "";
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "pocketodds.pouch.deposit_recovered", count, tierId
                    ).withStyle(net.minecraft.ChatFormatting.GOLD));
                });
    }

    public synchronized void auditAndResolvePendingDeposits(UUID playerUUID,
                                                           java.util.function.Function<ChipTier, Integer> chipCounter,
                                                           java.util.function.Consumer<RewardTransaction> refundSink) {
        if (playerUUID == null || chipCounter == null) return;
        List<PouchDepositEscrow> toProcess = new ArrayList<>();
        for (PouchDepositEscrow escrow : pendingDeposits.values()) {
            if (playerUUID.equals(escrow.getPlayerUUID())) {
                toProcess.add(escrow);
            }
        }

        if (toProcess.isEmpty()) return;

        for (PouchDepositEscrow escrow : toProcess) {
            pendingDeposits.remove(escrow.getDepositId());
            if (escrow.getStatus() == PouchDepositEscrow.Status.DEBITED) {
                PouchBalance internal = getInternalPouchBalance(escrow.getPouchUUID(), null);
                internal.addCount(escrow.getTier(), escrow.getCount());
                LOGGER.info("Pocket Odds Audit: Credited debited deposit {} to pouch {}", escrow.getDepositId(), escrow.getPouchUUID());
            } else if (escrow.getStatus() == PouchDepositEscrow.Status.PREPARED) {
                int expected = escrow.getExpectedPlayerChipCount();
                int current = chipCounter.apply(escrow.getTier());
                int take = escrow.getCount();

                if (expected >= 0 && current <= (expected - take)) {
                    // Chips were indeed removed before crash! Create safe refund transaction in outbox.
                    List<ItemStack> refund = net.pocketodds.util.ChipUtils.convertAmountToChips((long) take * escrow.getTier().getBaseValue());
                    RewardTransaction refundTx = new RewardTransaction(UUID.randomUUID(), playerUUID, refund);
                    if (refundSink != null) {
                        refundSink.accept(refundTx);
                    } else {
                        enqueueRewardTransaction(refundTx);
                    }
                    LOGGER.warn("Pocket Odds Audit: Player {} was missing {} {} chips from uncommitted deposit {}. Enqueued refund {}.",
                            playerUUID, take, escrow.getTier(), escrow.getDepositId(), refundTx.getTransactionId());
                } else {
                    LOGGER.info("Pocket Odds Audit: Prepared deposit {} canceled for player {} (chips present: expected={}, current={})",
                            escrow.getDepositId(), playerUUID, expected, current);
                }
            }
        }
        setDirty();
    }

    // --- Roulette History Methods ---

    public synchronized void addRouletteNumber(int number) {
        rouletteHistory.add(0, number);
        while (rouletteHistory.size() > 10) {
            rouletteHistory.remove(rouletteHistory.size() - 1);
        }
        setDirty();
    }

    public synchronized int[] getRecentRouletteHistory() {
        int[] result = new int[Math.min(5, rouletteHistory.size())];
        for (int i = 0; i < result.length; i++) {
            result[i] = rouletteHistory.get(i);
        }
        return result;
    }

    // --- Last Game Session Methods ---

    public static final long SESSION_TTL_MS = 60_000L; // 1 minute TTL for recent game sessions

    public synchronized void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        boolean changed = lastPlayerSessions.entrySet().removeIf(entry ->
                (now - entry.getValue().getGameTimestamp()) > SESSION_TTL_MS
        );
        if (changed) {
            setDirty();
        }
    }

    public synchronized void saveLastGameSession(UUID playerUUID, CasinoGameSession session) {
        if (playerUUID != null && session != null) {
            cleanupExpiredSessions();
            lastPlayerSessions.put(playerUUID, session);
            setDirty();
        }
    }

    public synchronized CasinoGameSession getLastGameSession(UUID playerUUID) {
        if (playerUUID == null) return null;
        CasinoGameSession session = lastPlayerSessions.get(playerUUID);
        if (session != null) {
            long elapsed = System.currentTimeMillis() - session.getGameTimestamp();
            if (elapsed > SESSION_TTL_MS) {
                lastPlayerSessions.remove(playerUUID);
                setDirty();
                return null;
            }
        }
        return session;
    }

    public synchronized void removeLastGameSession(UUID playerUUID) {
        if (playerUUID != null && lastPlayerSessions.remove(playerUUID) != null) {
            setDirty();
        }
    }

    public synchronized void markDeckSessionDelivered(UUID sessionId) {
        DeckSession s = deckSessions.get(sessionId);
        if (s != null) {
            s.setStatus(DeckSession.Status.DELIVERED);
            s.setActive(false);
        }
        if (sessionId != null) {
            UUID cashoutTxId = UUID.nameUUIDFromBytes(("deck_cashout:" + sessionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            recordCompletedReceipt(cashoutTxId);
            recordCompletedReceipt(sessionId);
            deckSessions.remove(sessionId);
        }
        setDirty();
    }

    public synchronized void recordCompletedReceipt(UUID id) {
        if (id != null) {
            completedReceipts.add(id);
            if (completedReceipts.size() > 5000) {
                Iterator<UUID> it = completedReceipts.iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            setDirty();
        }
    }

    public synchronized boolean isReceiptCompleted(UUID id) {
        return id != null && completedReceipts.contains(id);
    }

    // --- Transactional Outbox Methods ---

    public synchronized void commitDeckCashout(UUID sessionId, UUID cashoutTxId, UUID playerUUID, RewardBundle bundle) {
        commitDeckCashout(sessionId, cashoutTxId, playerUUID, bundle, PayoutDestination.INVENTORY);
    }

    public synchronized void commitDeckCashout(UUID sessionId, UUID cashoutTxId, UUID playerUUID, RewardBundle bundle, PayoutDestination payoutDestination) {
        DeckSession session = deckSessions.get(sessionId);
        if (session != null) {
            session.setStatus(DeckSession.Status.CASHOUT_COMMITTED);
            session.setActive(false);
        }
        if (cashoutTxId != null && playerUUID != null && bundle != null && !bundle.isEmpty()) {
            enqueueRewardTransaction(RewardTransaction.fromBundle(cashoutTxId, playerUUID, bundle, payoutDestination));
        }
        setDirty();
    }

    public synchronized void settleAndCommitBet(BetPreparation prep, UUID txId, UUID playerUUID, RewardBundle bundle) {
        settleAndCommitBet(prep, txId, playerUUID, bundle, PayoutDestination.INVENTORY);
    }

    public synchronized void settleAndCommitBet(BetPreparation prep, UUID txId, UUID playerUUID, RewardBundle bundle, PayoutDestination payoutDestination) {
        if (prep != null) {
            prep.setStatus(BetPreparation.PreparationStatus.COMMITTED);
            preparedBets.remove(prep.getPreparationId());
        }
        if (txId != null && playerUUID != null) {
            enqueueRewardTransaction(RewardTransaction.fromBundle(txId, playerUUID, bundle, payoutDestination));
        }
        setDirty();
    }

    public synchronized void enqueueRewardTransaction(RewardTransaction transaction) {
        if (transaction != null) {
            for (RewardTransaction existing : pendingTransactions) {
                if (existing.getTransactionId().equals(transaction.getTransactionId())) {
                    return; // Idempotent: transaction with this rollId/transactionId is already recorded
                }
            }
            pendingTransactions.add(transaction);
            setDirty();
        }
    }

    public synchronized boolean hasPendingTransaction(UUID transactionId) {
        if (transactionId == null) return false;
        for (RewardTransaction tx : pendingTransactions) {
            if (tx.getTransactionId().equals(transactionId)) {
                return true;
            }
        }
        return false;
    }

    public synchronized RewardTransaction getTransaction(UUID transactionId) {
        if (transactionId == null) return null;
        for (RewardTransaction tx : pendingTransactions) {
            if (tx.getTransactionId().equals(transactionId)) {
                return RewardTransaction.fromLines(tx.getTransactionId(), tx.getPlayerUUID(), tx.getLines(), tx.isJackpot(), tx.getJackpotAmount(), tx.isJackpotClaimed(), tx.getPayoutDestination());
            }
        }
        return null;
    }

    public synchronized boolean claimJackpotForTransaction(UUID transactionId) {
        if (transactionId == null) return false;
        for (RewardTransaction tx : pendingTransactions) {
            if (tx.getTransactionId().equals(transactionId)) {
                if (tx.isJackpot() && !tx.isJackpotClaimed()) {
                    long baseAmount = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                            ? PocketOddsConfig.SERVER.jackpotBaseAmount.get() : 100L;
                    this.jackpotAmount = baseAmount;
                    tx.setJackpotClaimed(true);
                    setDirty();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public synchronized List<RewardTransaction> getPendingTransactions(UUID playerUUID) {
        List<RewardTransaction> result = new ArrayList<>();
        for (RewardTransaction tx : pendingTransactions) {
            if (tx.getPlayerUUID().equals(playerUUID)) {
                result.add(RewardTransaction.fromLines(tx.getTransactionId(), tx.getPlayerUUID(), tx.getLines(), tx.isJackpot(), tx.getJackpotAmount(), tx.isJackpotClaimed(), tx.getPayoutDestination()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean creditRewardLineToPouch(UUID pouchUUID, UUID transactionId, UUID lineId) {
        return creditRewardLineToPouch(pouchUUID, transactionId, lineId, null);
    }

    public synchronized boolean creditRewardLineToPouch(UUID pouchUUID, UUID transactionId, UUID lineId, ItemStack initialStack) {
        if (pouchUUID == null || transactionId == null || lineId == null) return false;

        for (Iterator<RewardTransaction> it = pendingTransactions.iterator(); it.hasNext(); ) {
            RewardTransaction tx = it.next();
            if (tx.getTransactionId().equals(transactionId)) {
                // 1. Find undelivered line
                RewardLine targetLine = null;
                for (RewardLine line : tx.lines) {
                    if (!line.isDelivered() && line.getLineId().equals(lineId)) {
                        targetLine = line;
                        break;
                    }
                }
                if (targetLine == null) {
                    return false;
                }

                // 2. Verify that it is chips
                ItemStack stack = targetLine.getStack();
                if (stack.isEmpty() || !(stack.getItem() instanceof ChipItem chipItem)) {
                    return false;
                }

                // 3. Atomically check and credit to internal pouch balance
                PouchBalance internal = getInternalPouchBalance(pouchUUID, initialStack);
                if (!internal.canAdd(chipItem.getTier(), stack.getCount())) {
                    LOGGER.warn("Pocket Odds: Pouch {} cannot accept {} {} chips (balance limit/overflow). Transaction line {} deferred.",
                            pouchUUID, stack.getCount(), chipItem.getTier(), lineId);
                    return false;
                }

                boolean added = internal.addCount(chipItem.getTier(), stack.getCount());
                if (!added) {
                    return false;
                }

                // 4. Confirm the line as delivered ONLY if successfully credited
                boolean removed = tx.confirmDeliveredLine(lineId);
                if (tx.isEmpty()) {
                    recordCompletedReceipt(tx.getTransactionId());
                    it.remove();
                }

                // 5. Mark dirty after both mutations
                setDirty();
                return removed;
            }
        }
        return false;
    }

    public synchronized boolean confirmDeliveredLine(UUID transactionId, UUID lineId) {
        for (Iterator<RewardTransaction> it = pendingTransactions.iterator(); it.hasNext(); ) {
            RewardTransaction tx = it.next();
            if (tx.getTransactionId().equals(transactionId)) {
                boolean removed = tx.confirmDeliveredLine(lineId);
                if (tx.isEmpty()) {
                    recordCompletedReceipt(tx.getTransactionId());
                    it.remove();
                }
                setDirty();
                return removed;
            }
        }
        return false;
    }

    public synchronized boolean confirmDeliveredItem(UUID transactionId, ItemStack stack) {
        for (Iterator<RewardTransaction> it = pendingTransactions.iterator(); it.hasNext(); ) {
            RewardTransaction tx = it.next();
            if (tx.getTransactionId().equals(transactionId)) {
                boolean removed = tx.removeDeliveredItem(stack);
                if (tx.isEmpty()) {
                    recordCompletedReceipt(tx.getTransactionId());
                    it.remove();
                }
                setDirty();
                return removed;
            }
        }
        return false;
    }

    public synchronized void removePendingTransaction(UUID transactionId) {
        if (pendingTransactions.removeIf(tx -> tx.getTransactionId().equals(transactionId))) {
            recordCompletedReceipt(transactionId);
            setDirty();
        }
    }

    // Legacy and testing convenience wrappers
    public synchronized void addPendingReward(UUID playerUUID, ItemStack stack) {
        if (stack.isEmpty()) return;
        List<ItemStack> list = new ArrayList<>();
        list.add(stack.copy());
        enqueueRewardTransaction(new RewardTransaction(UUID.randomUUID(), playerUUID, list));
    }

    public synchronized List<ItemStack> popPendingRewards(UUID playerUUID) {
        List<ItemStack> delivered = new ArrayList<>();
        for (Iterator<RewardTransaction> it = pendingTransactions.iterator(); it.hasNext(); ) {
            RewardTransaction tx = it.next();
            if (tx.getPlayerUUID().equals(playerUUID)) {
                for (ItemStack stack : tx.getItems()) {
                    delivered.add(stack.copy());
                }
                it.remove();
            }
        }
        if (!delivered.isEmpty()) {
            setDirty();
        }
        return delivered;
    }

    public synchronized List<ItemStack> getPendingRewards(UUID playerUUID) {
        List<ItemStack> list = new ArrayList<>();
        for (RewardTransaction tx : pendingTransactions) {
            if (tx.getPlayerUUID().equals(playerUUID)) {
                for (ItemStack stack : tx.getItems()) {
                    list.add(stack.copy());
                }
            }
        }
        return Collections.unmodifiableList(list);
    }

    public synchronized void clearPendingRewards(UUID playerUUID) {
        if (pendingTransactions.removeIf(tx -> tx.getPlayerUUID().equals(playerUUID))) {
            setDirty();
        }
    }

    // Active session persistence
    public synchronized void saveActiveSession(UUID playerUUID, CompoundTag sessionTag) {
        activeSessions.put(playerUUID, sessionTag);
        setDirty();
    }

    public synchronized void removeActiveSession(UUID playerUUID) {
        if (activeSessions.remove(playerUUID) != null) {
            setDirty();
        }
    }

    public synchronized Map<UUID, CompoundTag> getActiveSessions() {
        return Collections.unmodifiableMap(new HashMap<>(activeSessions));
    }

    public synchronized boolean isShopOperationProcessed(UUID operationId) {
        if (operationId == null) return false;
        return shopPurchases.containsKey(operationId);
    }

    public synchronized void recordShopPurchase(ShopPurchaseTransaction tx) {
        if (tx != null) {
            shopPurchases.put(tx.getOperationId(), tx);
            setDirty();
        }
    }

    public synchronized void updateShopPurchaseState(UUID operationId, ShopPurchaseTransaction.State newState) {
        if (operationId == null || newState == null) return;
        ShopPurchaseTransaction tx = shopPurchases.get(operationId);
        if (tx != null) {
            tx.setState(newState);
            setDirty();
        }
    }

    public synchronized ShopPurchaseTransaction getShopPurchase(UUID operationId) {
        return operationId != null ? shopPurchases.get(operationId) : null;
    }

    public synchronized int getPlayerPurchaseCount(UUID playerUUID, String offerId, ShopLimitPeriod period) {
        if (playerUUID == null || offerId == null || period == ShopLimitPeriod.UNLIMITED) return 0;
        Map<String, PlayerPurchaseRecord> playerRecords = playerShopLimits.get(playerUUID);
        if (playerRecords == null) return 0;
        PlayerPurchaseRecord record = playerRecords.get(offerId);
        if (record == null) return 0;

        long currentDay = System.currentTimeMillis() / (1000L * 86400L);
        long currentWeek = currentDay / 7L;
        return record.getEffectiveCount(currentDay, currentWeek, period);
    }

    public synchronized void incrementPlayerPurchaseCount(UUID playerUUID, String offerId, ShopLimitPeriod period) {
        if (playerUUID == null || offerId == null || period == ShopLimitPeriod.UNLIMITED) return;
        long currentDay = System.currentTimeMillis() / (1000L * 86400L);
        long currentWeek = currentDay / 7L;

        playerShopLimits.computeIfAbsent(playerUUID, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(offerId, k -> new PlayerPurchaseRecord(0, currentDay, currentWeek))
                .increment(currentDay, currentWeek, period);
        setDirty();
    }

    public static JackpotSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                JackpotSavedData::load,
                JackpotSavedData::new,
                DATA_NAME
        );
    }
}
