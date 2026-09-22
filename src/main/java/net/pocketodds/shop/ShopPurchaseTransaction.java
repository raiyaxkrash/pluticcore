package net.pocketodds.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.item.ChipTier;

import java.util.*;

public class ShopPurchaseTransaction {

    public enum State {
        PREPARED,
        PAYMENT_DEBITED,
        REWARD_QUEUED,
        COMMITTED,
        REFUND_QUEUED,
        CANCELLED;

        public static State fromString(String name) {
            if (name == null) return PREPARED;
            if ("TOKENS_DEBITED".equalsIgnoreCase(name)) return PAYMENT_DEBITED;
            try {
                return State.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return PREPARED;
            }
        }
    }

    private final UUID operationId;
    private final UUID playerUUID;
    private final String offerId;
    private final long priceCredits;
    private final ItemStack rewardStack;
    private final ShopPaymentSource paymentSource;
    private final Map<ChipTier, Integer> inventoryDebits;
    private final Map<ChipTier, Long> pouchDebits;
    private final Map<ChipTier, Integer> actualInventoryDebits;
    private final Map<ChipTier, Long> actualPouchDebits;
    private final long changeCredits;
    private final List<ItemStack> changeStacks;
    private final ShopLimitPeriod limitPeriod;
    private boolean limitRecorded;
    private State state;
    private final long timestamp;

    public ShopPurchaseTransaction(UUID operationId, UUID playerUUID, String offerId, long priceCredits,
                                   ItemStack rewardStack, ShopPaymentSource paymentSource,
                                   Map<ChipTier, Integer> inventoryDebits, Map<ChipTier, Long> pouchDebits,
                                   Map<ChipTier, Integer> actualInventoryDebits, Map<ChipTier, Long> actualPouchDebits,
                                   long changeCredits, List<ItemStack> changeStacks,
                                   ShopLimitPeriod limitPeriod, boolean limitRecorded,
                                   State state, long timestamp) {
        this.operationId = operationId != null ? operationId : UUID.randomUUID();
        this.playerUUID = playerUUID != null ? playerUUID : UUID.randomUUID();
        this.offerId = offerId != null ? offerId : "";
        this.priceCredits = Math.max(1L, priceCredits);
        this.rewardStack = rewardStack != null ? rewardStack.copy() : ItemStack.EMPTY;
        this.paymentSource = paymentSource != null ? paymentSource : ShopPaymentSource.INVENTORY;
        this.inventoryDebits = new EnumMap<>(ChipTier.class);
        if (inventoryDebits != null) {
            this.inventoryDebits.putAll(inventoryDebits);
        }
        this.pouchDebits = new EnumMap<>(ChipTier.class);
        if (pouchDebits != null) {
            this.pouchDebits.putAll(pouchDebits);
        }
        this.actualInventoryDebits = new EnumMap<>(ChipTier.class);
        if (actualInventoryDebits != null) {
            this.actualInventoryDebits.putAll(actualInventoryDebits);
        }
        this.actualPouchDebits = new EnumMap<>(ChipTier.class);
        if (actualPouchDebits != null) {
            this.actualPouchDebits.putAll(actualPouchDebits);
        }
        this.changeCredits = Math.max(0L, changeCredits);
        this.changeStacks = new ArrayList<>();
        if (changeStacks != null) {
            for (ItemStack s : changeStacks) {
                if (!s.isEmpty()) {
                    this.changeStacks.add(s.copy());
                }
            }
        }
        this.limitPeriod = limitPeriod != null ? limitPeriod : ShopLimitPeriod.UNLIMITED;
        this.limitRecorded = limitRecorded;
        this.state = state != null ? state : State.PREPARED;
        this.timestamp = timestamp;
    }

    public ShopPurchaseTransaction(UUID operationId, UUID playerUUID, String offerId, long priceCredits,
                                   ItemStack rewardStack, ShopPaymentSource paymentSource,
                                   Map<ChipTier, Integer> inventoryDebits, Map<ChipTier, Long> pouchDebits,
                                   long changeCredits, List<ItemStack> changeStacks,
                                   ShopLimitPeriod limitPeriod, boolean limitRecorded,
                                   State state, long timestamp) {
        this(operationId, playerUUID, offerId, priceCredits, rewardStack, paymentSource,
                inventoryDebits, pouchDebits, null, null,
                changeCredits, changeStacks, limitPeriod, limitRecorded, state, timestamp);
    }

