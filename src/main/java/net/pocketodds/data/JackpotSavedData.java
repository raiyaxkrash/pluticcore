package net.pocketodds.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.pocketodds.config.PocketOddsConfig;

import java.util.*;

public class JackpotSavedData extends SavedData {
    public static final String DATA_NAME = "pocketodds_jackpot";
    private long jackpotAmount;

    // Transactional outbox for rewards
    private final List<RewardTransaction> pendingTransactions = new ArrayList<>();
    private final Map<UUID, CompoundTag> activeSessions = new HashMap<>();

    public static class RewardTransaction {
        private final UUID transactionId;
        private final UUID playerUUID;
        private final List<ItemStack> items;
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
            this.items = new ArrayList<>();
            if (items != null) {
                for (ItemStack stack : items) {
                    if (stack != null && !stack.isEmpty()) {
                        this.items.add(stack.copy());
                    }
                }
            }
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

        public List<ItemStack> getItems() {
            List<ItemStack> copy = new ArrayList<>(items.size());
            for (ItemStack stack : items) {
                copy.add(stack.copy());
            }
            return Collections.unmodifiableList(copy);
        }

        boolean removeDeliveredItem(ItemStack stack) {
            for (Iterator<ItemStack> it = items.iterator(); it.hasNext(); ) {
                ItemStack item = it.next();
                if (ItemStack.isSameItemSameTags(item, stack)) {
                    if (item.getCount() <= stack.getCount()) {
                        it.remove();
                    } else {
                        item.shrink(stack.getCount());
                    }
                    return true;
                }
            }
            return false;
        }

        public boolean isEmpty() {
            return items.isEmpty();
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
            for (ItemStack stack : items) {
                listTag.add(stack.save(new CompoundTag()));
            }
            tag.put("Items", listTag);
            return tag;
        }

        public static RewardTransaction fromNbt(CompoundTag tag) {
            UUID txId = tag.contains("TransactionId") ? tag.getUUID("TransactionId") : UUID.randomUUID();
            UUID pId = tag.getUUID("PlayerUUID");
            boolean jackpot = tag.getBoolean("Jackpot");
            long jackpotAmount = tag.getLong("JackpotAmount");
            boolean jackpotClaimed = tag.getBoolean("JackpotClaimed");
            ListTag listTag = tag.getList("Items", Tag.TAG_COMPOUND);
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < listTag.size(); i++) {
                ItemStack stack = ItemStack.of(listTag.getCompound(i));
                if (!stack.isEmpty()) {
                    items.add(stack);
                }
            }
            return new RewardTransaction(txId, pId, items, jackpot, jackpotAmount, jackpotClaimed);
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

        // Load new transactional outbox
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
                } catch (IllegalArgumentException ignored) {
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
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("JackpotAmount", this.jackpotAmount);

        // Save transactional outbox
        if (!pendingTransactions.isEmpty()) {
            ListTag txList = new ListTag();
            for (RewardTransaction tx : pendingTransactions) {
                if (!tx.isEmpty()) {
                    txList.add(tx.toNbt());
                }
            }
            tag.put("PendingTransactions", txList);
        }

        // Save active sessions with current live tick state
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

        return tag;
    }

    public long getJackpotAmount() {
        return jackpotAmount;
    }

    public void addContribution(long betValue) {
        if (betValue <= 0) {
            return;
        }
        double rate = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? PocketOddsConfig.SERVER.jackpotContributionRate.get() : 0.05;
        if (rate <= 0.0) {
            return;
        }
        long contribution = Math.max(1L, (long) Math.round(betValue * rate));
        this.jackpotAmount += contribution;
        setDirty();
    }

    public long claimJackpot() {
        long payout = this.jackpotAmount;
        long baseAmount = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? PocketOddsConfig.SERVER.jackpotBaseAmount.get() : 100L;
        this.jackpotAmount = baseAmount;
        setDirty();
        return payout;
    }

    // --- Transactional Outbox Methods ---

    public synchronized void enqueueRewardTransaction(RewardTransaction transaction) {
        if (transaction != null && !transaction.isEmpty()) {
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
                return new RewardTransaction(tx.getTransactionId(), tx.getPlayerUUID(), tx.getItems(), tx.isJackpot(), tx.getJackpotAmount(), tx.isJackpotClaimed());
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
            if (tx.getPlayerUUID().equals(playerUUID) && !tx.isEmpty()) {
                result.add(new RewardTransaction(tx.getTransactionId(), tx.getPlayerUUID(), tx.getItems(), tx.isJackpot(), tx.getJackpotAmount(), tx.isJackpotClaimed()));
            }
        }
        return Collections.unmodifiableList(result);
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
