package net.pocketodds.gui.casino;

public enum PayoutDestination {
    INVENTORY,
    POUCH;

    public static PayoutDestination fromOrdinal(int ordinal) {
        PayoutDestination[] values = values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return INVENTORY;
    }
}