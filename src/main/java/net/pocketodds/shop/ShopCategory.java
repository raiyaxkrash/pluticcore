package net.pocketodds.shop;

import java.util.Locale;

public enum ShopCategory {
    RESOURCES,
    MACHINES,
    COMPONENTS,
    STORAGE,
    TOOLS,
    TRAVEL,
    BUILDING,
    RARE,
    CONSUMABLES;

    public static ShopCategory fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return RESOURCES;
        }
        try {
            return ShopCategory.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RESOURCES;
        }
    }

    public static ShopCategory fromOrdinal(int ordinal) {
        ShopCategory[] values = values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return RESOURCES;
    }

    public String getTranslationKey() {
        return "shop.pocketodds.category." + name().toLowerCase(Locale.ROOT);
    }
}
