package net.pocketodds.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class ShopPurchaseTransaction {

    public enum State {
        PREPARED,
        TOKENS_DEBITED,
        REWARD_QUEUED,
        COMMITTED,
        REFUND_QUEUED
    }

    private final UUID operationId;
    private final UUID playerUUID;
    private final String offerId;
    private final int price;
    private final ItemStack rewardStack;
    private State state;
    private final long timestamp;

    public ShopPurchaseTransaction(UUID operationId, UUID playerUUID, String offerId, int price, ItemStack rewardStack, State state, long timestamp) {
        this.operationId = operationId;
        this.playerUUID = playerUUID;
        this.offerId = offerId;
        this.price = price;
        this.rewardStack = rewardStack != null ? rewardStack.copy() : ItemStack.EMPTY;
        this.state = state != null ? state : State.PREPARED;
        this.timestamp = timestamp;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getOfferId() {
        return offerId;
    }

    public int getPrice() {
        return price;
    }

    public ItemStack getRewardStack() {
        return rewardStack;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("OperationId", operationId);
        tag.putUUID("PlayerUUID", playerUUID);
        tag.putString("OfferId", offerId);
        tag.putInt("Price", price);
        if (!rewardStack.isEmpty()) {
            tag.put("RewardStack", rewardStack.save(new CompoundTag()));
        }
        tag.putString("State", state.name());
        tag.putLong("Timestamp", timestamp);
        return tag;
    }

    public static ShopPurchaseTransaction fromNbt(CompoundTag tag) {
        UUID opId = tag.contains("OperationId") ? tag.getUUID("OperationId") : UUID.randomUUID();
        UUID playerUUID = tag.contains("PlayerUUID") ? tag.getUUID("PlayerUUID") : UUID.randomUUID();
        String offerId = tag.getString("OfferId");
        int price = tag.getInt("Price");
        ItemStack rewardStack = tag.contains("RewardStack") ? ItemStack.of(tag.getCompound("RewardStack")) : ItemStack.EMPTY;
        State state;
        try {
            state = State.valueOf(tag.getString("State"));
        } catch (Exception e) {
            state = State.PREPARED;
        }
        long timestamp = tag.getLong("Timestamp");
        return new ShopPurchaseTransaction(opId, playerUUID, offerId, price, rewardStack, state, timestamp);
    }
}