    public UUID getOperationId() {
        return operationId;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getOfferId() {
        return offerId;
    }

    public long getPriceCredits() {
        return priceCredits;
    }

    public int getPrice() {
        return (int) Math.min(Integer.MAX_VALUE, priceCredits);
    }

    public ItemStack getRewardStack() {
        return rewardStack.copy();
    }

    public ShopPaymentSource getPaymentSource() {
        return paymentSource;
    }

    public Map<ChipTier, Integer> getInventoryDebits() {
        return Collections.unmodifiableMap(inventoryDebits);
    }

    public Map<ChipTier, Integer> getPlannedInventoryDebits() {
        return Collections.unmodifiableMap(inventoryDebits);
    }

    public Map<ChipTier, Long> getPouchDebits() {
        return Collections.unmodifiableMap(pouchDebits);
    }

    public Map<ChipTier, Long> getPlannedPouchDebits() {
        return Collections.unmodifiableMap(pouchDebits);
    }

    public Map<ChipTier, Integer> getActualInventoryDebits() {
        return Collections.unmodifiableMap(actualInventoryDebits);
    }

    public Map<ChipTier, Long> getActualPouchDebits() {
        return Collections.unmodifiableMap(actualPouchDebits);
    }

    public synchronized void recordActualInventoryDebit(ChipTier tier, int count) {
        if (tier != null && count > 0) {
            actualInventoryDebits.merge(tier, count, Integer::sum);
        }
    }

    public synchronized void recordActualPouchDebit(ChipTier tier, long count) {
        if (tier != null && count > 0) {
            actualPouchDebits.merge(tier, count, Long::sum);
        }
    }

    public boolean hasActualDebits() {
        return !actualInventoryDebits.isEmpty() || !actualPouchDebits.isEmpty();
    }

    public List<ItemStack> createActualRefundStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        for (Map.Entry<ChipTier, Integer> e : actualInventoryDebits.entrySet()) {
            if (e.getValue() > 0) {
                stacks.addAll(net.pocketodds.util.ChipUtils.splitChips(e.getKey().getItem(), e.getValue()));
            }
        }
        for (Map.Entry<ChipTier, Long> e : actualPouchDebits.entrySet()) {
            if (e.getValue() > 0) {
                stacks.addAll(net.pocketodds.util.ChipUtils.splitChips(e.getKey().getItem(), e.getValue()));
            }
        }
        return stacks;
    }

    public long getChangeCredits() {
        return changeCredits;
    }

