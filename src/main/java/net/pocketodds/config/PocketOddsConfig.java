package net.pocketodds.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.pocketodds.PocketOdds;
import net.pocketodds.gambling.itembet.ItemBetRegistry;
import net.pocketodds.gambling.itembet.ItemRewardRegistry;

import java.util.List;

public class PocketOddsConfig {
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Server SERVER;
    public static final int CURRENT_CONFIG_VERSION = 3;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SERVER = new Server(builder);
        SERVER_SPEC = builder.build();
    }

    public static boolean isConfigLoaded() {
        return SERVER_SPEC != null && SERVER_SPEC.isLoaded();
    }

    public static void onConfigLoad(net.minecraftforge.fml.event.config.ModConfigEvent event) {
        if (event.getConfig().getSpec() == SERVER_SPEC) {
            migrateLegacyConfigIfNeeded();
            if (SERVER != null) {
                ItemBetRegistry.loadConfig(SERVER.allowedItemBets.get());
                ItemRewardRegistry.loadConfig(
                        SERVER.slotRewardTable.get(),
                        SERVER.diceRewardTable.get(),
                        SERVER.rouletteRewardTable.get(),
                        SERVER.deckRewardTable.get()
                );
            }
        }
    }

    public static void migrateLegacyConfigIfNeeded() {
        if (!isConfigLoaded() || SERVER == null) return;
        int version = SERVER.configVersion.get();
        if (version < 2) {
            boolean migrated = false;

            // Slot weights: 35/25/18/10/6/2/3/1 -> 40/28/16/8/4/2/5/1
            if (SERVER.weightCherry.get() == 35) { SERVER.weightCherry.set(40); migrated = true; }
            if (SERVER.weightIron.get() == 25) { SERVER.weightIron.set(28); migrated = true; }
            if (SERVER.weightGold.get() == 18) { SERVER.weightGold.set(16); migrated = true; }
            if (SERVER.weightDiamond.get() == 10) { SERVER.weightDiamond.set(8); migrated = true; }
            if (SERVER.weightEmerald.get() == 6) { SERVER.weightEmerald.set(4); migrated = true; }
            if (SERVER.weightSkull.get() == 3) { SERVER.weightSkull.set(5); migrated = true; }

            // 3-match payouts: 25->20, 50->40, 77->60, 100->60
            if (Math.abs(SERVER.payoutDiamond3.get() - 25.0) < 0.001) { SERVER.payoutDiamond3.set(20.0); migrated = true; }
            if (Math.abs(SERVER.payoutEmerald3.get() - 50.0) < 0.001) { SERVER.payoutEmerald3.set(40.0); migrated = true; }
            if (Math.abs(SERVER.payoutJoker3.get() - 77.0) < 0.001) { SERVER.payoutJoker3.set(60.0); migrated = true; }
            if (Math.abs(SERVER.payoutStar3Multiplier.get() - 100.0) < 0.001) { SERVER.payoutStar3Multiplier.set(60.0); migrated = true; }

            // 2-match payouts: 1.2/1.5/2.0/4.0/8.0/15.0 -> 0.5/1.0/1.5/2.5/4.0/8.0
            if (Math.abs(SERVER.payoutCherry2.get() - 1.2) < 0.001) { SERVER.payoutCherry2.set(0.5); migrated = true; }
            if (Math.abs(SERVER.payoutIron2.get() - 1.5) < 0.001) { SERVER.payoutIron2.set(1.0); migrated = true; }
            if (Math.abs(SERVER.payoutGold2.get() - 2.0) < 0.001) { SERVER.payoutGold2.set(1.5); migrated = true; }
            if (Math.abs(SERVER.payoutDiamond2.get() - 4.0) < 0.001) { SERVER.payoutDiamond2.set(2.5); migrated = true; }
            if (Math.abs(SERVER.payoutEmerald2.get() - 8.0) < 0.001) { SERVER.payoutEmerald2.set(4.0); migrated = true; }
            if (Math.abs(SERVER.payoutStar2.get() - 15.0) < 0.001) { SERVER.payoutStar2.set(8.0); migrated = true; }

            // Void dice: 2.5->2.0, 3.5->3.0
            if (Math.abs(SERVER.diceDoublePayout.get() - 2.5) < 0.001) { SERVER.diceDoublePayout.set(2.0); migrated = true; }
            if (Math.abs(SERVER.diceLuckySevenPayout.get() - 3.5) < 0.001) { SERVER.diceLuckySevenPayout.set(3.0); migrated = true; }

            // Deck of Fate: 1.5->1.25, 2.0->1.5
            if (Math.abs(SERVER.deckFortuneMultiplier.get() - 1.5) < 0.001) { SERVER.deckFortuneMultiplier.set(1.25); migrated = true; }
            if (Math.abs(SERVER.deckRichesMultiplier.get() - 2.0) < 0.001) { SERVER.deckRichesMultiplier.set(1.5); migrated = true; }
        }
        if (version < 3) {
            SERVER.configVersion.set(CURRENT_CONFIG_VERSION);
            SERVER_SPEC.save();
            PocketOdds.LOGGER.info("Pocket Odds: Config migrated to v3 (item bets and reward tables enabled).");
        }
    }

    public static class Server {
        public final ForgeConfigSpec.IntValue configVersion;

        // Cooldown
        public final ForgeConfigSpec.IntValue cooldownTicks;

        // Danger toggle
        public final ForgeConfigSpec.BooleanValue allowDangerousEvents;

        // Jackpot settings
        public final ForgeConfigSpec.LongValue jackpotBaseAmount;
        public final ForgeConfigSpec.DoubleValue jackpotContributionRate;

        // Insurance refund rate
        public final ForgeConfigSpec.DoubleValue insuranceRefundRate;

        // Slot symbol weights
        public final ForgeConfigSpec.IntValue weightCherry;
        public final ForgeConfigSpec.IntValue weightIron;
        public final ForgeConfigSpec.IntValue weightGold;
        public final ForgeConfigSpec.IntValue weightDiamond;
        public final ForgeConfigSpec.IntValue weightEmerald;
        public final ForgeConfigSpec.IntValue weightStar;
        public final ForgeConfigSpec.IntValue weightSkull;
        public final ForgeConfigSpec.IntValue weightJoker;

        // Slot payouts (multiplier of bet)
        public final ForgeConfigSpec.DoubleValue payoutCherry3;
        public final ForgeConfigSpec.DoubleValue payoutIron3;
        public final ForgeConfigSpec.DoubleValue payoutGold3;
        public final ForgeConfigSpec.DoubleValue payoutDiamond3;
        public final ForgeConfigSpec.DoubleValue payoutEmerald3;
        public final ForgeConfigSpec.DoubleValue payoutJoker3;
        public final ForgeConfigSpec.DoubleValue payoutStar3Multiplier;

        public final ForgeConfigSpec.DoubleValue payoutCherry2;
        public final ForgeConfigSpec.DoubleValue payoutIron2;
        public final ForgeConfigSpec.DoubleValue payoutGold2;
        public final ForgeConfigSpec.DoubleValue payoutDiamond2;
        public final ForgeConfigSpec.DoubleValue payoutEmerald2;
        public final ForgeConfigSpec.DoubleValue payoutStar2;

        // Dice multipliers
        public final ForgeConfigSpec.DoubleValue diceDoublePayout;
        public final ForgeConfigSpec.DoubleValue diceLuckySevenPayout;

        // Deck of Fate multipliers
        public final ForgeConfigSpec.DoubleValue deckFortuneMultiplier;
        public final ForgeConfigSpec.DoubleValue deckRichesMultiplier;

        // Item betting
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> allowedItemBets;
        public final ForgeConfigSpec.ConfigValue<String> itemBetPayoutMode;
        public final ForgeConfigSpec.IntValue maxItemRewardCap;
        public final ForgeConfigSpec.DoubleValue bothSameItemWeight;
        public final ForgeConfigSpec.DoubleValue bothTableWeight;

        // Reward tables
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> slotRewardTable;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> diceRewardTable;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> rouletteRewardTable;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> deckRewardTable;

        public Server(ForgeConfigSpec.Builder builder) {
            builder.push("general");
            configVersion = builder
                    .comment("Configuration version tracker for automatic migrations")
                    .defineInRange("configVersion", CURRENT_CONFIG_VERSION, 1, 100);
            cooldownTicks = builder
                    .comment("Cooldown in ticks between slot spins and dice rolls (20 ticks = 1 second)")
                    .defineInRange("cooldownTicks", 30, 5, 200);
            allowDangerousEvents = builder
                    .comment("Whether 3 skulls can trigger dangerous events (summoning mobs / lightning)")
                    .define("allowDangerousEvents", true);
            builder.pop();

            builder.push("jackpot");
            jackpotBaseAmount = builder
                    .comment("Initial and minimum server jackpot pool value in base chip units")
                    .defineInRange("jackpotBaseAmount", 100L, 10L, 1000000L);
            jackpotContributionRate = builder
                    .comment("Fraction of each bet added to the server jackpot pool (0.05 = 5%). Set to 0 to disable.")
                    .defineInRange("jackpotContributionRate", 0.05, 0.0, 0.5);
            builder.pop();

            builder.push("insurance");
            insuranceRefundRate = builder
                    .comment("Fraction of bet returned when Insurance item is consumed (0.50 = 50%)")
                    .defineInRange("insuranceRefundRate", 0.50, 0.10, 1.00);
            builder.pop();

            builder.push("slot_weights");
            weightCherry = builder.defineInRange("weightCherry", 40, 1, 1000);
            weightIron = builder.defineInRange("weightIron", 28, 1, 1000);
            weightGold = builder.defineInRange("weightGold", 16, 1, 1000);
            weightDiamond = builder.defineInRange("weightDiamond", 8, 1, 1000);
            weightEmerald = builder.defineInRange("weightEmerald", 4, 1, 1000);
            weightStar = builder.defineInRange("weightStar", 2, 1, 1000);
            weightSkull = builder.defineInRange("weightSkull", 5, 1, 1000);
            weightJoker = builder.defineInRange("weightJoker", 1, 1, 1000);
            builder.pop();

            builder.push("slot_payouts_3_matches");
            payoutCherry3 = builder.defineInRange("payoutCherry3", 3.0, 0.0, 1000.0);
            payoutIron3 = builder.defineInRange("payoutIron3", 5.0, 0.0, 1000.0);
            payoutGold3 = builder.defineInRange("payoutGold3", 10.0, 0.0, 1000.0);
            payoutDiamond3 = builder.defineInRange("payoutDiamond3", 20.0, 0.0, 1000.0);
            payoutEmerald3 = builder.defineInRange("payoutEmerald3", 40.0, 0.0, 1000.0);
            payoutJoker3 = builder.defineInRange("payoutJoker3", 60.0, 0.0, 1000.0);
            payoutStar3Multiplier = builder
                    .comment("Multiplier for base bet paid out in addition to the server jackpot pool on 3 stars")
                    .defineInRange("payoutStar3Multiplier", 60.0, 0.0, 1000.0);
            builder.pop();

            builder.push("slot_payouts_2_matches");
            payoutCherry2 = builder.defineInRange("payoutCherry2", 0.5, 0.0, 100.0);
            payoutIron2 = builder.defineInRange("payoutIron2", 1.0, 0.0, 100.0);
            payoutGold2 = builder.defineInRange("payoutGold2", 1.5, 0.0, 100.0);
            payoutDiamond2 = builder.defineInRange("payoutDiamond2", 2.5, 0.0, 100.0);
            payoutEmerald2 = builder.defineInRange("payoutEmerald2", 4.0, 0.0, 100.0);
            payoutStar2 = builder.defineInRange("payoutStar2", 8.0, 0.0, 100.0);
            builder.pop();

            builder.push("void_dice");
            diceDoublePayout = builder.defineInRange("diceDoublePayout", 2.0, 0.0, 100.0);
            diceLuckySevenPayout = builder.defineInRange("diceLuckySevenPayout", 3.0, 0.0, 100.0);
            builder.pop();

            builder.push("deck_of_fate");
            deckFortuneMultiplier = builder.defineInRange("deckFortuneMultiplier", 1.25, 1.0, 10.0);
            deckRichesMultiplier = builder.defineInRange("deckRichesMultiplier", 1.5, 1.0, 20.0);
            builder.pop();

            builder.push("item_bets");
            allowedItemBets = builder
                    .comment("List of items allowed for betting.",
                            "Format: itemId;unitCreditValue;minCount;maxCount;allowedGames;allowNbt;allowDamaged",
                            "Containers are unconditionally prohibited.")
                    .defineListAllowEmpty(List.of("allowedItemBets"), () -> List.of(
                            "minecraft:copper_ingot;1;1;64;SLOT,DICE,ROULETTE,DECK;false;false",
                            "minecraft:iron_ingot;1;1;64;SLOT,DICE,ROULETTE,DECK;false;false",
                            "minecraft:gold_ingot;8;1;64;SLOT,DICE,ROULETTE,DECK;false;false",
                            "minecraft:emerald;16;1;64;SLOT,DICE,ROULETTE,DECK;false;false",
                            "minecraft:diamond;64;1;64;SLOT,DICE,ROULETTE,DECK;false;false",
                            "minecraft:netherite_ingot;512;1;16;SLOT,DICE,ROULETTE,DECK;false;false"
                    ), obj -> obj instanceof String);
            itemBetPayoutMode = builder
                    .comment("Payout mode for item bets: SAME_ITEM (return multiplied bet item), REWARD_TABLE (drop from reward table), BOTH (budgeted split)")
                    .define("itemBetPayoutMode", "SAME_ITEM");
            maxItemRewardCap = builder
                    .comment("Maximum total count of items that can be paid out in a single reward")
                    .defineInRange("maxItemRewardCap", 512, 1, 10000);
            bothSameItemWeight = builder
                    .comment("Budget fraction for SAME_ITEM multiplier payout in BOTH mode (0.80 = 80%)")
                    .defineInRange("bothSameItemWeight", 0.80, 0.0, 1.0);
            bothTableWeight = builder
                    .comment("Budget fraction for REWARD_TABLE drop payout in BOTH mode (0.20 = 20%)")
                    .defineInRange("bothTableWeight", 0.20, 0.0, 1.0);
            builder.pop();

            builder.push("item_reward_tables");
            slotRewardTable = builder
                    .comment("Reward table for Slot machine. Format: itemId;weight;minCount;maxCount;maxCap;creditValue;minBetCredits;maxBetCredits")
                    .defineListAllowEmpty(List.of("slotRewardTable"), () -> List.of(
                            "minecraft:iron_ingot;50;2;16;64;1;1;32",
                            "minecraft:gold_ingot;30;1;8;32;8;8;128",
                            "minecraft:diamond;15;1;4;16;64;64;1024",
                            "minecraft:netherite_ingot;3;1;1;4;512;512;100000",
                            "pocketodds:joker;2;1;1;1;100;16;100000",
                            "pocketodds:insurance;5;1;1;2;50;8;100000"
                    ), obj -> obj instanceof String);

            diceRewardTable = builder
                    .comment("Reward table for Void Dice. Format: itemId;weight;minCount;maxCount;maxCap;creditValue;minBetCredits;maxBetCredits")
                    .defineListAllowEmpty(List.of("diceRewardTable"), () -> List.of(
                            "minecraft:iron_ingot;50;2;16;64;1;1;32",
                            "minecraft:gold_ingot;30;1;8;32;8;8;128",
                            "minecraft:diamond;15;1;4;16;64;64;1024",
                            "pocketodds:insurance;5;1;1;2;50;8;100000"
                    ), obj -> obj instanceof String);

            rouletteRewardTable = builder
                    .comment("Reward table for Roulette. Format: itemId;weight;minCount;maxCount;maxCap;creditValue;minBetCredits;maxBetCredits")
                    .defineListAllowEmpty(List.of("rouletteRewardTable"), () -> List.of(
                            "minecraft:gold_ingot;40;2;8;32;8;8;128",
                            "minecraft:diamond;20;1;4;16;64;64;1024",
                            "pocketodds:insurance;5;1;1;2;50;8;100000"
                    ), obj -> obj instanceof String);

            deckRewardTable = builder
                    .comment("Reward table for Deck of Fate. Format: itemId;weight;minCount;maxCount;maxCap;creditValue;minBetCredits;maxBetCredits")
                    .defineListAllowEmpty(List.of("deckRewardTable"), () -> List.of(
                            "minecraft:iron_ingot;50;2;16;64;1;1;32",
                            "minecraft:gold_ingot;30;1;8;32;8;8;128",
                            "minecraft:diamond;15;1;4;16;64;64;1024",
                            "pocketodds:joker;3;1;1;1;100;16;100000",
                            "pocketodds:insurance;5;1;1;2;50;8;100000"
                    ), obj -> obj instanceof String);
            builder.pop();
        }
    }
}
