package net.pocketodds.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.pocketodds.PocketOdds;
import net.pocketodds.shop.ShopCategory;
import net.pocketodds.shop.ShopOffer;
import net.pocketodds.shop.ShopOfferRegistry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ShopCatalogGeneratorCommand {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("pocketodds")
                        .then(Commands.literal("generate_shop")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> generate(ctx.getSource(), null))
                                .then(Commands.argument("modid", StringArgumentType.word())
                                        .executes(ctx -> generate(ctx.getSource(), StringArgumentType.getString(ctx, "modid")))
                                )
                        )
        );
    }

    public static int generate(CommandSourceStack source, String targetModId) {
        Path outputDir;
        try {
            outputDir = FMLPaths.CONFIGDIR.get().resolve("pocketodds/generated_offers");
        } catch (Throwable t) {
            outputDir = Paths.get("config/pocketodds/generated_offers");
        }

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            source.sendFailure(Component.literal("Не удалось создать директорию: " + outputDir));
            return 0;
        }

        int totalChecked = 0;
        int generatedCount = 0;
        int skippedExisting = 0;
        Map<String, Integer> filteredReasons = new LinkedHashMap<>();
        List<String> generatedDetails = new ArrayList<>();

        net.minecraft.world.item.crafting.RecipeManager recipeManager = null;
        try {
            if (source != null && source.getServer() != null) {
                recipeManager = source.getServer().getRecipeManager();
            }
        } catch (Throwable ignored) {
        }

        for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
            ResourceLocation id = entry.getKey().location();
            Item item = entry.getValue();
            if (id == null || item == null) continue;

            String namespace = id.getNamespace();
            if (targetModId != null && !targetModId.equalsIgnoreCase(namespace)) {
                continue;
            }
            if (targetModId == null && (namespace.equals("minecraft") || namespace.equals("pocketodds"))) {
                continue;
            }

            totalChecked++;

            // Exclusion filters
            String exclusionReason = checkExclusion(id, item);
            if (exclusionReason != null) {
                filteredReasons.put(exclusionReason, filteredReasons.getOrDefault(exclusionReason, 0) + 1);
                continue;
            }

            String offerId = namespace + "_" + id.getPath().replace('/', '_');
            String fileName = offerId + ".json";
            Path targetFile = outputDir.resolve(fileName);

            if (Files.exists(targetFile) || ShopOfferRegistry.getOffer(offerId) != null) {
                skippedExisting++;
                continue;
            }

            ShopCategory category = detectCategory(item, id);
            long price = calculateItemValue(item, id, category, recipeManager);

            int purchaseLimit = 0;
            String limitPeriod = "UNLIMITED";
            String requiredAdvancement = "";
            int sortOrder = 100;
            if (category == ShopCategory.CREATIVE) {
                purchaseLimit = 1;
                limitPeriod = "PER_PLAYER";
                requiredAdvancement = "allthemods:allthemodium/atm_star";
                sortOrder = 1000;
            }

            JsonObject json = new JsonObject();
            json.addProperty("offerId", offerId);
            json.addProperty("item", id.toString());
            json.addProperty("count", 1);
            json.addProperty("priceCredits", price);
            json.addProperty("category", category.name());
            json.addProperty("purchaseLimit", purchaseLimit);
            json.addProperty("limitPeriod", limitPeriod);
            json.addProperty("requiredAdvancement", requiredAdvancement);
            json.addProperty("enabled", false);
            json.addProperty("sortOrder", sortOrder);

            JsonArray mods = new JsonArray();
            mods.add(namespace);
            json.add("requiredMods", mods);

            JsonArray items = new JsonArray();
            items.add(id.toString());
            json.add("requiredItems", items);

            json.addProperty("nameKey", "item." + namespace + "." + id.getPath().replace('/', '.'));
            json.addProperty("descriptionKey", "item." + namespace + "." + id.getPath().replace('/', '.') + ".desc");

            try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
                generatedCount++;
                generatedDetails.add(String.format("| `%s` | `%s` | %s | %d кр. |", offerId, id, category.name(), price));
            } catch (IOException e) {
                PocketOdds.LOGGER.error("Pocket Odds: Failed to write generated offer JSON {}: {}", targetFile, e.getMessage());
            }
        }

        // Generate markdown report
        Path reportFile = outputDir.resolve("shop_generation_report.md");
        writeReport(reportFile, targetModId, totalChecked, generatedCount, skippedExisting, filteredReasons, generatedDetails);

        String msg = String.format("§aГенерация завершена!§r Проверено: %d, Сгенерировано: %d, Пропущено (существуют): %d, Отфильтровано: %d. Отчёт: %s",
                totalChecked, generatedCount, skippedExisting,
                filteredReasons.values().stream().mapToInt(Integer::intValue).sum(),
                reportFile.getFileName().toString());

        source.sendSuccess(() -> Component.literal(msg), true);
        return generatedCount;
    }

    public static String checkExclusion(ResourceLocation id, Item item) {
        if (item == Items.AIR) return "Air Item";
        if (item instanceof SpawnEggItem) return "Spawn Egg";
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("debug") || path.contains("barrier") || path.contains("structure_void")
                || path.contains("test_") || path.contains("dummy") || path.contains("placeholder")
                || path.contains("developer_")) {
            return "Technical / Debug Item";
        }
        if (path.endsWith("_bundle") && !path.contains("shulker")) {
            return "Unfinished / Empty Bundle Item";
        }
        return null;
    }

    public static ShopCategory detectCategory(Item item, ResourceLocation id) {
        if (id == null) return ShopCategory.RESOURCES;
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("creative")) {
            return ShopCategory.CREATIVE;
        }

        // 1. Tag & Class-based analysis
        if (item instanceof net.minecraft.world.item.TieredItem || item instanceof net.minecraft.world.item.ArmorItem
                || item instanceof net.minecraft.world.item.BowItem || item instanceof net.minecraft.world.item.CrossbowItem
                || item instanceof net.minecraft.world.item.TridentItem || item instanceof net.minecraft.world.item.ShieldItem
                || item instanceof net.minecraft.world.item.FishingRodItem || item instanceof net.minecraft.world.item.ShearsItem) {
            return ShopCategory.TOOLS;
        }

        if (item != null && item.isEdible()) {
            return ShopCategory.CONSUMABLES;
        }
        if (item instanceof net.minecraft.world.item.PotionItem || item instanceof net.minecraft.world.item.ArrowItem) {
            return ShopCategory.CONSUMABLES;
        }

        if (item != null && item.builtInRegistryHolder() != null) {
            var tags = item.builtInRegistryHolder().tags();
            boolean hasToolTag = tags.anyMatch(t -> {
                String p = t.location().getPath();
                return p.contains("tools") || p.contains("armors") || p.contains("weapons");
            });
            if (hasToolTag) return ShopCategory.TOOLS;

            tags = item.builtInRegistryHolder().tags();
            boolean hasStorageTag = tags.anyMatch(t -> {
                String p = t.location().getPath();
                return p.contains("chests") || p.contains("barrels") || p.contains("shulker_boxes");
            });
            if (hasStorageTag) return ShopCategory.STORAGE;

            tags = item.builtInRegistryHolder().tags();
            boolean hasComponentTag = tags.anyMatch(t -> {
                String p = t.location().getPath();
                return p.contains("circuits") || p.contains("gears") || p.contains("plates")
                        || p.contains("wires") || p.contains("rods") || p.contains("dusts");
            });
            if (hasComponentTag) return ShopCategory.COMPONENTS;

            tags = item.builtInRegistryHolder().tags();
            boolean hasFoodTag = tags.anyMatch(t -> {
                String p = t.location().getPath();
                return p.contains("food") || p.contains("crops") || p.contains("meat") || p.contains("seeds");
            });
            if (hasFoodTag) return ShopCategory.CONSUMABLES;
        }

        return detectCategory(path);
    }

    public static ShopCategory detectCategory(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.contains("creative")) {
            return ShopCategory.CREATIVE;
        }
        if (p.contains("star") || p.contains("infinity") || p.contains("relic") || p.contains("unobtainium")) {
            return ShopCategory.RARE;
        }
        if (p.contains("circuit") || p.contains("alloy") || p.contains("processor") || p.contains("gear")
                || p.contains("plate") || p.contains("wire") || p.contains("coil") || p.contains("core")
                || p.contains("tube") || p.contains("pipe") || p.contains("cable") || p.contains("rod")) {
            return ShopCategory.COMPONENTS;
        }
        if (p.contains("generator") || p.contains("furnace") || p.contains("crusher") || p.contains("smelter")
                || p.contains("infuser") || p.contains("reactor") || p.contains("turbine") || p.contains("press")
                || p.contains("machine") || p.contains("controller") || p.contains("assembler")) {
            return ShopCategory.MACHINES;
        }
        if (p.contains("backpack") || p.contains("chest") || p.contains("barrel") || p.contains("drawer")
                || p.contains("upgrade") || p.contains("storage") || p.contains("tank") || p.contains("crate")) {
            return ShopCategory.STORAGE;
        }
        if (p.contains("sword") || p.contains("pickaxe") || p.contains("axe") || p.contains("shovel")
                || p.contains("hoe") || p.contains("helmet") || p.contains("chestplate") || p.contains("leggings")
                || p.contains("boots") || p.contains("jetpack") || p.contains("bow") || p.contains("shield")) {
            return ShopCategory.TOOLS;
        }
        if (p.contains("cake") || p.contains("pie") || p.contains("food") || p.contains("apple")
                || p.contains("berry") || p.contains("potion") || p.contains("bottle") || p.contains("bread")) {
            return ShopCategory.CONSUMABLES;
        }
        return ShopCategory.RESOURCES;
    }

    public static long calculateItemValue(Item targetItem, ResourceLocation id, ShopCategory category, net.minecraft.world.item.crafting.RecipeManager recipeManager) {
        if (category == ShopCategory.CREATIVE) {
            return calculateBasePrice(category, id != null ? id.getPath() : "");
        }

        String path = id != null ? id.getPath().toLowerCase(Locale.ROOT) : "";

        // Check primitive baseline prices
        Long basePrimitive = getPrimitiveBasePrice(path);
        if (basePrimitive != null) {
            return basePrimitive;
        }

        // Recipe-based cost calculation
        if (recipeManager != null && targetItem != null && targetItem != Items.AIR) {
            long recipeCost = calculateRecipeCost(targetItem, recipeManager, new HashSet<>(), 0);
            if (recipeCost > 0) {
                long finalPrice = Math.max(16L, Math.min(ShopOffer.MAX_PRICE_CREDITS, recipeCost));
                return roundToSensibleCreditValue(finalPrice);
            }
        }

        // Fallback to rarity and category heuristics
        long categoryBase = calculateBasePrice(category, path);
        if (targetItem != null) {
            try {
                net.minecraft.world.item.Rarity rarity = targetItem.getRarity(new ItemStack(targetItem));
                double rarityMult = switch (rarity) {
                    case COMMON -> 1.0;
                    case UNCOMMON -> 1.5;
                    case RARE -> 3.0;
                    case EPIC -> 6.0;
                };
                categoryBase = Math.round(categoryBase * rarityMult);
            } catch (Throwable ignored) {
            }
        }
        return Math.max(16L, Math.min(ShopOffer.MAX_PRICE_CREDITS, categoryBase));
    }

    private static Long getPrimitiveBasePrice(String path) {
        if (path.contains("unobtainium_ingot")) return 131072L;
        if (path.contains("unobtainium_nugget")) return 14563L;
        if (path.contains("vibranium_ingot")) return 32768L;
        if (path.contains("vibranium_nugget")) return 3640L;
        if (path.contains("allthemodium_ingot")) return 8192L;
        if (path.contains("allthemodium_nugget")) return 910L;
        if (path.contains("nether_star")) return 4096L;
        if (path.contains("netherite_ingot")) return 512L;
        if (path.contains("diamond") && !path.contains("ore") && !path.contains("block")) return 64L;
        if (path.contains("emerald") && !path.contains("ore") && !path.contains("block")) return 64L;
        if (path.contains("gold_ingot") || path.contains("raw_gold")) return 16L;
        if (path.contains("iron_ingot") || path.contains("raw_iron")) return 8L;
        if (path.contains("copper_ingot") || path.contains("raw_copper")) return 4L;
        if (path.contains("redstone") || path.contains("lapis")) return 8L;
        if (path.contains("coal")) return 4L;
        if (path.contains("cobblestone") || path.contains("dirt") || path.contains("sand") || path.contains("gravel")) return 1L;
        return null;
    }

    private static long calculateRecipeCost(Item targetItem, net.minecraft.world.item.crafting.RecipeManager recipeManager, Set<Item> visited, int depth) {
        if (depth > 5 || visited.contains(targetItem)) {
            return 0L;
        }
        visited.add(targetItem);

        net.minecraft.world.item.crafting.Recipe<?> bestRecipe = null;
        for (net.minecraft.world.item.crafting.Recipe<?> r : recipeManager.getRecipes()) {
            ItemStack res = r.getResultItem(net.minecraft.core.RegistryAccess.EMPTY);
            if (!res.isEmpty() && res.getItem() == targetItem) {
                bestRecipe = r;
                break;
            }
        }

        if (bestRecipe == null) {
            visited.remove(targetItem);
            return 0L;
        }

        long totalIngCost = 0L;
        int count = 0;
        for (net.minecraft.world.item.crafting.Ingredient ing : bestRecipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            ItemStack[] items = ing.getItems();
            if (items.length == 0) continue;
            Item ingItem = items[0].getItem();
            long ingCost;

            ResourceLocation ingId = ForgeRegistries.ITEMS.getKey(ingItem);
            String ingPath = ingId != null ? ingId.getPath().toLowerCase(Locale.ROOT) : "";
            Long base = getPrimitiveBasePrice(ingPath);
            if (base != null) {
                ingCost = base;
            } else {
                ingCost = calculateRecipeCost(ingItem, recipeManager, visited, depth + 1);
                if (ingCost == 0) {
                    ingCost = 32L;
                }
            }
            totalIngCost += ingCost;
            count++;
        }

        visited.remove(targetItem);

        if (count == 0) return 0L;
        ItemStack output = bestRecipe.getResultItem(net.minecraft.core.RegistryAccess.EMPTY);
        int outCount = Math.max(1, output.getCount());
        double stepCost = ((double) totalIngCost / outCount) * 1.25;
        return Math.round(stepCost);
    }

    private static long roundToSensibleCreditValue(long value) {
        if (value <= 32) return value;
        if (value <= 256) return Math.round(value / 8.0) * 8L;
        if (value <= 2048) return Math.round(value / 32.0) * 32L;
        if (value <= 16384) return Math.round(value / 128.0) * 128L;
        return Math.round(value / 512.0) * 512L;
    }

    public static long calculateBasePrice(ShopCategory category, String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (category == ShopCategory.CREATIVE) {
            if (p.contains("controller") || p.contains("energy") || p.contains("cell") || p.contains("cube")) {
                return 1_048_576L;
            }
            if (p.contains("pool") || p.contains("jar") || p.contains("compressor")) {
                return 524_288L;
            }
            return 262_144L;
        }
        if (category == ShopCategory.RARE) {
            return 8192L;
        }
        if (category == ShopCategory.MACHINES) {
            return 2048L;
        }
        if (category == ShopCategory.STORAGE) {
            if (p.contains("upgrade")) return 1024L;
            return 512L;
        }
        if (category == ShopCategory.COMPONENTS) {
            if (p.contains("ultimate") || p.contains("atomic")) return 4096L;
            if (p.contains("advanced") || p.contains("reinforced")) return 1024L;
            return 256L;
        }
        if (category == ShopCategory.TOOLS) {
            if (p.contains("netherite") || p.contains("allthemodium")) return 4096L;
            if (p.contains("diamond")) return 1024L;
            return 512L;
        }
        if (category == ShopCategory.CONSUMABLES) {
            return 64L;
        }
        // RESOURCES default
        if (p.contains("nugget")) return 16L;
        if (p.contains("raw_") || p.contains("ore")) return 32L;
        if (p.contains("ingot") || p.contains("gem")) return 64L;
        if (p.contains("block")) return 512L;
        return 64L;
    }

    private static void writeReport(Path reportPath, String filterMod, int total, int generated, int skipped,
                                    Map<String, Integer> reasons, List<String> generatedDetails) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Отчёт генерации каталога предложений Pocket Odds\n\n");
        sb.append("- **Дата генерации**: ").append(new Date()).append("\n");
        sb.append("- **Фильтр мода**: ").append(filterMod == null ? "Все установленные моды (кроме vanilla/pocketodds)" : filterMod).append("\n");
        sb.append("- **Всего проверено предметов**: ").append(total).append("\n");
        sb.append("- **Успешно сгенерировано**: ").append(generated).append("\n");
        sb.append("- **Пропущено (уже существуют)**: ").append(skipped).append("\n");
        sb.append("- **Отфильтровано по правилам исключения**: ").append(reasons.values().stream().mapToInt(Integer::intValue).sum()).append("\n\n");

        sb.append("## Причины исключения\n\n");
        sb.append("| Причина | Количество предметов |\n");
        sb.append("|---|---|\n");
        for (Map.Entry<String, Integer> e : reasons.entrySet()) {
            sb.append(String.format("| %s | %d |\n", e.getKey(), e.getValue()));
        }
        sb.append("\n");

        if (!generatedDetails.isEmpty()) {
            sb.append("## Сгенерированные шаблоны предложений (`enabled: false`)\n\n");
            sb.append("| Offer ID | Item ID | Категория | Базовая цена |\n");
            sb.append("|---|---|---|---|\n");
            int limit = Math.min(100, generatedDetails.size());
            for (int i = 0; i < limit; i++) {
                sb.append(generatedDetails.get(i)).append("\n");
            }
            if (generatedDetails.size() > limit) {
                sb.append(String.format("\n*... и ещё %d предложений.*\n", generatedDetails.size() - limit));
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
            writer.write(sb.toString());
        } catch (IOException e) {
            PocketOdds.LOGGER.error("Pocket Odds: Failed to write generation report {}: {}", reportPath, e.getMessage());
        }
    }
}
