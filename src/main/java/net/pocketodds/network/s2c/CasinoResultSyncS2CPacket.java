package net.pocketodds.network.s2c;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.slot.SlotSymbol;
import net.pocketodds.gui.casino.PocketCasinoScreen;
import net.pocketodds.service.CasinoGameResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class CasinoResultSyncS2CPacket {
    private final int gameTypeOrdinal;
    private final UUID operationId;
    private final boolean success;
    private final String messageKey;
    private final int[] slotSymbolOrdinals;
    private final int rouletteNumber;
    private final int dice1;
    private final int dice2;
    private final int streak;
    private final int potUnits;
    private final double multiplier;
    private final long wonAmount;
    private final boolean jackpot;
    private final boolean insured;
    private final boolean curse;
    private final int remainingAnimTicks;
    private final List<ItemStack> rewardItems;

    public CasinoResultSyncS2CPacket(CasinoGameResult result) {
        this(result, -1);
    }

    public CasinoResultSyncS2CPacket(CasinoGameResult result, int remainingAnimTicks) {
        this.gameTypeOrdinal = result.getGameType().ordinal();
        this.operationId = result.getOperationId();
        this.success = result.isSuccess();
        this.messageKey = result.getMessageKey();
        SlotSymbol[] symbols = result.getSlotSymbols();
        this.slotSymbolOrdinals = new int[symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            this.slotSymbolOrdinals[i] = symbols[i].ordinal();
        }
        this.rouletteNumber = result.getRouletteNumber();
        this.dice1 = result.getDice1();
        this.dice2 = result.getDice2();
        this.streak = result.getStreak();
        this.potUnits = result.getPotUnits();
        this.multiplier = result.getMultiplier();
        this.wonAmount = result.getWonAmount();
        this.jackpot = result.isJackpot();
        this.insured = result.isInsured();
        this.curse = result.isCurse();
        this.remainingAnimTicks = remainingAnimTicks;

        List<ItemStack> rawRewards = result.getRewardItems();
        this.rewardItems = new ArrayList<>();
        if (rawRewards != null) {
            int limit = Math.min(rawRewards.size(), 8);
            for (int i = 0; i < limit; i++) {
                ItemStack stack = rawRewards.get(i);
                if (stack != null && !stack.isEmpty()) {
                    this.rewardItems.add(stack.copy());
                }
            }
        }
    }

    public CasinoResultSyncS2CPacket(FriendlyByteBuf buf) {
        this.gameTypeOrdinal = buf.readInt();
        this.operationId = buf.readUUID();
        this.success = buf.readBoolean();
        this.messageKey = buf.readUtf(256);
        int symLen = buf.readInt();
        this.slotSymbolOrdinals = new int[symLen];
        for (int i = 0; i < symLen; i++) {
            this.slotSymbolOrdinals[i] = buf.readInt();
        }
        this.rouletteNumber = buf.readInt();
        this.dice1 = buf.readInt();
        this.dice2 = buf.readInt();
        this.streak = buf.readInt();
        this.potUnits = buf.readInt();
        this.multiplier = buf.readDouble();
        this.wonAmount = buf.readLong();
        this.jackpot = buf.readBoolean();
        this.insured = buf.readBoolean();
        this.curse = buf.readBoolean();
        this.remainingAnimTicks = buf.readInt();
        int rewardCount = buf.readInt();
        this.rewardItems = new ArrayList<>(rewardCount);
        for (int i = 0; i < rewardCount; i++) {
            this.rewardItems.add(buf.readItem());
        }
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(gameTypeOrdinal);
        buf.writeUUID(operationId);
        buf.writeBoolean(success);
        buf.writeUtf(messageKey, 256);
        buf.writeInt(slotSymbolOrdinals.length);
        for (int s : slotSymbolOrdinals) {
            buf.writeInt(s);
        }
        buf.writeInt(rouletteNumber);
        buf.writeInt(dice1);
        buf.writeInt(dice2);
        buf.writeInt(streak);
        buf.writeInt(potUnits);
        buf.writeDouble(multiplier);
        buf.writeLong(wonAmount);
        buf.writeBoolean(jackpot);
        buf.writeBoolean(insured);
        buf.writeBoolean(curse);
        buf.writeInt(remainingAnimTicks);
        buf.writeInt(rewardItems.size());
        for (ItemStack item : rewardItems) {
            buf.writeItem(item);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (Minecraft.getInstance().screen instanceof PocketCasinoScreen screen) {
                    screen.handleGameResult(this);
                }
            });
        });
        context.setPacketHandled(true);
    }

    public GameType getGameType() { return GameType.values()[gameTypeOrdinal]; }
    public UUID getOperationId() { return operationId; }
    public boolean isSuccess() { return success; }
    public String getMessageKey() { return messageKey; }
    public int[] getSlotSymbolOrdinals() { return slotSymbolOrdinals; }
    public int getRouletteNumber() { return rouletteNumber; }
    public int getDice1() { return dice1; }
    public int getDice2() { return dice2; }
    public int getStreak() { return streak; }
    public int getPotUnits() { return potUnits; }
    public double getMultiplier() { return multiplier; }
    public long getWonAmount() { return wonAmount; }
    public boolean isJackpot() { return jackpot; }
    public boolean isInsured() { return insured; }
    public boolean isCurse() { return curse; }
    public int getRemainingAnimTicks() { return remainingAnimTicks; }
    public List<ItemStack> getRewardItems() { return java.util.Collections.unmodifiableList(rewardItems); }
}