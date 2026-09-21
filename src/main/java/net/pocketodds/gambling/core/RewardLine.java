package net.pocketodds.gambling.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.UUID;

public class RewardLine {
    private final UUID lineId;
    private final ItemStack stack;
    private boolean delivered;

    public RewardLine(ItemStack stack) {
        this(UUID.randomUUID(), stack, false);
    }

    public RewardLine(UUID lineId, ItemStack stack, boolean delivered) {
        this.lineId = lineId != null ? lineId : UUID.randomUUID();
        this.stack = (stack != null && !stack.isEmpty()) ? stack.copy() : ItemStack.EMPTY;
        this.delivered = delivered;
    }

    public UUID getLineId() {
        return lineId;
    }

    public ItemStack getStack() {
        return stack.copy();
    }

    public boolean isDelivered() {
        return delivered;
    }

    public void setDelivered(boolean delivered) {
        this.delivered = delivered;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("LineId", lineId);
        tag.putBoolean("Delivered", delivered);
        if (!stack.isEmpty()) {
            tag.put("Item", stack.save(new CompoundTag()));
        }
        return tag;
    }

    public static RewardLine fromNbt(CompoundTag tag) {
        UUID id = tag.contains("LineId") ? tag.getUUID("LineId") : UUID.randomUUID();
        boolean delivered = tag.getBoolean("Delivered");
        ItemStack item = tag.contains("Item") ? ItemStack.of(tag.getCompound("Item")) : ItemStack.EMPTY;
        return new RewardLine(id, item, delivered);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RewardLine that)) return false;
        return Objects.equals(lineId, that.lineId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lineId);
    }
}
