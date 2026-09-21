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
import org.slf4j.Logger;

@Mod(PocketOdds.MODID)
public class PocketOdds {
    public static final String MODID = "pocketodds";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PocketOdds() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register items and creative tab
        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        // Register common setup listener
        modEventBus.addListener(this::commonSetup);

        // Register Server Config and migration listener
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, PocketOddsConfig.SERVER_SPEC);
        modEventBus.addListener(PocketOddsConfig::onConfigLoad);

        // Register event listeners on Forge bus
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("Pocket Odds initialized!");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Pocket Odds common setup completed.");
    }
}
