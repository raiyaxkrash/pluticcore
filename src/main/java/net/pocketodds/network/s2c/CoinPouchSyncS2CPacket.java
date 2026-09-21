package net.pocketodds.network.s2c;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.gui.pouch.CoinPouchScreen;

import java.util.UUID;
import java.util.function.Supplier;

public class CoinPouchSyncS2CPacket {
    private final UUID pouchUUID;
    private final long copper;
    private final long gold;
    private final long diamond;
    private final long netherite;
    private final long totalCredits;

    public CoinPouchSyncS2CPacket(UUID pouchUUID, long copper, long gold, long diamond, long netherite, long totalCredits) {
        this.pouchUUID = pouchUUID != null ? pouchUUID : UUID.randomUUID();
        this.copper = copper;
        this.gold = gold;
        this.diamond = diamond;
        this.netherite = netherite;
        this.totalCredits = totalCredits;
    }

    public CoinPouchSyncS2CPacket(FriendlyByteBuf buf) {
        this.pouchUUID = buf.readUUID();
        this.copper = buf.readLong();
        this.gold = buf.readLong();
        this.diamond = buf.readLong();
        this.netherite = buf.readLong();
        this.totalCredits = buf.readLong();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(pouchUUID);
        buf.writeLong(copper);
        buf.writeLong(gold);
        buf.writeLong(diamond);
        buf.writeLong(netherite);
        buf.writeLong(totalCredits);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (Minecraft.getInstance().screen instanceof CoinPouchScreen screen) {
                    screen.updatePouchData(pouchUUID, copper, gold, diamond, netherite, totalCredits);
                }
            });
        });
        context.setPacketHandled(true);
    }

    public UUID getPouchUUID() { return pouchUUID; }
    public long getCopper() { return copper; }
    public long getGold() { return gold; }
    public long getDiamond() { return diamond; }
    public long getNetherite() { return netherite; }
    public long getTotalCredits() { return totalCredits; }
}