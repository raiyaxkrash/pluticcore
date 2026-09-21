package net.pocketodds.service;

import net.minecraft.world.item.ItemStack;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.slot.SlotSymbol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class CasinoGameResult {
    private final GameType gameType;
    private final UUID operationId;
    private final boolean success;
    private final String messageKey;
    private final SlotSymbol[] slotSymbols;
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
    private final List<ItemStack> rewardItems;

    public CasinoGameResult(Builder builder) {
        this.gameType = builder.gameType;
        this.operationId = builder.operationId;
        this.success = builder.success;
        this.messageKey = builder.messageKey != null ? builder.messageKey : "";
        this.slotSymbols = builder.slotSymbols != null ? builder.slotSymbols : new SlotSymbol[0];
        this.rouletteNumber = builder.rouletteNumber;
        this.dice1 = builder.dice1;
        this.dice2 = builder.dice2;
        this.streak = builder.streak;
        this.potUnits = builder.potUnits;
        this.multiplier = builder.multiplier;
        this.wonAmount = builder.wonAmount;
        this.jackpot = builder.jackpot;
        this.insured = builder.insured;
        this.curse = builder.curse;
        this.rewardItems = Collections.unmodifiableList(new ArrayList<>(builder.rewardItems));
    }

    public GameType getGameType() { return gameType; }
    public UUID getOperationId() { return operationId; }
    public boolean isSuccess() { return success; }
    public String getMessageKey() { return messageKey; }
    public SlotSymbol[] getSlotSymbols() { return slotSymbols; }
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
    public List<ItemStack> getRewardItems() { return rewardItems; }
    public List<ItemStack> getRewards() { return rewardItems; }

    public net.minecraft.nbt.CompoundTag toNbt() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("GameType", gameType.name());
        tag.putUUID("OperationId", operationId);
        tag.putBoolean("Success", success);
        tag.putString("MessageKey", messageKey);
        net.minecraft.nbt.ListTag symList = new net.minecraft.nbt.ListTag();
        for (SlotSymbol s : slotSymbols) {
            symList.add(net.minecraft.nbt.StringTag.valueOf(s.name()));
        }
        tag.put("SlotSymbols", symList);
        tag.putInt("RouletteNumber", rouletteNumber);
        tag.putInt("Dice1", dice1);
        tag.putInt("Dice2", dice2);
        tag.putInt("Streak", streak);
        tag.putInt("PotUnits", potUnits);
        tag.putDouble("Multiplier", multiplier);
        tag.putLong("WonAmount", wonAmount);
        tag.putBoolean("Jackpot", jackpot);
        tag.putBoolean("Insured", insured);
        tag.putBoolean("Curse", curse);
        net.minecraft.nbt.ListTag itemsList = new net.minecraft.nbt.ListTag();
        for (ItemStack st : rewardItems) {
            itemsList.add(st.save(new net.minecraft.nbt.CompoundTag()));
        }
        tag.put("Rewards", itemsList);
        return tag;
    }

    public static CasinoGameResult fromNbt(net.minecraft.nbt.CompoundTag tag) {
        if (tag == null) return null;
        try {
            GameType type = GameType.valueOf(tag.getString("GameType"));
            Builder b = new Builder(type);
            if (tag.hasUUID("OperationId")) {
                b.operationId(tag.getUUID("OperationId"));
            }
            b.success(tag.getBoolean("Success"));
            b.messageKey(tag.getString("MessageKey"));
            if (tag.contains("SlotSymbols", net.minecraft.nbt.Tag.TAG_LIST)) {
                net.minecraft.nbt.ListTag symList = tag.getList("SlotSymbols", net.minecraft.nbt.Tag.TAG_STRING);
                SlotSymbol[] syms = new SlotSymbol[symList.size()];
                for (int i = 0; i < symList.size(); i++) {
                    syms[i] = SlotSymbol.valueOf(symList.getString(i));
                }
                b.slotSymbols(syms);
            }
            b.rouletteNumber(tag.getInt("RouletteNumber"));
            b.dice(tag.getInt("Dice1"), tag.getInt("Dice2"));
            b.deck(tag.getInt("Streak"), tag.getInt("PotUnits"));
            b.multiplier(tag.getDouble("Multiplier"));
            b.wonAmount(tag.getLong("WonAmount"));
            b.jackpot(tag.getBoolean("Jackpot"));
            b.insured(tag.getBoolean("Insured"));
            b.curse(tag.getBoolean("Curse"));
            if (tag.contains("Rewards", net.minecraft.nbt.Tag.TAG_LIST)) {
                net.minecraft.nbt.ListTag itemsList = tag.getList("Rewards", net.minecraft.nbt.Tag.TAG_COMPOUND);
                List<ItemStack> list = new ArrayList<>();
                for (int i = 0; i < itemsList.size(); i++) {
                    list.add(ItemStack.of(itemsList.getCompound(i)));
                }
                b.rewards(list);
            }
            return b.build();
        } catch (Exception e) {
            return null;
        }
    }

    public static class Builder {
        private final GameType gameType;
        private UUID operationId = UUID.randomUUID();
        private boolean success = true;
        private String messageKey = "";
        private SlotSymbol[] slotSymbols = new SlotSymbol[0];
        private int rouletteNumber = -1;
        private int dice1 = -1;
        private int dice2 = -1;
        private int streak = 0;
        private int potUnits = 0;
        private double multiplier = 0.0;
        private long wonAmount = 0L;
        private boolean jackpot = false;
        private boolean insured = false;
        private boolean curse = false;
        private List<ItemStack> rewardItems = new ArrayList<>();

        public Builder(GameType gameType) {
            this.gameType = gameType;
        }

        public Builder operationId(UUID opId) { this.operationId = opId; return this; }
        public Builder success(boolean s) { this.success = s; return this; }
        public Builder messageKey(String k) { this.messageKey = k; return this; }
        public Builder slotSymbols(SlotSymbol[] s) { this.slotSymbols = s; return this; }
        public Builder rouletteNumber(int r) { this.rouletteNumber = r; return this; }
        public Builder dice(int d1, int d2) { this.dice1 = d1; this.dice2 = d2; return this; }
        public Builder deck(int streak, int potUnits) { this.streak = streak; this.potUnits = potUnits; return this; }
        public Builder multiplier(double m) { this.multiplier = m; return this; }
        public Builder wonAmount(long a) { this.wonAmount = a; return this; }
        public Builder jackpot(boolean j) { this.jackpot = j; return this; }
        public Builder insured(boolean i) { this.insured = i; return this; }
        public Builder curse(boolean c) { this.curse = c; return this; }
        public Builder rewards(List<ItemStack> r) {
            if (r != null) {
                for (ItemStack st : r) {
                    if (st != null && !st.isEmpty()) this.rewardItems.add(st.copy());
                }
            }
            return this;
        }

        public CasinoGameResult build() {
            return new CasinoGameResult(this);
        }
    }
}