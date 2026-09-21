package net.pocketodds.network.c2s;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.gui.casino.BetFundingSource;
import net.pocketodds.gui.casino.PayoutDestination;
import net.pocketodds.gui.casino.PocketCasinoMenu;
import net.pocketodds.gui.casino.RouletteBetType;
import net.pocketodds.item.ChipTier;

import java.util.function.Supplier;

public class UpdateBetSelectionC2SPacket {
    private final int containerId;
    private final int fundingSourceOrdinal;
    private final int chipTierOrdinal;
    private final int betCount;
    private final int rouletteBetTypeOrdinal;
    private final int payoutDestOrdinal;

    public UpdateBetSelectionC2SPacket(int containerId, int fundingSource, int chipTier, int betCount, int rouletteBetType, int payoutDest) {
        this.containerId = containerId;
        this.fundingSourceOrdinal = fundingSource;
        this.chipTierOrdinal = chipTier;
        this.betCount = betCount;
        this.rouletteBetTypeOrdinal = rouletteBetType;
        this.payoutDestOrdinal = payoutDest;
    }

    public UpdateBetSelectionC2SPacket(FriendlyByteBuf buf) {
        this.containerId = buf.readInt();
        this.fundingSourceOrdinal = buf.readInt();
        this.chipTierOrdinal = buf.readInt();
        this.betCount = buf.readInt();
        this.rouletteBetTypeOrdinal = buf.readInt();
        this.payoutDestOrdinal = buf.readInt();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(containerId);
        buf.writeInt(fundingSourceOrdinal);
        buf.writeInt(chipTierOrdinal);
        buf.writeInt(betCount);
        buf.writeInt(rouletteBetTypeOrdinal);
        buf.writeInt(payoutDestOrdinal);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.containerMenu instanceof PocketCasinoMenu menu && menu.containerId == containerId) {
                menu.setBetFundingSource(BetFundingSource.fromOrdinal(fundingSourceOrdinal));
                menu.setSelectedChipTier(ChipTier.values()[Math.max(0, Math.min(ChipTier.values().length - 1, chipTierOrdinal))]);
                menu.setBetCount(Math.max(1, Math.min(64, betCount)));
                menu.setRouletteBetType(RouletteBetType.fromOrdinal(rouletteBetTypeOrdinal));
                menu.setPayoutDestination(PayoutDestination.fromOrdinal(payoutDestOrdinal));
            }
        });
        context.setPacketHandled(true);
    }
}