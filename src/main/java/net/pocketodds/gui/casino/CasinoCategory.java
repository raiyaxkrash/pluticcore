package net.pocketodds.gui.casino;

public enum CasinoCategory {
    SLOTS,
    ROULETTE,
    DICE,
    DECK_OF_FATE,
    JACKPOT_INFO,
    PRIZE_SHOP;

    public static CasinoCategory fromOrdinal(int ordinal) {
        CasinoCategory[] values = values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return SLOTS;
    }

    public String getTranslationKey() {
        return "pocketodds.gui.category." + name().toLowerCase(java.util.Locale.ROOT);
    }
}