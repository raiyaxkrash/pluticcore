package net.pocketodds.gambling.itembet;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.pocketodds.gambling.core.GameType;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class ItemBetRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicReference<Map<ResourceLocation, ItemBetConfigEntry>> REGISTRY_HOLDER =
            new AtomicReference<>(Collections.emptyMap());

    /**
     * Safely parses raw configuration strings and publishes an immutable snapshot.
     * Format: "itemId;unitCreditValue;minCount;maxCount;allowedGames;allowNbt;allowDamaged"
     * Example: "minecraft:iron_ingot;1;1;64;SLOT,DICE,ROULETTE,DECK;false;false"
     */
    public static void loadConfig(List<? extends String> rawEntries) {
        if (rawEntries == null || rawEntries.isEmpty()) {
            REGISTRY_HOLDER.set(Collections.emptyMap());
            return;
        }

        Map<ResourceLocation, ItemBetConfigEntry> newMap = new HashMap<>();

        for (String raw : rawEntries) {
            if (raw == null || raw.trim().isEmpty() || raw.trim().startsWith("#")) {
                continue;
            }
            try {
                String[] parts = raw.split(";");
                if (parts.length < 4) {
                    LOGGER.warn("Pocket Odds: Invalid item bet config entry format (must have at least 4 fields: itemId;unitCreditValue;minCount;maxCount): '{}'", raw);
                    continue;
                }

                String itemIdStr = parts[0].trim();
                ResourceLocation itemId = ResourceLocation.tryParse(itemIdStr);
                if (itemId == null) {
                    LOGGER.warn("Pocket Odds: Invalid ResourceLocation for item bet: '{}'", itemIdStr);
                    continue;
                }

                // Check item existence in Forge or BuiltIn registry
                boolean exists = (ForgeRegistries.ITEMS != null && ForgeRegistries.ITEMS.containsKey(itemId))
                        || BuiltInRegistries.ITEM.containsKey(itemId);
                if (!exists) {
                    LOGGER.warn("Pocket Odds: Unknown item '{}' in item bet config. Skipping.", itemId);
                    continue;
                }

                long unitCreditValue = Long.parseLong(parts[1].trim());
                if (unitCreditValue <= 0) {
                    LOGGER.warn("Pocket Odds: unitCreditValue must be positive in item bet config: '{}'", raw);
                    continue;
                }

                int minCount = Integer.parseInt(parts[2].trim());
                int maxCount = Integer.parseInt(parts[3].trim());
                if (minCount <= 0 || maxCount < minCount) {
                    LOGGER.warn("Pocket Odds: Invalid min/max count range [{}, {}] in entry: '{}'", minCount, maxCount, raw);
                    continue;
                }

                Set<GameType> allowedGames = EnumSet.allOf(GameType.class);
                if (parts.length > 4 && !parts[4].trim().isEmpty()) {
                    allowedGames = EnumSet.noneOf(GameType.class);
                    String[] games = parts[4].split(",");
                    for (String g : games) {
                        try {
                            allowedGames.add(GameType.valueOf(g.trim().toUpperCase(Locale.ROOT)));
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("Pocket Odds: Unknown GameType '{}' in entry: '{}'", g, raw);
                        }
                    }
                    if (allowedGames.isEmpty()) {
                        allowedGames = EnumSet.allOf(GameType.class);
                    }
                }

                boolean allowNbt = false;
                if (parts.length > 5) {
                    allowNbt = Boolean.parseBoolean(parts[5].trim());
                }

                boolean allowDamaged = false;
                if (parts.length > 6) {
                    allowDamaged = Boolean.parseBoolean(parts[6].trim());
                }

                ItemBetConfigEntry entry = new ItemBetConfigEntry(itemId, unitCreditValue, minCount, maxCount, allowedGames, allowNbt, allowDamaged);
                newMap.put(itemId, entry);
            } catch (Exception e) {
                LOGGER.warn("Pocket Odds: Failed to parse item bet config line '{}': {}", raw, e.getMessage());
            }
        }

        // Publish immutable snapshot atomically
        REGISTRY_HOLDER.set(Collections.unmodifiableMap(newMap));
        LOGGER.info("Pocket Odds: Loaded {} valid item bet configuration entries.", newMap.size());
    }

    public static ItemBetConfigEntry getEntry(ResourceLocation itemId) {
        if (itemId == null) return null;
        return REGISTRY_HOLDER.get().get(itemId);
    }

    public static ItemBetConfigEntry getEntry(Item item) {
        if (item == null) return null;
        ResourceLocation id = ForgeRegistries.ITEMS != null ? ForgeRegistries.ITEMS.getKey(item) : BuiltInRegistries.ITEM.getKey(item);
        return getEntry(id);
    }

    public static boolean isAllowed(ItemStack stack, GameType gameType) {
        if (stack == null || stack.isEmpty()) return false;
        ItemBetConfigEntry entry = getEntry(stack.getItem());
        if (entry == null) return false;
        return ItemBetValidator.validate(stack, stack.getCount(), entry, gameType) == ItemBetValidator.ValidationResult.VALID;
    }

    public static Map<ResourceLocation, ItemBetConfigEntry> getAllEntries() {
        return REGISTRY_HOLDER.get();
    }
}
