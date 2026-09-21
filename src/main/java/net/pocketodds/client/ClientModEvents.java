package net.pocketodds.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.pocketodds.PocketOdds;
import net.pocketodds.gui.casino.PocketCasinoScreen;
import net.pocketodds.gui.pouch.CoinPouchScreen;
import net.pocketodds.registration.ModMenus;

@Mod.EventBusSubscriber(modid = PocketOdds.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.POCKET_CASINO_MENU.get(), PocketCasinoScreen::new);
            MenuScreens.register(ModMenus.COIN_POUCH_MENU.get(), CoinPouchScreen::new);
        });
    }
}