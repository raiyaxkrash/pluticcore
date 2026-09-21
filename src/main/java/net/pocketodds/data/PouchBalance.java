package net.pocketodds.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.pocketodds.item.ChipTier;

public class PouchBalance {
    /**
     * Theoretical maximum chips per tier to guarantee that even if ALL 4 tiers are full,
     * getTotalCredits() never exceeds Long.MAX_VALUE.
     * Sum of all 4 tier base values: 512 + 64 + 8 + 1 = 585.
     * Long.MAX_VALUE / 585L ≈ 15,766,447,926,247,479 (15.7 quadrillion).
     */
    public static final long MAX_CHIPS_PER_TIER = Long.MAX_VALUE / 585L;

    private final long maxChipLimit;
    private long copper;
    private long gold;
    private long diamond;
    private long netherite;

    public PouchBalance() {
        this(0L, 0L, 0L, 0L, MAX_CHIPS_PER_TIER);
    }

    public PouchBalance(long copper, long gold, long diamond, long netherite) {
        this(copper, gold, diamond, netherite, MAX_CHIPS_PER_TIER);
    }

    public PouchBalance(long copper, long gold, long diamond, long netherite, long maxChipLimit) {
        this.maxChipLimit = maxChipLimit > 0 ? maxChipLimit : MAX_CHIPS_PER_TIER;
        this.copper = Math.max(0L, Math.min(this.maxChipLimit, copper));
        this.gold = Math.max(0L, Math.min(this.maxChipLimit, gold));
        this.diamond = Math.max(0L, Math.min(this.maxChipLimit, diamond));
        this.netherite = Math.max(0L, Math.min(this.maxChipLimit, netherite));
    }

    public long getMaxChipLimit() {
        return maxChipLimit;
    }

    public synchronized long getCount(ChipTier tier) {
        if (tier == null) return 0L;
        return switch (tier) {
            case COPPER -> copper;
            case GOLD -> gold;
            case DIAMOND -> diamond;
            case NETHERITE -> netherite;
        };
    }

    public synchronized void setCount(ChipTier tier, long count) {
        if (tier == null) return;
        long val = Math.max(0L, Math.min(maxChipLimit, count));
        switch (tier) {
            case COPPER -> copper = val;
            case GOLD -> gold = val;
            case DIAMOND -> diamond = val;
            case NETHERITE -> netherite = val;
        }
    }

    /**
     * Checks whether the specified delta can be added to the tier without exceeding maxChipLimit.
     */
    public synchronized boolean canAdd(ChipTier tier, long delta) {
        if (tier == null || delta < 0) return false;
        if (delta == 0) return true;
        long current = getCount(tier);
        return (maxChipLimit - current) >= delta;
    }

    /**
     * Atomically adds delta to the tier count.
     * Returns true if successful. If adding delta would cause overflow beyond maxChipLimit,
     * or if deducting delta exceeds current balance, returns false and leaves the balance unchanged.
     */
    public synchronized boolean addCount(ChipTier tier, long delta) {
        if (tier == null || delta == 0) return true;
        long current = getCount(tier);
        if (delta > 0) {
            if (maxChipLimit - current < delta) {
                return false; // Prevent overflow and zeroing
            }
            setCount(tier, current + delta);
            return true;
        } else {
            if (current < -delta) {
                return false; // Underflow
            }
            setCount(tier, current + delta);
            return true;
        }
    }

    public synchronized long getTotalCredits() {
        long total = 0L;
        for (ChipTier tier : ChipTier.values()) {
            long count = getCount(tier);
            long val = tier.getBaseValue();
            if (count > 0) {
                long product = count * val;
                if (Long.MAX_VALUE - total < product) {
                    return Long.MAX_VALUE;
                }
                total += product;
            }
        }
        return total;
    }

    public synchronized CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("CopperChips", copper);
        tag.putLong("GoldChips", gold);
        tag.putLong("DiamondChips", diamond);
        tag.putLong("NetheriteChips", netherite);
        if (maxChipLimit != MAX_CHIPS_PER_TIER) {
            tag.putLong("MaxChipLimit", maxChipLimit);
        }
        return tag;
    }

    public static PouchBalance fromNbt(CompoundTag tag) {
        if (tag == null) return new PouchBalance();
        long maxLimit = tag.contains("MaxChipLimit", Tag.TAG_ANY_NUMERIC)
                ? tag.getLong("MaxChipLimit")
                : MAX_CHIPS_PER_TIER;
        return new PouchBalance(
                tag.contains("CopperChips", Tag.TAG_ANY_NUMERIC) ? tag.getLong("CopperChips") : 0L,
                tag.contains("GoldChips", Tag.TAG_ANY_NUMERIC) ? tag.getLong("GoldChips") : 0L,
                tag.contains("DiamondChips", Tag.TAG_ANY_NUMERIC) ? tag.getLong("DiamondChips") : 0L,
                tag.contains("NetheriteChips", Tag.TAG_ANY_NUMERIC) ? tag.getLong("NetheriteChips") : 0L,
                maxLimit
        );
    }

    public synchronized PouchBalance copy() {
        return new PouchBalance(copper, gold, diamond, netherite, maxChipLimit);
    }
}
