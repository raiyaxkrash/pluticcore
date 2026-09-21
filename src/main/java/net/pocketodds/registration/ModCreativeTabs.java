package net.pocketodds.registration;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.pocketodds.PocketOdds;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PocketOdds.MODID);

    public static final RegistryObject<CreativeModeTab> SLOTS_TAB = CREATIVE_MODE_TABS.register("slots_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.pocketodds.slots_tab"))
                    .icon(() -> new ItemStack(ModItems.GOLD_CHIP.get()))
                    .displayItems((parameters, output) -> {
                        // 1-4 Chips
                        output.accept(ModItems.COPPER_CHIP.get());
                        output.accept(ModItems.GOLD_CHIP.get());
                        output.accept(ModItems.DIAMOND_CHIP.get());
                        output.accept(ModItems.NETHERITE_CHIP.get());

                        // 5-6 Main Casino & Pouch Items
                        output.accept(ModItems.POCKET_CASINO.get());
                        output.accept(ModItems.COIN_POUCH.get());

                        // 9-13 Special Cards, Rewards & Tokens
                        output.accept(ModItems.JOKER.get());
                        output.accept(ModItems.INSURANCE.get());
                        output.accept(ModItems.CURSED_CARD.get());
                        output.accept(ModItems.JACKPOT_TOKEN.get());
                        output.accept(ModItems.PRIZE_TOKEN.get());
                    })
                    .build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
