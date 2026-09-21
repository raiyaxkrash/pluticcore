package net.pocketodds.registration;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.pocketodds.PocketOdds;
import net.pocketodds.gui.casino.PocketCasinoMenu;
import net.pocketodds.gui.pouch.CoinPouchMenu;

public class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, PocketOdds.MODID);

    public static final RegistryObject<MenuType<PocketCasinoMenu>> POCKET_CASINO_MENU =
            MENUS.register("pocket_casino", () -> IForgeMenuType.create(PocketCasinoMenu::new));

    public static final RegistryObject<MenuType<CoinPouchMenu>> COIN_POUCH_MENU =
            MENUS.register("coin_pouch", () -> IForgeMenuType.create(CoinPouchMenu::new));

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}