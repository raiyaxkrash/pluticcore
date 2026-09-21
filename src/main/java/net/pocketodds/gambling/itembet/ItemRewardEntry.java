package net.pocketodds.gambling.itembet;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public class ItemRewardEntry {
    private final ResourceLocation itemId;
    private final int weight;
    private final int minCount;
    private final int maxCount;
    private final int maxCap;
    private final long creditValue;
    private final long minBetCredits;
    private final long maxBetCredits;

    public ItemRewardEntry(ResourceLocation itemId, int weight, int minCount, int maxCount, int maxCap,
                           long creditValue, long minBetCredits, long maxBetCredits) {
        this.itemId = Objects.requireNonNull(itemId, "itemId must not be null");
        if (weight <= 0) throw new IllegalArgumentException("weight must be positive: " + weight);
        if (minCount <= 0 || maxCount < minCount) throw new IllegalArgumentException("Invalid count range: [" + minCount + ", " + maxCount + "]");
        if (maxCap <= 0) throw new IllegalArgumentException("maxCap must be positive: " + maxCap);
        if (creditValue <= 0) throw new IllegalArgumentException("creditValue must be positive: " + creditValue);
        if (minBetCredits < 0 || maxBetCredits < minBetCredits) throw new IllegalArgumentException("Invalid bet credit range: [" + minBetCredits + ", " + maxBetCredits + "]");

        this.weight = weight;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.maxCap = maxCap;
        this.creditValue = creditValue;
        this.minBetCredits = minBetCredits;
        this.maxBetCredits = maxBetCredits;
    }

    public ResourceLocation getItemId() {
        return itemId;
    }

    public int getWeight() {
        return weight;
    }

    public int getMinCount() {
        return minCount;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public int getMaxCap() {
        return maxCap;
    }

    public long getCreditValue() {
        return creditValue;
    }

    public long getMinBetCredits() {
        return minBetCredits;
    }

    public long getMaxBetCredits() {
        return maxBetCredits;
    }

    public boolean matchesBetCredits(long betCredits) {
        return betCredits >= minBetCredits && betCredits <= maxBetCredits;
    }
}
