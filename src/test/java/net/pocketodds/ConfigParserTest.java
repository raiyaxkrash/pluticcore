package net.pocketodds;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.itembet.ItemBetConfigEntry;
import net.pocketodds.gambling.itembet.ItemBetRegistry;
import net.pocketodds.gambling.itembet.ItemRewardRegistry;
import net.pocketodds.gambling.itembet.ItemRewardTable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ConfigParserTest {

    @BeforeAll
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void testValidItemBetConfigParsing() {
        List<String> raw = List.of(
                "minecraft:iron_ingot;1;1;64;SLOT,DICE,ROULETTE,DECK;false;false",
                "minecraft:diamond;64;1;16;SLOT,DICE;true;false"
        );

        ItemBetRegistry.loadConfig(raw);

        ItemBetConfigEntry iron = ItemBetRegistry.getEntry(new net.minecraft.resources.ResourceLocation("minecraft:iron_ingot"));
        Assertions.assertNotNull(iron);
        Assertions.assertEquals(1L, iron.getUnitCreditValue());
        Assertions.assertEquals(1, iron.getMinCount());
        Assertions.assertEquals(64, iron.getMaxCount());
        Assertions.assertTrue(iron.isGameAllowed(GameType.SLOT));
        Assertions.assertTrue(iron.isGameAllowed(GameType.DICE));
        Assertions.assertFalse(iron.isAllowNbt());

        ItemBetConfigEntry diamond = ItemBetRegistry.getEntry(new net.minecraft.resources.ResourceLocation("minecraft:diamond"));
        Assertions.assertNotNull(diamond);
        Assertions.assertEquals(64L, diamond.getUnitCreditValue());
        Assertions.assertEquals(16, diamond.getMaxCount());
        Assertions.assertTrue(diamond.isAllowNbt());
        Assertions.assertFalse(diamond.isGameAllowed(GameType.ROULETTE));
    }

    @Test
    public void testMalformedAndUnknownItemConfigLinesSkippedGracefully() {
        List<String> raw = List.of(
                "# This is a comment",
                "",
                "invalid_format_without_semicolons",
                "minecraft:unknown_fake_item_that_does_not_exist;10;1;10",
                "minecraft:iron_ingot;-5;1;64;SLOT", // negative value
                "minecraft:gold_ingot;8;64;1;SLOT", // min > max
                "minecraft:gold_ingot;8;1;32;SLOT;false;false" // valid line
        );

        ItemBetRegistry.loadConfig(raw);

        // Unknown fake item must not be in registry
        Assertions.assertNull(ItemBetRegistry.getEntry(new net.minecraft.resources.ResourceLocation("minecraft:unknown_fake_item_that_does_not_exist")));

        // Valid gold_ingot must be present
        ItemBetConfigEntry gold = ItemBetRegistry.getEntry(new net.minecraft.resources.ResourceLocation("minecraft:gold_ingot"));
        Assertions.assertNotNull(gold);
        Assertions.assertEquals(8L, gold.getUnitCreditValue());
        Assertions.assertEquals(32, gold.getMaxCount());
    }

    @Test
    public void testRewardTableParsingAndFiltering() {
        List<String> slotRaw = List.of(
                "minecraft:iron_ingot;50;2;16;64;1;1;32",
                "minecraft:diamond;15;1;4;16;64;64;1024",
                "malformed_reward_line"
        );

        ItemRewardRegistry.loadConfig(slotRaw, List.of(), List.of(), List.of());
        ItemRewardTable slotTable = ItemRewardRegistry.getTable(GameType.SLOT);
        Assertions.assertNotNull(slotTable);
        Assertions.assertEquals(2, slotTable.getEntries().size());

        // Test credit range matching
        Assertions.assertTrue(slotTable.getEntries().get(0).matchesBetCredits(10));
        Assertions.assertFalse(slotTable.getEntries().get(0).matchesBetCredits(100));

        Assertions.assertFalse(slotTable.getEntries().get(1).matchesBetCredits(10));
        Assertions.assertTrue(slotTable.getEntries().get(1).matchesBetCredits(100));
    }
}
