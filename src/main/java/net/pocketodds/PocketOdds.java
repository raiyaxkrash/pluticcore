package net.pocketodds;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.gambling.tracker.ActiveRollTracker;
import net.pocketodds.registration.ModCreativeTabs;
import net.pocketodds.registration.ModItems;
import net.pocketodds.registration.ModMenus;
import org.slf4j.Logger;

@Mod(PocketOdds.MODID)
public class PocketOdds {
    public static final String MODID = "pocketodds";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PocketOdds() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register items, menus and creative tab
        ModItems.register(modEventBus);
        ModMenus.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        // Register common setup listener
        modEventBus.addListener(this::commonSetup);

        // Register Server Config and migration listener
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, PocketOddsConfig.SERVER_SPEC);
        modEventBus.addListener(PocketOddsConfig::onConfigLoad);

        // Register Client Config
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, net.pocketodds.client.PocketOddsClientConfig.CLIENT_SPEC);

        // Register event listeners on Forge bus
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::onDatapackSync);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);

        LOGGER.info("Pocket Odds initialized!");
    }

    private void onAddReloadListeners(net.minecraftforge.event.AddReloadListenerEvent event) {
        event.addListener(net.pocketodds.shop.ShopOfferRegistry.INSTANCE);
    }

    private void onDatapackSync(net.minecraftforge.event.OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            net.pocketodds.shop.ShopService.syncShopToPlayer(event.getPlayer(),
                    net.pocketodds.data.JackpotSavedData.get(event.getPlayer().serverLevel()));
        } else if (event.getPlayerList() != null) {
            for (net.minecraft.server.level.ServerPlayer player : event.getPlayerList().getPlayers()) {
                net.pocketodds.shop.ShopService.syncShopToPlayer(player,
                        net.pocketodds.data.JackpotSavedData.get(player.serverLevel()));
            }
        }
    }

    private void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        net.pocketodds.command.ShopCatalogGeneratorCommand.register(event.getDispatcher());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            net.pocketodds.network.ModMessages.register();
        });
        LOGGER.info("Pocket Odds common setup completed.");
    }
}
