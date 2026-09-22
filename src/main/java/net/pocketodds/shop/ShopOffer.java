package net.pocketodds.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import net.pocketodds.PocketOdds;
import net.pocketodds.item.ChipTier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

public class ShopOffer {
    public static final long MAX_PRICE_CREDITS = 1_000_000_000L;

    private static BiFunction<String, Boolean, Boolean> modLoadedPredicate = null;

    private final String offerId;
    private final String itemId;
    private final int count;
    private final long priceCredits;
    private final ShopCategory category;
    private final int purchaseLimit;
    private final ShopLimitPeriod limitPeriod;
    private final String requiredAdvancement;
    private final boolean enabled;
    private final int sortOrder;
    private final String nameKey;
    private final String descriptionKey;
    private final CompoundTag itemNbt;
    private final List<String> requiredMods;
    private final List<String> requiredItems;
    private final String minStage;
    private final String maxStage;

    public ShopOffer(String offerId, String itemId, int count, long priceCredits, ShopCategory category,
                     int purchaseLimit, ShopLimitPeriod limitPeriod, String requiredAdvancement,
                     boolean enabled, int sortOrder, String nameKey, String descriptionKey,
                     CompoundTag itemNbt, List<String> requiredMods, List<String> requiredItems,
                     String minStage, String maxStage) {
        this.offerId = offerId != null ? offerId : "unknown";
        this.itemId = itemId != null ? itemId : "minecraft:air";
        this.count = Math.max(1, Math.min(count, 64));
        if (priceCredits <= 0L || priceCredits > MAX_PRICE_CREDITS) {
            throw new IllegalArgumentException("Invalid priceCredits " + priceCredits + " for offer '" + offerId + "'. Must be between 1 and " + MAX_PRICE_CREDITS);
        }
        this.priceCredits = priceCredits;
        this.category = category != null ? category : ShopCategory.RESOURCES;
        this.purchaseLimit = Math.max(0, purchaseLimit);
        this.limitPeriod = limitPeriod != null ? limitPeriod : ShopLimitPeriod.UNLIMITED;
        this.requiredAdvancement = requiredAdvancement != null ? requiredAdvancement : "";
        this.enabled = enabled;
        this.sortOrder = sortOrder;
        this.nameKey = nameKey != null ? nameKey : "";
        this.descriptionKey = descriptionKey != null ? descriptionKey : "";
        this.itemNbt = itemNbt != null ? itemNbt.copy() : null;
        this.requiredMods = requiredMods != null ? Collections.unmodifiableList(new ArrayList<>(requiredMods)) : Collections.emptyList();
        this.requiredItems = requiredItems != null ? Collections.unmodifiableList(new ArrayList<>(requiredItems)) : Collections.emptyList();
        this.minStage = minStage != null ? minStage : "";
        this.maxStage = maxStage != null ? maxStage : "";
    }

    public ShopOffer(String offerId, String itemId, int count, long priceCredits, ShopCategory category,
                     int purchaseLimit, ShopLimitPeriod limitPeriod, String requiredAdvancement,
                     boolean enabled, int sortOrder, String nameKey, String descriptionKey) {
        this(offerId, itemId, count, priceCredits, category, purchaseLimit, limitPeriod,
                requiredAdvancement, enabled, sortOrder, nameKey, descriptionKey, null, null, null, "", "");
    }

    public ShopOffer(String offerId, String itemId, int count, int price, ShopCategory category,
                     int purchaseLimit, ShopLimitPeriod limitPeriod, String requiredAdvancement,
                     boolean enabled, int sortOrder, String nameKey, String descriptionKey) {
        this(offerId, itemId, count, (long) price, category, purchaseLimit, limitPeriod,
                requiredAdvancement, enabled, sortOrder, nameKey, descriptionKey, null, null, null, "", "");
    }

    public static void setModLoadedPredicate(BiFunction<String, Boolean, Boolean> predicate) {
        modLoadedPredicate = predicate;
    }

