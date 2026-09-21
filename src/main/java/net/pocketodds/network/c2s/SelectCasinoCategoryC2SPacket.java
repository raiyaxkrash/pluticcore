package net.pocketodds.network.c2s;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.gui.casino.CasinoCategory;
import net.pocketodds.gui.casino.PocketCasinoMenu;

import java.util.function.Supplier;

public class SelectCasinoCategoryC2SPacket {
    private final int containerId;
    private final int categoryOrdinal;

    public SelectCasinoCategoryC2SPacket(int containerId, int categoryOrdinal) {
        this.containerId = containerId;
        this.categoryOrdinal = categoryOrdinal;
    }

    public SelectCasinoCategoryC2SPacket(FriendlyByteBuf buf) {
        this.containerId = buf.readInt();
        this.categoryOrdinal = buf.readInt();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(containerId);
        buf.writeInt(categoryOrdinal);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.containerMenu instanceof PocketCasinoMenu menu && menu.containerId == containerId) {
                menu.setCurrentCategory(CasinoCategory.fromOrdinal(categoryOrdinal));
                net.pocketodds.service.CasinoGameService.syncCasinoState(player);
            }
        });
        context.setPacketHandled(true);
    }
}