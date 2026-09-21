package net.pocketodds.gambling.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.item.ChipTier;

import java.util.Objects;

public class BetSnapshot {
    public enum BetType {
        CHIP,
        ITEM
    }

    private final BetType betType;
    private final ChipTier chipTier;
    private final ItemStack itemPrototype;
    private final int betCount;
    private final long unitCreditValue;
    private final long totalCreditValue;

    private BetSnapshot(BetType betType, ChipTier chipTier, ItemStack itemPrototype, int betCount, long unitCreditValue) {
        if (betCount <= 0) {
            throw new IllegalArgumentException("betCount must be positive, got " + betCount);
        }
        if (unitCreditValue <= 0) {
            throw new IllegalArgumentException("unitCreditValue must be positive, got " + unitCreditValue);
        }
        this.betType = betType;
        this.chipTier = chipTier;
        if (itemPrototype != null && !itemPrototype.isEmpty()) {
            ItemStack copy = itemPrototype.copy();
            copy.setCount(1);
            this.itemPrototype = copy;
        } else {
            this.itemPrototype = ItemStack.EMPTY;
        }
        this.betCount = betCount;
        this.unitCreditValue = unitCreditValue;
        this.totalCreditValue = Math.multiplyExact((long) betCount, unitCreditValue);
    }

    public static BetSnapshot fromChip(ChipTier tier, int betCount) {
        Objects.requireNonNull(tier, "ChipTier must not be null");
        return new BetSnapshot(BetType.CHIP, tier, ItemStack.EMPTY, betCount, tier.getBaseValue());
    }

    public static BetSnapshot fromItem(ItemStack stack, int betCount, long unitCreditValue) {
        Objects.requireNonNull(stack, "ItemStack must not be null");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("ItemStack must not be empty for item bet");
        }
        return new BetSnapshot(BetType.ITEM, null, stack, betCount, unitCreditValue);
    }

    public BetType getBetType() {
        return betType;
    }

    public boolean isItemBet() {
        return betType == BetType.ITEM;
    }

    public boolean isChipBet() {
        return betType == BetType.CHIP;
    }

    public ChipTier getChipTier() {
        return chipTier;
    }

    public ItemStack getItemPrototype() {
        return itemPrototype.copy();
    }

    public int getBetCount() {
        return betCount;
    }

    public long getUnitCreditValue() {
        return unitCreditValue;
    }

    public long getTotalCreditValue() {
        return totalCreditValue;
    }

    /**
     * Checks whether the given ItemStack matches this bet's item definition.
     * Uses strict ItemStack.isSameItemSameTags().
     */
    public boolean matches(ItemStack candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        if (isChipBet()) {
            return candidate.getItem() == chipTier.getItem();
        } else {
            return ItemStack.isSameItemSameTags(this.itemPrototype, candidate);
        }
    }

    public Component getDisplayName() {
        if (isChipBet()) {
            return Component.literal(chipTier.getColorCode() + chipTier.getId());
        } else {
            return itemPrototype.getHoverName();
        }
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("BetType", betType.name());
        tag.putInt("BetCount", betCount);
        tag.putLong("UnitCreditValue", unitCreditValue);
        if (isChipBet()) {
            tag.putString("ChipTier", chipTier.getId());
        } else {
            tag.put("ItemPrototype", itemPrototype.save(new CompoundTag()));
        }
        return tag;
    }

    public static BetSnapshot fromNbt(CompoundTag tag) {
        BetType type = tag.contains("BetType") ? BetType.valueOf(tag.getString("BetType")) : BetType.CHIP;
        int count = tag.getInt("BetCount");
        if (count <= 0) count = 1;

        if (type == BetType.ITEM && tag.contains("ItemPrototype")) {
            ItemStack proto = ItemStack.of(tag.getCompound("ItemPrototype"));
            long unitVal = tag.contains("UnitCreditValue") ? tag.getLong("UnitCreditValue") : 1L;
            if (unitVal <= 0) unitVal = 1L;
            return new BetSnapshot(BetType.ITEM, null, proto, count, unitVal);
        } else {
            String tierId = tag.contains("ChipTier") ? tag.getString("ChipTier") : (tag.contains("BetTier") ? tag.getString("BetTier") : "copper");
            ChipTier tier = ChipTier.fromId(tierId);
            return fromChip(tier, count);
        }
    }
}
