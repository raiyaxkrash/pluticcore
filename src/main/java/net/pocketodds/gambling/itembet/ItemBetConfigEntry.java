package net.pocketodds.gambling.itembet;

import net.minecraft.resources.ResourceLocation;
import net.pocketodds.gambling.core.GameType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public class ItemBetConfigEntry {
    private final ResourceLocation itemId;
    private final long unitCreditValue;
    private final int minCount;
    private final int maxCount;
    private final Set<GameType> allowedGames;
    private final boolean allowNbt;
    private final boolean allowDamaged;

    public ItemBetConfigEntry(ResourceLocation itemId, long unitCreditValue, int minCount, int maxCount,
                              Set<GameType> allowedGames, boolean allowNbt, boolean allowDamaged) {
        this.itemId = Objects.requireNonNull(itemId, "itemId must not be null");
        if (unitCreditValue <= 0) {
            throw new IllegalArgumentException("unitCreditValue must be positive: " + unitCreditValue);
        }
        if (minCount <= 0 || maxCount < minCount) {
            throw new IllegalArgumentException("Invalid count range: [" + minCount + ", " + maxCount + "]");
        }
        this.unitCreditValue = unitCreditValue;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.allowedGames = (allowedGames != null && !allowedGames.isEmpty())
                ? Collections.unmodifiableSet(EnumSet.copyOf(allowedGames))
                : Collections.unmodifiableSet(EnumSet.allOf(GameType.class));
        this.allowNbt = allowNbt;
        this.allowDamaged = allowDamaged;
    }

    public ResourceLocation getItemId() {
        return itemId;
    }

    public long getUnitCreditValue() {
        return unitCreditValue;
    }

    public int getMinCount() {
        return minCount;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public Set<GameType> getAllowedGames() {
        return allowedGames;
    }

    public boolean isGameAllowed(GameType game) {
        return allowedGames.contains(game);
    }

    public boolean isAllowNbt() {
        return allowNbt;
    }

    public boolean isAllowDamaged() {
        return allowDamaged;
    }
}
