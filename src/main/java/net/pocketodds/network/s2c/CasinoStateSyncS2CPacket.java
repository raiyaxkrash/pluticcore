package net.pocketodds.network.s2c;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.gui.casino.PocketCasinoScreen;

import java.util.function.Supplier;

public class CasinoStateSyncS2CPacket {
    private final long jackpotPool;
    private final long pouchCredits;
    private final long invCredits;
    private final int streak;
    private final int potUnits;
    private final int[] recentRouletteHistory;

    public CasinoStateSyncS2CPacket(long jackpotPool, long pouchCredits, long invCredits, int streak, int potUnits, int[] recentRouletteHistory) {
        this.jackpotPool = jackpotPool;
        this.pouchCredits = pouchCredits;
        this.invCredits = invCredits;
        this.streak = streak;
        this.potUnits = potUnits;
        this.recentRouletteHistory = recentRouletteHistory != null ? recentRouletteHistory : new int[0];
    }

    public CasinoStateSyncS2CPacket(FriendlyByteBuf buf) {
        this.jackpotPool = buf.readLong();
        this.pouchCredits = buf.readLong();
        this.invCredits = buf.readLong();
        this.streak = buf.readInt();
        this.potUnits = buf.readInt();
        int len = buf.readInt();
        this.recentRouletteHistory = new int[len];
        for (int i = 0; i < len; i++) {
            this.recentRouletteHistory[i] = buf.readInt();
        }
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeLong(jackpotPool);
        buf.writeLong(pouchCredits);
        buf.writeLong(invCredits);
        buf.writeInt(streak);
        buf.writeInt(potUnits);
        buf.writeInt(recentRouletteHistory.length);
        for (int val : recentRouletteHistory) {
            buf.writeInt(val);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (Minecraft.getInstance().screen instanceof PocketCasinoScreen screen) {
                    screen.updateCasinoState(jackpotPool, pouchCredits, invCredits, streak, potUnits, recentRouletteHistory);
                }
            });
        });
        context.setPacketHandled(true);
    }

    public long getJackpotPool() { return jackpotPool; }
    public long getPouchCredits() { return pouchCredits; }
    public long getInvCredits() { return invCredits; }
    public int getStreak() { return streak; }
    public int getPotUnits() { return potUnits; }
    public int[] getRecentRouletteHistory() { return recentRouletteHistory; }
}