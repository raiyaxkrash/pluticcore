package net.pocketodds.gambling.slot;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;
import net.pocketodds.config.PocketOddsConfig;

public enum SlotSymbol {
    CHERRY("🍒", ChatFormatting.RED, "cherry"),
    IRON("⚪", ChatFormatting.WHITE, "iron"),
    GOLD("🟡", ChatFormatting.GOLD, "gold"),
    DIAMOND("💎", ChatFormatting.AQUA, "diamond"),
    EMERALD("🟢", ChatFormatting.GREEN, "emerald"),
    STAR("⭐", ChatFormatting.YELLOW, "star"),
    SKULL("💀", ChatFormatting.DARK_GRAY, "skull"),
    JOKER("🃏", ChatFormatting.LIGHT_PURPLE, "joker");

    private final String icon;
    private final ChatFormatting color;
    private final String keyName;

    SlotSymbol(String icon, ChatFormatting color, String keyName) {
        this.icon = icon;
        this.color = color;
        this.keyName = keyName;
    }

    public String getIcon() {
        return icon;
    }

    public ChatFormatting getColor() {
        return color;
    }

    public MutableComponent getFormattedDisplay() {
        return Component.literal(icon + " ").withStyle(color)
                .append(Component.translatable("pocketodds.symbol." + keyName).withStyle(color));
    }

    public MutableComponent getShortDisplay() {
        return Component.literal(icon).withStyle(color);
    }

    public int getWeight(PocketOddsConfig.Server config) {
        if (config == null) {
            return switch (this) {
                case CHERRY -> 40;
                case IRON -> 28;
                case GOLD -> 16;
                case DIAMOND -> 8;
                case EMERALD -> 4;
                case STAR -> 2;
                case SKULL -> 5;
                case JOKER -> 1;
            };
        }
        return switch (this) {
            case CHERRY -> config.weightCherry.get();
            case IRON -> config.weightIron.get();
            case GOLD -> config.weightGold.get();
            case DIAMOND -> config.weightDiamond.get();
            case EMERALD -> config.weightEmerald.get();
            case STAR -> config.weightStar.get();
            case SKULL -> config.weightSkull.get();
            case JOKER -> config.weightJoker.get();
        };
    }

    public static SlotSymbol getRandomSymbol(RandomSource random, PocketOddsConfig.Server config) {
        int totalWeight = 0;
        for (SlotSymbol sym : values()) {
            totalWeight += sym.getWeight(config);
        }

        if (totalWeight <= 0) {
            return CHERRY;
        }

        int roll = random.nextInt(totalWeight);
        int current = 0;
        for (SlotSymbol sym : values()) {
            current += sym.getWeight(config);
            if (roll < current) {
                return sym;
            }
        }
        return CHERRY;
    }
}
