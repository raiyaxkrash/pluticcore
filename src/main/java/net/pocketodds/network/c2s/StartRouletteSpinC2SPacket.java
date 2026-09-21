package net.pocketodds.network.c2s;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.gui.casino.CasinoCategory;
import net.pocketodds.gui.casino.PocketCasinoMenu;
import net.pocketodds.gui.casino.RouletteBetType;
import net.pocketodds.service.CasinoGameService;

import java.util.UUID;
import java.util.function.Supplier;

public class StartRouletteSpinC2SPacket {
    private final int containerId;
    private final int betTypeOrdinal;
    private final UUID operationId;

    public StartRouletteSpinC2SPacket(int containerId, int betTypeOrdinal, UUID operationId) {
        this.containerId = containerId;
        this.betTypeOrdinal = betTypeOrdinal;
        this.operationId = operationId != null ? operationId : UUID.randomUUID();
    }

    public StartRouletteSpinC2SPacket(FriendlyByteBuf buf) {
        this.containerId = buf.readInt();
        this.betTypeOrdinal = buf.readInt();
        this.operationId = buf.readUUID();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(containerId);
        buf.writeInt(betTypeOrdinal);
        buf.writeUUID(operationId);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.containerMenu instanceof PocketCasinoMenu menu
                    && menu.containerId == containerId
                    && menu.getCurrentCategory() == CasinoCategory.ROULETTE) {
                CasinoGameService.spinRoulette(player, menu, RouletteBetType.fromOrdinal(betTypeOrdinal), operationId);
            }
        });
        context.setPacketHandled(true);
    }
}