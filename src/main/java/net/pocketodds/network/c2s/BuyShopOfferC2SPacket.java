package net.pocketodds.network.c2s;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.shop.ShopPaymentSource;
import net.pocketodds.shop.ShopService;

import java.util.UUID;
import java.util.function.Supplier;

public class BuyShopOfferC2SPacket {
    private final int containerId;
    private final String offerId;
    private final UUID operationId;
    private final ShopPaymentSource paymentSource;

    public BuyShopOfferC2SPacket(int containerId, String offerId, UUID operationId, ShopPaymentSource paymentSource) {
        this.containerId = containerId;
        this.offerId = offerId != null ? offerId : "";
        this.operationId = operationId != null ? operationId : UUID.randomUUID();
        this.paymentSource = paymentSource != null ? paymentSource : ShopPaymentSource.INVENTORY;
    }

    public BuyShopOfferC2SPacket(int containerId, String offerId, UUID operationId) {
        this(containerId, offerId, operationId, ShopPaymentSource.INVENTORY);
    }

    public BuyShopOfferC2SPacket(FriendlyByteBuf buf) {
        this.containerId = buf.readVarInt();
        this.offerId = buf.readUtf(128);
        this.operationId = buf.readUUID();
        this.paymentSource = ShopPaymentSource.fromOrdinal(buf.readByte());
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeUtf(offerId, 128);
        buf.writeUUID(operationId);
        buf.writeByte(paymentSource.ordinal());
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

    public ShopPaymentSource getPaymentSource() {
        return paymentSource;
    }

    public static void handle(BuyShopOfferC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                JackpotSavedData jackpotData = JackpotSavedData.get(player.serverLevel());
                ShopService.processPurchase(player, msg.containerId, msg.offerId, msg.operationId, msg.paymentSource, jackpotData);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
