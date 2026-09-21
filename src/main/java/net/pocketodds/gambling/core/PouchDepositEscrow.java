package net.pocketodds.gambling.core;

import net.minecraft.nbt.CompoundTag;
import net.pocketodds.item.ChipTier;

import java.util.UUID;

public class PouchDepositEscrow {
    public enum Status {
        PREPARED,
        DEBITED,
        COMMITTED,
        ABORTED
    }

    private final UUID depositId;
    private final UUID playerUUID;
    private final UUID pouchUUID;
    private final ChipTier tier;
    private final int count;
    private final int expectedPlayerChipCount;
    private Status status;
    private final long timestamp;

    public PouchDepositEscrow(UUID depositId, UUID playerUUID, UUID pouchUUID, ChipTier tier, int count, Status status, long timestamp) {
        this(depositId, playerUUID, pouchUUID, tier, count, -1, status, timestamp);
    }

    public PouchDepositEscrow(UUID depositId, UUID playerUUID, UUID pouchUUID, ChipTier tier, int count, int expectedPlayerChipCount, Status status, long timestamp) {
        this.depositId = depositId;
        this.playerUUID = playerUUID;
        this.pouchUUID = pouchUUID;
        this.tier = tier;
        this.count = count;
        this.expectedPlayerChipCount = expectedPlayerChipCount;
        this.status = status;
        this.timestamp = timestamp;
    }

    public UUID getDepositId() { return depositId; }
    public UUID getPlayerUUID() { return playerUUID; }
    public UUID getPouchUUID() { return pouchUUID; }
    public ChipTier getTier() { return tier; }
    public int getCount() { return count; }
    public int getExpectedPlayerChipCount() { return expectedPlayerChipCount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getTimestamp() { return timestamp; }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("DepositId", depositId);
        tag.putUUID("PlayerUUID", playerUUID);
        tag.putUUID("PouchUUID", pouchUUID);
        tag.putString("Tier", tier.name());
        tag.putInt("Count", count);
        tag.putInt("ExpectedPlayerChipCount", expectedPlayerChipCount);
        tag.putString("Status", status.name());
        tag.putLong("Timestamp", timestamp);
        return tag;
    }

    public static PouchDepositEscrow fromNbt(CompoundTag tag) {
        if (tag == null) return null;
        UUID dId = tag.getUUID("DepositId");
        UUID pId = tag.getUUID("PlayerUUID");
        UUID pouchId = tag.getUUID("PouchUUID");
        ChipTier tier = ChipTier.valueOf(tag.getString("Tier"));
        int count = tag.getInt("Count");
        int expectedCount = tag.contains("ExpectedPlayerChipCount") ? tag.getInt("ExpectedPlayerChipCount") : -1;
        Status st = Status.valueOf(tag.getString("Status"));
        long time = tag.getLong("Timestamp");
        return new PouchDepositEscrow(dId, pId, pouchId, tier, count, expectedCount, st, time);
    }
}
