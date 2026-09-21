package net.pocketodds.network.c2s;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.gui.pouch.CoinPouchMenu;
import net.pocketodds.item.ChipTier;
import net.pocketodds.item.CoinPouchItem;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.s2c.CoinPouchSyncS2CPacket;
import net.pocketodds.service.CoinPouchService;

import java.util.UUID;
import java.util.function.Supplier;

public class CoinPouchActionC2SPacket {
    public static final int ACTION_DEPOSIT_ALL = 0;
    public static final int ACTION_WITHDRAW_ALL = 1;
    public static final int ACTION_DEPOSIT_TIER = 2;
    public static final int ACTION_WITHDRAW_TIER = 3;

    private final int containerId;
    private final int action;
    private final int tierOrdinal;
    private final int count;

    public CoinPouchActionC2SPacket(int containerId, int action, int tierOrdinal, int count) {
        this.containerId = containerId;
        this.action = action;
        this.tierOrdinal = tierOrdinal;
        this.count = count;
    }

    public CoinPouchActionC2SPacket(FriendlyByteBuf buf) {
        this.containerId = buf.readInt();
        this.action = buf.readInt();
        this.tierOrdinal = buf.readInt();
        this.count = buf.readInt();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(containerId);
        buf.writeInt(action);
        buf.writeInt(tierOrdinal);
        buf.writeInt(count);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.containerMenu instanceof CoinPouchMenu menu && menu.containerId == containerId) {
                ItemStack pouch = menu.getPouchStack();
                if (pouch != null && !pouch.isEmpty() && pouch.getItem() instanceof CoinPouchItem) {
                    UUID expectedUUID = menu.getPouchUUID();
                    UUID currentUUID = CoinPouchService.getPouchUUID(pouch);
                    if (expectedUUID == null || !expectedUUID.equals(currentUUID)) {
                        return;
                    }
                    ChipTier tier = ChipTier.values()[Math.max(0, Math.min(ChipTier.values().length - 1, tierOrdinal))];
                    int safeCount = Math.max(1, count);

                    switch (action) {
                        case ACTION_DEPOSIT_ALL -> CoinPouchService.depositAllChips(player, pouch);
                        case ACTION_WITHDRAW_ALL -> CoinPouchService.withdrawAllChips(player, pouch);
                        case ACTION_DEPOSIT_TIER -> CoinPouchService.depositChips(player, pouch, tier, safeCount);
                        case ACTION_WITHDRAW_TIER -> CoinPouchService.withdrawChips(player, pouch, tier, safeCount);
                    }

                    // Sync updated pouch state back to client
                    ModMessages.sendToPlayer(new CoinPouchSyncS2CPacket(
                            menu.getPouchUUID(),
                            CoinPouchService.getChipCount(pouch, ChipTier.COPPER),
                            CoinPouchService.getChipCount(pouch, ChipTier.GOLD),
                            CoinPouchService.getChipCount(pouch, ChipTier.DIAMOND),
                            CoinPouchService.getChipCount(pouch, ChipTier.NETHERITE),
                            CoinPouchService.getTotalCredits(pouch)
                    ), player);
                }
            }
        });
        context.setPacketHandled(true);
    }
}