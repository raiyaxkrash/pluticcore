package net.pocketodds.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.gambling.core.BetPreparation;
import net.pocketodds.gambling.core.DeckSession;
import net.pocketodds.gambling.core.RewardBundle;
import net.pocketodds.gambling.core.RewardLine;
import net.pocketodds.item.DeckOfFateItem;
import net.pocketodds.util.ChipUtils;

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

    public static class RewardTransaction {
        private final UUID transactionId;
        private final UUID playerUUID;
        private final List<RewardLine> lines;
        private final boolean jackpot;
        private final long jackpotAmount;
        private boolean jackpotClaimed;

        public RewardTransaction(UUID transactionId, UUID playerUUID, List<ItemStack> items) {
            this(transactionId, playerUUID, items, false, 0L, false);
        }

        public RewardTransaction(UUID transactionId, UUID playerUUID, List<ItemStack> items, boolean jackpot, long jackpotAmount, boolean jackpotClaimed) {
            this.transactionId = transactionId != null ? transactionId : UUID.randomUUID();
            this.playerUUID = playerUUID;
            this.jackpot = jackpot;
            this.jackpotAmount = jackpotAmount;
            this.jackpotClaimed = jackpotClaimed;
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
            RewardTransaction tx = new RewardTransaction(transactionId, playerUUID, Collections.emptyList(), jackpot, jackpotAmount, jackpotClaimed);
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
            if (bundle == null || bundle.isEmpty()) {
                return new RewardTransaction(transactionId, playerUUID, Collections.emptyList(), false, 0L, false);
            }
            List<ItemStack> items = new ArrayList<>(bundle.getItems());
            if (bundle.isJackpot() && bundle.getJackpotCredits() > 0L) {
                items.addAll(ChipUtils.convertAmountToChips(bundle.getJackpotCredits()));
            }
            return new RewardTransaction(transactionId, playerUUID, items, bundle.isJackpot(), bundle.getJackpotCredits(), false);
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

        public CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("TransactionId", transactionId);
            tag.putUUID("PlayerUUID", playerUUID);
            if (jackpot) {
                tag.putBoolean("Jackpot", true);
                tag.putLong("JackpotAmount", jackpotAmount);
                tag.putBoolean("JackpotClaimed", jackpotClaimed);
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
            return fromLines(txId, pId, lines, jackpot, jackpotAmount, jackpotClaimed);
        }
    }

    public JackpotSavedData() {
        this.jackpotAmount = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? PocketOddsConfig.SERVER.jackpotBaseAmount.get() : 100L;
    }

    public JackpotSavedData(long initialAmount) {
        this.jackpotAmount = initialAmount;
    }

    public static JackpotSavedData load(CompoundTag tag) {
        long amount = tag.getLong("JackpotAmount");
        long baseAmount = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? PocketOddsConfig.SERVER.jackpotBaseAmount.get() : 100L;
        if (amount < baseAmount) {
            amount = baseAmount;
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

        return data;
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

        return tag;
    }

    public long getJackpotAmount() {
        return jackpotAmount;
    }

    public void addContributionBasisPoints(long totalCredits, int basisPoints) {
        if (totalCredits <= 0 || basisPoints <= 0) {
            return;
        }
        long contribution = Math.max(1L, (totalCredits * (long) basisPoints) / 10_000L);
        this.jackpotAmount += contribution;
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
        long payout = this.jackpotAmount;
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

    // --- Transactional Outbox Methods ---

    public synchronized void commitDeckCashout(UUID sessionId, UUID cashoutTxId, UUID playerUUID, RewardBundle bundle) {
        DeckSession session = deckSessions.get(sessionId);
        if (session != null) {
            session.setStatus(DeckSession.Status.CASHOUT_COMMITTED);
            session.setActive(false);
        }
        if (cashoutTxId != null && playerUUID != null && bundle != null && !bundle.isEmpty()) {
            enqueueRewardTransaction(RewardTransaction.fromBundle(cashoutTxId, playerUUID, bundle));
        }
        setDirty();
    }

    public synchronized void settleAndCommitBet(BetPreparation prep, UUID txId, UUID playerUUID, RewardBundle bundle) {
        if (prep != null) {
            prep.setStatus(BetPreparation.PreparationStatus.COMMITTED);
            preparedBets.remove(prep.getPreparationId());
        }
        if (txId != null && playerUUID != null) {
            enqueueRewardTransaction(RewardTransaction.fromBundle(txId, playerUUID, bundle));
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
                return RewardTransaction.fromLines(tx.getTransactionId(), tx.getPlayerUUID(), tx.getLines(), tx.isJackpot(), tx.getJackpotAmount(), tx.isJackpotClaimed());
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
                result.add(RewardTransaction.fromLines(tx.getTransactionId(), tx.getPlayerUUID(), tx.getLines(), tx.isJackpot(), tx.getJackpotAmount(), tx.isJackpotClaimed()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean confirmDeliveredLine(UUID transactionId, UUID lineId) {
        for (Iterator<RewardTransaction> it = pendingTransactions.iterator(); it.hasNext(); ) {
            RewardTransaction tx = it.next();
            if (tx.getTransactionId().equals(transactionId)) {
                boolean removed = tx.confirmDeliveredLine(lineId);
                if (tx.isEmpty()) {
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

    public static JackpotSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                JackpotSavedData::load,
                JackpotSavedData::new,
                DATA_NAME
        );
    }
}
