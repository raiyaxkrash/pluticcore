package net.pocketodds.gambling.itembet;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.pocketodds.gambling.core.GameType;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class ItemRewardRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final AtomicReference<Map<GameType, ItemRewardTable>> REGISTRY_HOLDER =
            new AtomicReference<>(Collections.emptyMap());

    public static void loadConfig(
            List<? extends String> slotRaw,
            List<? extends String> diceRaw,
            List<? extends String> rouletteRaw,
            List<? extends String> deckRaw
    ) {
        Map<GameType, ItemRewardTable> newMap = new EnumMap<>(GameType.class);
        newMap.put(GameType.SLOT, parseTable(GameType.SLOT, slotRaw));
        newMap.put(GameType.DICE, parseTable(GameType.DICE, diceRaw));
        newMap.put(GameType.ROULETTE, parseTable(GameType.ROULETTE, rouletteRaw));
        newMap.put(GameType.DECK, parseTable(GameType.DECK, deckRaw));

        REGISTRY_HOLDER.set(Collections.unmodifiableMap(newMap));
        LOGGER.info("Pocket Odds: Loaded item reward tables for all game types.");
    }

    private static ItemRewardTable parseTable(GameType gameType, List<? extends String> rawEntries) {
        if (rawEntries == null || rawEntries.isEmpty()) {
            return new ItemRewardTable(Collections.emptyList());
        }

        List<ItemRewardEntry> entries = new ArrayList<>();
        for (String raw : rawEntries) {
            if (raw == null || raw.trim().isEmpty() || raw.trim().startsWith("#")) {
                continue;
            }
            try {
                // Supported formats:
                // 6-field: "itemId;weight;maxCap;creditValue;minBetCredits;maxBetCredits"
                // 8-field (legacy): "itemId;weight;minCount;maxCount;maxCap;creditValue;minBetCredits;maxBetCredits"
                String[] parts = raw.split(";");
                if (parts.length < 6) {
                    LOGGER.warn("Pocket Odds: Invalid reward table entry format for {}: '{}' (requires at least 6 fields: itemId;weight;maxCap;creditValue;minBetCredits;maxBetCredits)", gameType, raw);
                    continue;
                }

                String itemIdStr = parts[0].trim();
                ResourceLocation itemId = ResourceLocation.tryParse(itemIdStr);
                if (itemId == null) {
                    LOGGER.warn("Pocket Odds: Invalid ResourceLocation for reward item: '{}'", itemIdStr);
                    continue;
                }

                boolean exists = (ForgeRegistries.ITEMS != null && ForgeRegistries.ITEMS.containsKey(itemId))
                        || BuiltInRegistries.ITEM.containsKey(itemId);
                if (!exists) {
                    LOGGER.warn("Pocket Odds: Unknown reward item '{}' in table for {}. Skipping.", itemId, gameType);
                    continue;
                }

                int weight;
                int maxCap;
                long creditValue;
                long minBetCredits;
                long maxBetCredits;

                if (parts.length >= 8) {
                    // Legacy 8-field format
                    weight = Integer.parseInt(parts[1].trim());
                    maxCap = Integer.parseInt(parts[4].trim());
                    creditValue = Long.parseLong(parts[5].trim());
                    minBetCredits = Long.parseLong(parts[6].trim());
                    maxBetCredits = Long.parseLong(parts[7].trim());
                } else {
                    // New clean 6-field format
                    weight = Integer.parseInt(parts[1].trim());
                    maxCap = Integer.parseInt(parts[2].trim());
                    creditValue = Long.parseLong(parts[3].trim());
                    minBetCredits = Long.parseLong(parts[4].trim());
                    maxBetCredits = Long.parseLong(parts[5].trim());
                }

                ItemRewardEntry entry = new ItemRewardEntry(itemId, weight, maxCap, creditValue, minBetCredits, maxBetCredits);
                entries.add(entry);
            } catch (Exception e) {
                LOGGER.warn("Pocket Odds: Failed to parse reward table line for {} '{}': {}", gameType, raw, e.getMessage());
            }
        }
        return new ItemRewardTable(entries);
    }

    public static ItemRewardTable getTable(GameType gameType) {
        Map<GameType, ItemRewardTable> map = REGISTRY_HOLDER.get();
        return map.getOrDefault(gameType, new ItemRewardTable(Collections.emptyList()));
    }
}
