package net.pocketodds.shop;

import java.util.Locale;

public enum ShopLimitPeriod {
    DAILY,
    WEEKLY,
    PERMANENT,
    UNLIMITED;

    public static ShopLimitPeriod fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return UNLIMITED;
        }
        try {
            return ShopLimitPeriod.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNLIMITED;
        }
    }

    public static ShopLimitPeriod fromOrdinal(int ordinal) {
        ShopLimitPeriod[] values = values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return UNLIMITED;
    }

    public String getTranslationKey() {
        return "shop.pocketodds.limit." + name().toLowerCase(Locale.ROOT);
    }
}
