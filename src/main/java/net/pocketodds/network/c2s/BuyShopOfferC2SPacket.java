package net.pocketodds.network.c2s;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.shop.ShopService;

import java.util.UUID;
import java.util.function.Supplier;

public class BuyShopOfferC2SPacket {
    private final int containerId;
    private final String offerId;
    private final UUID operationId;

    public BuyShopOfferC2SPacket(int containerId, String offerId, UUID operationId) {
        this.containerId = containerId;
        this.offerId = offerId != null ? offerId : "";
        this.operationId = operationId != null ? operationId : UUID.randomUUID();
    }

    public BuyShopOfferC2SPacket(FriendlyByteBuf buf) {
        this.containerId = buf.readVarInt();
        this.offerId = buf.readUtf(128);
        this.operationId = buf.readUUID();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeUtf(offerId, 128);
        buf.writeUUID(operationId);
    }

    public int getContainerId() {
        return containerId;
    }

    public String getOfferId() {
        return offerId;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public static void handle(BuyShopOfferC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                JackpotSavedData jackpotData = JackpotSavedData.get(player.serverLevel());
                ShopService.processPurchase(player, msg.containerId, msg.offerId, msg.operationId, jackpotData);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
