package net.pocketodds.gui.casino;

public enum RouletteBetType {
    RED,
    BLACK,
    ZERO;

    public static RouletteBetType fromOrdinal(int ordinal) {
        RouletteBetType[] values = values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return RED;
    }
}