package net.pocketodds.shop;

public enum ShopPaymentSource {
    INVENTORY,
    POUCH,
    INVENTORY_THEN_POUCH,
    POUCH_THEN_INVENTORY;

    public static ShopPaymentSource fromOrdinal(int ordinal) {
        ShopPaymentSource[] values = values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return INVENTORY;
    }
}
