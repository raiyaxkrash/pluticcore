package net.pocketodds.gambling.itembet;

public enum PayoutMode {
    SAME_ITEM("same_item"),
    REWARD_TABLE("reward_table"),
    BOTH("both");

    private final String id;

    PayoutMode(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static PayoutMode fromId(String id) {
        if (id == null) return SAME_ITEM;
        for (PayoutMode mode : values()) {
            if (mode.id.equalsIgnoreCase(id) || mode.name().equalsIgnoreCase(id)) {
                return mode;
            }
        }
        return SAME_ITEM;
    }
}