    public static boolean isModLoaded(String modId) {
        if (modLoadedPredicate != null) {
            Boolean res = modLoadedPredicate.apply(modId, false);
            if (res != null) return res;
        }
        try {
            if (net.minecraftforge.fml.ModList.get() != null) {
                return net.minecraftforge.fml.ModList.get().isLoaded(modId);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public String getOfferId() {
        return offerId;
    }

    public String getItemId() {
        return itemId;
    }

    public int getCount() {
        return count;
    }

    public long getPriceCredits() {
        return priceCredits;
    }

    public int getPrice() {
        return (int) Math.min(Integer.MAX_VALUE, priceCredits);
    }

    public ShopCategory getCategory() {
        return category;
    }

    public int getPurchaseLimit() {
        return purchaseLimit;
    }

    public ShopLimitPeriod getLimitPeriod() {
        return limitPeriod;
    }

    public String getRequiredAdvancement() {
        return requiredAdvancement;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getDescriptionKey() {
        return descriptionKey;
    }

    public CompoundTag getItemNbt() {
        return itemNbt != null ? itemNbt.copy() : null;
    }

    public List<String> getRequiredMods() {
        return requiredMods;
    }

    public List<String> getRequiredItems() {
        return requiredItems;
    }

    public String getMinStage() {
        return minStage;
    }

    public String getMaxStage() {
        return maxStage;
    }

    public boolean isItemAvailable() {
        if (!enabled) return false;

        // 1. Check required mods
        if (!requiredMods.isEmpty()) {
            for (String modId : requiredMods) {
                if (!isModLoaded(modId)) {
                    return false;
                }
            }
        }

        // 2. Check required items
        if (!requiredItems.isEmpty()) {
            for (String reqItemId : requiredItems) {
                ResourceLocation reqLoc = ResourceLocation.tryParse(reqItemId);
                if (reqLoc == null || !ForgeRegistries.ITEMS.containsKey(reqLoc)) {
                    return false;
                }
            }
        }

        // 3. Check item itself
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        if (loc == null || !ForgeRegistries.ITEMS.containsKey(loc)) {
            return false;
        }
        Item item = ForgeRegistries.ITEMS.getValue(loc);
        return item != null && item != Items.AIR;
    }

    public ItemStack createRewardStack() {
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        if (loc == null) return ItemStack.EMPTY;
        Item item = ForgeRegistries.ITEMS.getValue(loc);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item, count);
        if (itemNbt != null && !itemNbt.isEmpty()) {
            stack.setTag(itemNbt.copy());
        }
        return stack;
    }

    public static String formatChipBreakdown(long credits) {
        if (credits <= 0) return "0 кр.";
        StringBuilder sb = new StringBuilder();
        long rem = credits;

        long netherite = rem / ChipTier.NETHERITE.getBaseValue();
        rem %= ChipTier.NETHERITE.getBaseValue();

        long diamond = rem / ChipTier.DIAMOND.getBaseValue();
        rem %= ChipTier.DIAMOND.getBaseValue();

        long gold = rem / ChipTier.GOLD.getBaseValue();
        rem %= ChipTier.GOLD.getBaseValue();

        long copper = rem;

        boolean first = true;
        if (netherite > 0) {
            sb.append("§5").append(netherite).append("x Netherite§r");
            first = false;
        }
        if (diamond > 0) {
            if (!first) sb.append(", ");
            sb.append("§b").append(diamond).append("x Diamond§r");
            first = false;
        }
        if (gold > 0) {
            if (!first) sb.append(", ");
            sb.append("§e").append(gold).append("x Gold§r");
            first = false;
        }
        if (copper > 0) {
            if (!first) sb.append(", ");
            sb.append("§6").append(copper).append("x Copper§r");
        }
        return sb.toString();
    }

    public static ShopOffer fromJson(JsonObject json) {
        if (json == null) return null;
        String offerId = json.has("offerId") ? json.get("offerId").getAsString() : "unknown";

        String itemId = "minecraft:air";
        if (json.has("itemId")) {
            itemId = json.get("itemId").getAsString();
        } else if (json.has("item")) {
            itemId = json.get("item").getAsString();
        }

        int count = json.has("count") ? json.get("count").getAsInt() : 1;
        if (!json.has("priceCredits") && !json.has("price")) {
            PocketOdds.LOGGER.error("Pocket Odds: Missing price in shop offer '{}'. Offer rejected.", offerId);
            return null;
        }

        long priceCredits = json.has("priceCredits") ? json.get("priceCredits").getAsLong() : json.get("price").getAsLong();
        if (priceCredits <= 0L || priceCredits > MAX_PRICE_CREDITS) {
            PocketOdds.LOGGER.error("Pocket Odds: Invalid priceCredits {} for shop offer '{}'. Price must be between 1 and {}. Offer rejected.",
                    priceCredits, offerId, MAX_PRICE_CREDITS);
            return null;
        }

        String categoryStr = json.has("category") ? json.get("category").getAsString() : "RESOURCES";
        int purchaseLimit = json.has("purchaseLimit") ? json.get("purchaseLimit").getAsInt() : 0;
        String limitPeriodStr = json.has("limitPeriod") ? json.get("limitPeriod").getAsString() : "UNLIMITED";
        String requiredAdvancement = json.has("requiredAdvancement") ? json.get("requiredAdvancement").getAsString() : "";
        boolean enabled = !json.has("enabled") || json.get("enabled").getAsBoolean();
        int sortOrder = json.has("sortOrder") ? json.get("sortOrder").getAsInt() : 100;
        String nameKey = json.has("nameKey") ? json.get("nameKey").getAsString() : "shop.pocketodds." + offerId;
        String descriptionKey = json.has("descriptionKey") ? json.get("descriptionKey").getAsString() : nameKey + ".desc";

        CompoundTag itemNbt = null;
        if (json.has("itemNbt")) {
            itemNbt = parseNbt(json.get("itemNbt"));
        } else if (json.has("nbt")) {
            itemNbt = parseNbt(json.get("nbt"));
        }

        List<String> requiredMods = new ArrayList<>();
        if (json.has("requiredMods") && json.get("requiredMods").isJsonArray()) {
            for (JsonElement elem : json.getAsJsonArray("requiredMods")) {
                if (elem.isJsonPrimitive()) {
                    requiredMods.add(elem.getAsString());
                }
            }
        }

        List<String> requiredItems = new ArrayList<>();
        if (json.has("requiredItems") && json.get("requiredItems").isJsonArray()) {
            for (JsonElement elem : json.getAsJsonArray("requiredItems")) {
                if (elem.isJsonPrimitive()) {
                    requiredItems.add(elem.getAsString());
                }
            }
        }

        String minStage = json.has("minStage") ? json.get("minStage").getAsString() : "";
        String maxStage = json.has("maxStage") ? json.get("maxStage").getAsString() : "";

        ShopCategory category = ShopCategory.fromString(categoryStr);
        ShopLimitPeriod limitPeriod = ShopLimitPeriod.fromString(limitPeriodStr);

        return new ShopOffer(
                offerId, itemId, count, priceCredits, category, purchaseLimit, limitPeriod,
                requiredAdvancement, enabled, sortOrder, nameKey, descriptionKey,
                itemNbt, requiredMods, requiredItems, minStage, maxStage
        );
    }

    private static CompoundTag parseNbt(JsonElement elem) {
        if (elem == null || elem.isJsonNull()) return null;
        try {
            if (elem.isJsonPrimitive()) {
                String snbt = elem.getAsString().trim();
                if (snbt.isEmpty()) return null;
                return TagParser.parseTag(snbt);
            } else if (elem.isJsonObject()) {
                return TagParser.parseTag(elem.toString());
            }
        } catch (Exception e) {
            PocketOdds.LOGGER.error("Pocket Odds: Failed to parse item NBT from JSON: {}", e.getMessage());
        }
        return null;
    }
}