    public List<ItemStack> getChangeStacks() {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack s : changeStacks) {
            list.add(s.copy());
        }
        return list;
    }

    public ShopLimitPeriod getLimitPeriod() {
        return limitPeriod;
    }

    public boolean isLimitRecorded() {
        return limitRecorded;
    }

    public void setLimitRecorded(boolean limitRecorded) {
        this.limitRecorded = limitRecorded;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("OperationId", operationId);
        tag.putUUID("PlayerUUID", playerUUID);
        tag.putString("OfferId", offerId);
        tag.putLong("PriceCredits", priceCredits);
        if (!rewardStack.isEmpty()) {
            tag.put("RewardStack", rewardStack.save(new CompoundTag()));
        }
        tag.putString("PaymentSource", paymentSource.name());

        if (!inventoryDebits.isEmpty()) {
            CompoundTag invTag = new CompoundTag();
            for (Map.Entry<ChipTier, Integer> e : inventoryDebits.entrySet()) {
                if (e.getValue() > 0) {
                    invTag.putInt(e.getKey().name(), e.getValue());
                }
            }
            tag.put("InventoryDebits", invTag);
        }

        if (!pouchDebits.isEmpty()) {
            CompoundTag pouchTag = new CompoundTag();
            for (Map.Entry<ChipTier, Long> e : pouchDebits.entrySet()) {
                if (e.getValue() > 0) {
                    pouchTag.putLong(e.getKey().name(), e.getValue());
                }
            }
            tag.put("PouchDebits", pouchTag);
        }

        if (!actualInventoryDebits.isEmpty()) {
            CompoundTag actInvTag = new CompoundTag();
            for (Map.Entry<ChipTier, Integer> e : actualInventoryDebits.entrySet()) {
                if (e.getValue() > 0) {
                    actInvTag.putInt(e.getKey().name(), e.getValue());
                }
            }
            tag.put("ActualInventoryDebits", actInvTag);
        }

        if (!actualPouchDebits.isEmpty()) {
            CompoundTag actPouchTag = new CompoundTag();
            for (Map.Entry<ChipTier, Long> e : actualPouchDebits.entrySet()) {
                if (e.getValue() > 0) {
                    actPouchTag.putLong(e.getKey().name(), e.getValue());
                }
            }
            tag.put("ActualPouchDebits", actPouchTag);
        }

        tag.putLong("ChangeCredits", changeCredits);

        if (!changeStacks.isEmpty()) {
            ListTag listTag = new ListTag();
            for (ItemStack s : changeStacks) {
                if (!s.isEmpty()) {
                    listTag.add(s.save(new CompoundTag()));
                }
            }
            tag.put("ChangeStacks", listTag);
        }

        tag.putString("LimitPeriod", limitPeriod.name());
        tag.putBoolean("LimitRecorded", limitRecorded);
        tag.putString("State", state.name());
        tag.putLong("Timestamp", timestamp);
        return tag;
    }

    public static ShopPurchaseTransaction fromNbt(CompoundTag tag) {
        UUID opId = tag.contains("OperationId") ? tag.getUUID("OperationId") : UUID.randomUUID();
        UUID playerUUID = tag.contains("PlayerUUID") ? tag.getUUID("PlayerUUID") : UUID.randomUUID();
        String offerId = tag.getString("OfferId");
        long priceCredits = tag.contains("PriceCredits") ? tag.getLong("PriceCredits") : (long) tag.getInt("Price");
        ItemStack rewardStack = tag.contains("RewardStack") ? ItemStack.of(tag.getCompound("RewardStack")) : ItemStack.EMPTY;

        ShopPaymentSource source = ShopPaymentSource.INVENTORY;
        if (tag.contains("PaymentSource")) {
            try {
                source = ShopPaymentSource.valueOf(tag.getString("PaymentSource"));
            } catch (Exception ignored) {}
        }

        Map<ChipTier, Integer> invDebits = new EnumMap<>(ChipTier.class);
        if (tag.contains("InventoryDebits")) {
            CompoundTag invTag = tag.getCompound("InventoryDebits");
            for (ChipTier tier : ChipTier.values()) {
                if (invTag.contains(tier.name())) {
                    invDebits.put(tier, invTag.getInt(tier.name()));
                }
            }
        }

        Map<ChipTier, Long> pouchDebits = new EnumMap<>(ChipTier.class);
        if (tag.contains("PouchDebits")) {
            CompoundTag pouchTag = tag.getCompound("PouchDebits");
            for (ChipTier tier : ChipTier.values()) {
                if (pouchTag.contains(tier.name())) {
                    pouchDebits.put(tier, pouchTag.getLong(tier.name()));
                }
            }
        }

        Map<ChipTier, Integer> actInvDebits = new EnumMap<>(ChipTier.class);
        if (tag.contains("ActualInventoryDebits")) {
            CompoundTag actInvTag = tag.getCompound("ActualInventoryDebits");
            for (ChipTier tier : ChipTier.values()) {
                if (actInvTag.contains(tier.name())) {
                    actInvDebits.put(tier, actInvTag.getInt(tier.name()));
                }
            }
        }

        Map<ChipTier, Long> actPouchDebits = new EnumMap<>(ChipTier.class);
        if (tag.contains("ActualPouchDebits")) {
            CompoundTag actPouchTag = tag.getCompound("ActualPouchDebits");
            for (ChipTier tier : ChipTier.values()) {
                if (actPouchTag.contains(tier.name())) {
                    actPouchDebits.put(tier, actPouchTag.getLong(tier.name()));
                }
            }
        }

        long changeCredits = tag.contains("ChangeCredits") ? tag.getLong("ChangeCredits") : 0L;

        List<ItemStack> changeStacks = new ArrayList<>();
        if (tag.contains("ChangeStacks", Tag.TAG_LIST)) {
            ListTag listTag = tag.getList("ChangeStacks", Tag.TAG_COMPOUND);
            for (int i = 0; i < listTag.size(); i++) {
                ItemStack s = ItemStack.of(listTag.getCompound(i));
                if (!s.isEmpty()) {
                    changeStacks.add(s);
                }
            }
        }

        ShopLimitPeriod limitPeriod = ShopLimitPeriod.UNLIMITED;
        if (tag.contains("LimitPeriod")) {
            limitPeriod = ShopLimitPeriod.fromString(tag.getString("LimitPeriod"));
        }

        boolean limitRecorded = tag.getBoolean("LimitRecorded");
        State state = State.fromString(tag.getString("State"));
        long timestamp = tag.getLong("Timestamp");

        // Backwards compatibility: if PAYMENT_DEBITED, REWARD_QUEUED or COMMITTED, and actual debits tags were not present,
        // then actual debits equal planned debits.
        if (state == State.PAYMENT_DEBITED || state == State.REWARD_QUEUED || state == State.COMMITTED) {
            if (actInvDebits.isEmpty() && !invDebits.isEmpty()) {
                actInvDebits.putAll(invDebits);
            }
            if (actPouchDebits.isEmpty() && !pouchDebits.isEmpty()) {
                actPouchDebits.putAll(pouchDebits);
            }
        }

        return new ShopPurchaseTransaction(
                opId, playerUUID, offerId, priceCredits, rewardStack, source,
                invDebits, pouchDebits, actInvDebits, actPouchDebits,
                changeCredits, changeStacks,
                limitPeriod, limitRecorded, state, timestamp
        );
    }
}
