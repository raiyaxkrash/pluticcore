package net.pocketodds.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.pocketodds.PocketOdds;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ShopOfferRegistry extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, ShopOffer> OFFERS = new LinkedHashMap<>();
    private static final Set<String> WARNED_MISSING_ITEMS = ConcurrentHashMap.newKeySet();

    public static final ShopOfferRegistry INSTANCE = new ShopOfferRegistry();

    public ShopOfferRegistry() {
        super(GSON, "shop_offers");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, ShopOffer> newOffers = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject json = entry.getValue().getAsJsonObject();

                String offerId = json.has("offerId") ? json.get("offerId").getAsString() : fileId.getPath();
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

                ShopOffer offer = new ShopOffer(
                        offerId, itemId, count, price, category, purchaseLimit, limitPeriod,
                        requiredAdvancement, enabled, sortOrder, nameKey, descriptionKey
                );

                newOffers.put(offerId, offer);
            } catch (Exception e) {
                PocketOdds.LOGGER.error("Pocket Odds: Failed to parse shop offer JSON {}: {}", fileId, e.getMessage());
            }
        }

        synchronized (OFFERS) {
            OFFERS.clear();
            OFFERS.putAll(newOffers);
        }

        // Validate items and log once
        for (ShopOffer offer : newOffers.values()) {
            if (!offer.isItemAvailable() && WARNED_MISSING_ITEMS.add(offer.getItemId())) {
                PocketOdds.LOGGER.warn("Pocket Odds: Shop offer '{}' refers to item '{}' which is not present in registry. Offer disabled.",
                        offer.getOfferId(), offer.getItemId());
            }
        }

        PocketOdds.LOGGER.info("Pocket Odds: Loaded {} shop offers from datapack.", newOffers.size());
    }

    public static ShopOffer getOffer(String offerId) {
        if (offerId == null) return null;
        synchronized (OFFERS) {
            return OFFERS.get(offerId);
        }
    }

    public static List<ShopOffer> getAllOffers() {
        synchronized (OFFERS) {
            return new ArrayList<>(OFFERS.values());
        }
    }

    public static List<ShopOffer> getAvailableOffers() {
        List<ShopOffer> result = new ArrayList<>();
        synchronized (OFFERS) {
            for (ShopOffer offer : OFFERS.values()) {
                if (offer.isItemAvailable()) {
                    result.add(offer);
                }
            }
        }
        result.sort(Comparator.comparingInt(ShopOffer::getSortOrder));
        return result;
    }

    public static void registerManualOffer(ShopOffer offer) {
        if (offer == null) return;
        synchronized (OFFERS) {
            OFFERS.put(offer.getOfferId(), offer);
        }
    }

    public static void registerOffer(ShopOffer offer) {
        registerManualOffer(offer);
    }

    public static void clear() {
        synchronized (OFFERS) {
            OFFERS.clear();
            WARNED_MISSING_ITEMS.clear();
        }
    }
}
