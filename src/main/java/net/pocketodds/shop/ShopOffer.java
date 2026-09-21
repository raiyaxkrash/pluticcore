package net.pocketodds.shop;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

public class ShopOffer {
    private final String offerId;
    private final String itemId;
    private final int count;
    private final int price;
    private final ShopCategory category;
    private final int purchaseLimit;
    private final ShopLimitPeriod limitPeriod;
    private final String requiredAdvancement;
    private final boolean enabled;
    private final int sortOrder;
    private final String nameKey;
    private final String descriptionKey;

    public ShopOffer(String offerId, String itemId, int count, int price, ShopCategory category,
                     int purchaseLimit, ShopLimitPeriod limitPeriod, String requiredAdvancement,
                     boolean enabled, int sortOrder, String nameKey, String descriptionKey) {
        this.offerId = offerId != null ? offerId : "unknown";
        this.itemId = itemId != null ? itemId : "minecraft:air";
        this.count = Math.max(1, Math.min(count, 64));
        this.price = Math.max(1, price);
        this.category = category != null ? category : ShopCategory.RESOURCES;
        this.purchaseLimit = Math.max(0, purchaseLimit);
        this.limitPeriod = limitPeriod != null ? limitPeriod : ShopLimitPeriod.UNLIMITED;
        this.requiredAdvancement = requiredAdvancement != null ? requiredAdvancement : "";
        this.enabled = enabled;
        this.sortOrder = sortOrder;
        this.nameKey = nameKey != null ? nameKey : "";
        this.descriptionKey = descriptionKey != null ? descriptionKey : "";
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

    public int getPrice() {
        return price;
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

    public boolean isItemAvailable() {
        if (!enabled) return false;
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        if (loc == null) return false;
        Item item = ForgeRegistries.ITEMS.getValue(loc);
        return item != null && item != Items.AIR;
    }

    public ItemStack createRewardStack() {
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        if (loc == null) return ItemStack.EMPTY;
        Item item = ForgeRegistries.ITEMS.getValue(loc);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item, count);
    }

    public static ShopOffer fromJson(com.google.gson.JsonObject json) {
        if (json == null) return null;
        String offerId = json.has("offerId") ? json.get("offerId").getAsString() : "unknown";
        String itemId = json.has("itemId") ? json.get("itemId").getAsString() : "minecraft:air";
        int count = json.has("count") ? json.get("count").getAsInt() : 1;
        int price = json.has("price") ? json.get("price").getAsInt() : 1;
        String categoryStr = json.has("category") ? json.get("category").getAsString() : "RESOURCES";
        int purchaseLimit = json.has("purchaseLimit") ? json.get("purchaseLimit").getAsInt() : 0;
        String limitPeriodStr = json.has("limitPeriod") ? json.get("limitPeriod").getAsString() : "UNLIMITED";
        String requiredAdvancement = json.has("requiredAdvancement") ? json.get("requiredAdvancement").getAsString() : "";
        boolean enabled = !json.has("enabled") || json.get("enabled").getAsBoolean();
        int sortOrder = json.has("sortOrder") ? json.get("sortOrder").getAsInt() : 100;
        String nameKey = json.has("nameKey") ? json.get("nameKey").getAsString() : "shop.pocketodds." + offerId;
        String descriptionKey = json.has("descriptionKey") ? json.get("descriptionKey").getAsString() : nameKey + ".desc";

        ShopCategory category = ShopCategory.fromString(categoryStr);
        ShopLimitPeriod limitPeriod = ShopLimitPeriod.fromString(limitPeriodStr);

        return new ShopOffer(
                offerId, itemId, count, price, category, purchaseLimit, limitPeriod,
                requiredAdvancement, enabled, sortOrder, nameKey, descriptionKey
        );
    }
}
