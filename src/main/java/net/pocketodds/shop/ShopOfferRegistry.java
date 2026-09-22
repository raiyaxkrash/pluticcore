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

    public void applyResources(Map<ResourceLocation, JsonElement> resources) {
        apply(resources, null, null);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, ShopOffer> newOffers = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try {
                if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                    PocketOdds.LOGGER.warn("Pocket Odds: Skipping non-object shop offer JSON {}", fileId);
                    continue;
                }
                JsonObject json = entry.getValue().getAsJsonObject();
                if (!json.has("offerId")) {
                    json.addProperty("offerId", fileId.getPath());
                }
                ShopOffer offer = ShopOffer.fromJson(json);
                if (offer != null) {
                    if (newOffers.containsKey(offer.getOfferId())) {
                        PocketOdds.LOGGER.warn("Pocket Odds: Duplicate shop offerId '{}' found in '{}'. Replacing previous entry.",
                                offer.getOfferId(), fileId);
                    }
                    newOffers.put(offer.getOfferId(), offer);
                }
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
                PocketOdds.LOGGER.warn("Pocket Odds: Shop offer '{}' refers to item '{}' or required mod which is not present in registry. Offer disabled.",
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
