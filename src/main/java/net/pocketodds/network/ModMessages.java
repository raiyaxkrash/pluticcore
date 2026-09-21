package net.pocketodds.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.pocketodds.PocketOdds;
import net.pocketodds.network.c2s.*;
import net.pocketodds.network.s2c.*;

public class ModMessages {
    public static final String PROTOCOL_VERSION = "2.0.0";
    private static SimpleChannel INSTANCE;
    private static int packetId = 0;
    private static int id() { return packetId++; }

    public static void register() {
        SimpleChannel net = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(PocketOdds.MODID, "messages"))
                .networkProtocolVersion(() -> PROTOCOL_VERSION)
                .clientAcceptedVersions(PROTOCOL_VERSION::equals)
                .serverAcceptedVersions(PROTOCOL_VERSION::equals)
                .simpleChannel();

        INSTANCE = net;

        // C2S Packets
        net.messageBuilder(SelectCasinoCategoryC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(SelectCasinoCategoryC2SPacket::new)
                .encoder(SelectCasinoCategoryC2SPacket::toBytes)
                .consumerMainThread(SelectCasinoCategoryC2SPacket::handle)
                .add();

        net.messageBuilder(UpdateBetSelectionC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(UpdateBetSelectionC2SPacket::new)
                .encoder(UpdateBetSelectionC2SPacket::toBytes)
                .consumerMainThread(UpdateBetSelectionC2SPacket::handle)
                .add();

        net.messageBuilder(StartSlotSpinC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(StartSlotSpinC2SPacket::new)
                .encoder(StartSlotSpinC2SPacket::toBytes)
                .consumerMainThread(StartSlotSpinC2SPacket::handle)
                .add();

        net.messageBuilder(StartRouletteSpinC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(StartRouletteSpinC2SPacket::new)
                .encoder(StartRouletteSpinC2SPacket::toBytes)
                .consumerMainThread(StartRouletteSpinC2SPacket::handle)
                .add();

        net.messageBuilder(RollDiceC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(RollDiceC2SPacket::new)
                .encoder(RollDiceC2SPacket::toBytes)
                .consumerMainThread(RollDiceC2SPacket::handle)
                .add();

        net.messageBuilder(DrawDeckCardC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(DrawDeckCardC2SPacket::new)
                .encoder(DrawDeckCardC2SPacket::toBytes)
                .consumerMainThread(DrawDeckCardC2SPacket::handle)
                .add();

        net.messageBuilder(CashOutDeckC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(CashOutDeckC2SPacket::new)
                .encoder(CashOutDeckC2SPacket::toBytes)
                .consumerMainThread(CashOutDeckC2SPacket::handle)
                .add();

        net.messageBuilder(CoinPouchActionC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(CoinPouchActionC2SPacket::new)
                .encoder(CoinPouchActionC2SPacket::toBytes)
                .consumerMainThread(CoinPouchActionC2SPacket::handle)
                .add();

        net.messageBuilder(RequestCasinoSyncC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(RequestCasinoSyncC2SPacket::new)
                .encoder(RequestCasinoSyncC2SPacket::toBytes)
                .consumerMainThread(RequestCasinoSyncC2SPacket::handle)
                .add();

        net.messageBuilder(BuyShopOfferC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(BuyShopOfferC2SPacket::new)
                .encoder(BuyShopOfferC2SPacket::toBytes)
                .consumerMainThread(BuyShopOfferC2SPacket::handle)
                .add();

        // S2C Packets
        net.messageBuilder(CasinoResultSyncS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(CasinoResultSyncS2CPacket::new)
                .encoder(CasinoResultSyncS2CPacket::toBytes)
                .consumerMainThread(CasinoResultSyncS2CPacket::handle)
                .add();

        net.messageBuilder(CasinoStateSyncS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(CasinoStateSyncS2CPacket::new)
                .encoder(CasinoStateSyncS2CPacket::toBytes)
                .consumerMainThread(CasinoStateSyncS2CPacket::handle)
                .add();

        net.messageBuilder(CoinPouchSyncS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(CoinPouchSyncS2CPacket::new)
                .encoder(CoinPouchSyncS2CPacket::toBytes)
                .consumerMainThread(CoinPouchSyncS2CPacket::handle)
                .add();

        net.messageBuilder(SyncShopCatalogS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(SyncShopCatalogS2CPacket::new)
                .encoder(SyncShopCatalogS2CPacket::toBytes)
                .consumerMainThread(SyncShopCatalogS2CPacket::handle)
                .add();
    }

    public static <MSG> void sendToServer(MSG message) {
        if (INSTANCE != null) {
            INSTANCE.sendToServer(message);
        }
    }

    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        if (INSTANCE != null && player != null) {
            INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }
}