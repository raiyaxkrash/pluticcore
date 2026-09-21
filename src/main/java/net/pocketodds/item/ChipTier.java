package net.pocketodds.item;

import net.minecraft.world.item.Item;
import net.pocketodds.registration.ModItems;

import java.util.function.Supplier;

public enum ChipTier {
    COPPER("copper", 1, "§6", () -> ModItems.COPPER_CHIP.get()),
    GOLD("gold", 8, "§e", () -> ModItems.GOLD_CHIP.get()),
    DIAMOND("diamond", 64, "§b", () -> ModItems.DIAMOND_CHIP.get()),
    NETHERITE("netherite", 512, "§5", () -> ModItems.NETHERITE_CHIP.get());

    private final String id;
    private final int baseValue;
    private final String colorCode;
    private final Supplier<Item> itemSupplier;

    ChipTier(String id, int baseValue, String colorCode, Supplier<Item> itemSupplier) {
        this.id = id;
        this.baseValue = baseValue;
        this.colorCode = colorCode;
        this.itemSupplier = itemSupplier;
    }

    public String getId() {
        return id;
    }

    public int getBaseValue() {
        return baseValue;
    }

    public String getColorCode() {
        return colorCode;
    }

    public Item getItem() {
        return itemSupplier.get();
    }

    public ChipTier next() {
        int nextOrdinal = (this.ordinal() + 1) % values().length;
        return values()[nextOrdinal];
    }

    public static ChipTier fromId(String id) {
        for (ChipTier tier : values()) {
            if (tier.id.equalsIgnoreCase(id)) {
                return tier;
            }
        }
        return COPPER;
    }

    public static ChipTier fromItem(Item item) {
        for (ChipTier tier : values()) {
            if (tier.getItem() == item) {
                return tier;
            }
        }
        return null;
    }
}
