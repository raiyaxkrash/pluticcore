package net.pocketodds.gui.casino;

public enum BetFundingSource {
    INVENTORY,
    POUCH_ONLY,
    POUCH_THEN_INVENTORY,
    INVENTORY_THEN_POUCH,
    ITEM_SLOT;

    public static BetFundingSource fromOrdinal(int ordinal) {
        BetFundingSource[] values = values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return INVENTORY;
    }
}