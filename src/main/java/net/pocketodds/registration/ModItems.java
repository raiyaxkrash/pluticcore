package net.pocketodds.registration;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.pocketodds.PocketOdds;
import net.pocketodds.item.*;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, PocketOdds.MODID);

    // 1. Copper Chip
    public static final RegistryObject<Item> COPPER_CHIP = ITEMS.register("copper_chip",
            () -> new ChipItem(ChipTier.COPPER, new Item.Properties().stacksTo(64)));

    // 2. Gold Chip
    public static final RegistryObject<Item> GOLD_CHIP = ITEMS.register("gold_chip",
            () -> new ChipItem(ChipTier.GOLD, new Item.Properties().stacksTo(64)));

    // 3. Diamond Chip
    public static final RegistryObject<Item> DIAMOND_CHIP = ITEMS.register("diamond_chip",
            () -> new ChipItem(ChipTier.DIAMOND, new Item.Properties().stacksTo(64)));

    // 4. Netherite Chip
    public static final RegistryObject<Item> NETHERITE_CHIP = ITEMS.register("netherite_chip",
            () -> new ChipItem(ChipTier.NETHERITE, new Item.Properties().stacksTo(64).fireResistant()));

    // 5. Pocket Slot
    public static final RegistryObject<Item> POCKET_SLOT = ITEMS.register("pocket_slot",
            () -> new PocketSlotItem(new Item.Properties().stacksTo(1)));

    // 6. Void Dice
    public static final RegistryObject<Item> VOID_DICE = ITEMS.register("void_dice",
            () -> new VoidDiceItem(new Item.Properties().stacksTo(1)));

    // 7. Roulette Token
    public static final RegistryObject<Item> ROULETTE_TOKEN = ITEMS.register("roulette_token",
            () -> new RouletteTokenItem(new Item.Properties().stacksTo(16)));

    // 8. Deck of Fate
    public static final RegistryObject<Item> DECK_OF_FATE = ITEMS.register("deck_of_fate",
            () -> new DeckOfFateItem(new Item.Properties().stacksTo(1)));

    // 9. Joker
    public static final RegistryObject<Item> JOKER = ITEMS.register("joker",
            () -> new JokerItem(new Item.Properties().stacksTo(16)));

    // 10. Insurance
    public static final RegistryObject<Item> INSURANCE = ITEMS.register("insurance",
            () -> new InsuranceItem(new Item.Properties().stacksTo(16)));

    // 11. Cursed Card
    public static final RegistryObject<Item> CURSED_CARD = ITEMS.register("cursed_card",
            () -> new CursedCardItem(new Item.Properties().stacksTo(16)));

    // 12. Jackpot Token
    public static final RegistryObject<Item> JACKPOT_TOKEN = ITEMS.register("jackpot_token",
            () -> new JackpotTokenItem(new Item.Properties().stacksTo(16).fireResistant()));

    // 13. Pocket Casino
    public static final RegistryObject<Item> POCKET_CASINO = ITEMS.register("pocket_casino",
            () -> new PocketCasinoItem(new Item.Properties().stacksTo(1).fireResistant()));

    // 14. Coin Pouch
    public static final RegistryObject<Item> COIN_POUCH = ITEMS.register("coin_pouch",
            () -> new CoinPouchItem(new Item.Properties().stacksTo(1)));

    // 15. Prize Token
    public static final RegistryObject<Item> PRIZE_TOKEN = ITEMS.register("prize_token",
            () -> new net.pocketodds.item.PrizeTokenItem(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
