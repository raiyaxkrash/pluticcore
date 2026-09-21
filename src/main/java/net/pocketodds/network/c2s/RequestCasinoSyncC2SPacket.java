package net.pocketodds.network.c2s;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.gui.casino.PocketCasinoMenu;
import net.pocketodds.service.CasinoGameService;

import java.util.function.Supplier;

public class RequestCasinoSyncC2SPacket {
    private final int containerId;

    public RequestCasinoSyncC2SPacket(int containerId) {
        this.containerId = containerId;
    }

    public RequestCasinoSyncC2SPacket(FriendlyByteBuf buf) {
        this.containerId = buf.readInt();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(containerId);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.containerMenu instanceof PocketCasinoMenu menu && menu.containerId == containerId) {
                CasinoGameService.onPlayerOpenCasino(player);
            }
        });
        context.setPacketHandled(true);
    }
}
