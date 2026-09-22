package net.pocketodds.shop;

import java.util.Locale;

public enum ShopLimitPeriod {
    DAILY,
    WEEKLY,
    PER_PLAYER,
    PERMANENT,
    UNLIMITED;

    public static ShopLimitPeriod fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return UNLIMITED;
        }
        String clean = name.trim().toUpperCase(Locale.ROOT);
        if ("PERMANENT".equals(clean)) {
            return PER_PLAYER;
        }
        try {
            return ShopLimitPeriod.valueOf(clean);
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
