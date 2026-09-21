package net.pocketodds.gambling.core;

public enum GameType {
    SLOT("slot"),
    DICE("dice"),
    ROULETTE("roulette"),
    DECK("deck");

    private final String id;

    GameType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static GameType fromId(String id) {
        if (id == null) return SLOT;
        for (GameType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return SLOT;
    }
}
