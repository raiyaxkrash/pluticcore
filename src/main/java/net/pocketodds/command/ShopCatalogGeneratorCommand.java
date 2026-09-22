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
import net.minecraft.world.item.SpawnEggItem;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.pocketodds.PocketOdds;
import net.pocketodds.shop.ShopCategory;
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

            ShopCategory category = detectCategory(id.getPath());
            long price = calculateBasePrice(category, id.getPath());

            JsonObject json = new JsonObject();
            json.addProperty("offerId", offerId);
            json.addProperty("item", id.toString());
            json.addProperty("count", 1);
            json.addProperty("priceCredits", price);
            json.addProperty("category", category.name());
            json.addProperty("purchaseLimit", 0);
            json.addProperty("limitPeriod", "UNLIMITED");
            json.addProperty("requiredAdvancement", "");
            json.addProperty("enabled", false);
            json.addProperty("sortOrder", 100);

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

    private static String checkExclusion(ResourceLocation id, Item item) {
        if (item == Items.AIR) return "Air Item";
        if (item instanceof SpawnEggItem) return "Spawn Egg";
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("debug") || path.contains("barrier") || path.contains("structure_void")
                || path.contains("test_") || path.contains("dummy") || path.contains("placeholder")
                || path.contains("developer_")) {
            return "Technical / Debug Item";
        }
        if (path.endsWith("_bundle") && !path.contains("shulker")) {
            // Safe exclusion for empty unfinished items
        }
        return null;
    }

    public static ShopCategory detectCategory(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.contains("creative") || p.contains("star") || p.contains("infinity") || p.contains("relic") || p.contains("unobtainium")) {
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

    public static long calculateBasePrice(ShopCategory category, String path) {
        String p = path.toLowerCase(Locale.ROOT);
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
