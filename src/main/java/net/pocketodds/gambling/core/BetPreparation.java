package net.pocketodds.gambling.core;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

public class BetPreparation {
    public enum PreparationStatus {
        PREPARED,
        DEBITED,
        COMMITTED,
        REFUND_QUEUED
    }

    private final UUID preparationId;
    private final UUID playerUUID;
    private final GameType gameType;
    private final BetSnapshot betSnapshot;
    private final long timestamp;
    private PreparationStatus status;
    private UUID associatedId; // Linked session ID (rollId / sessionId) or outcome transaction ID

    public BetPreparation(UUID playerUUID, GameType gameType, BetSnapshot betSnapshot) {
        this(UUID.randomUUID(), playerUUID, gameType, betSnapshot, System.currentTimeMillis(), PreparationStatus.PREPARED, null);
    }

    public BetPreparation(UUID playerUUID, GameType gameType, BetSnapshot betSnapshot, UUID associatedId) {
        this(UUID.randomUUID(), playerUUID, gameType, betSnapshot, System.currentTimeMillis(), PreparationStatus.PREPARED, associatedId);
    }

    public BetPreparation(UUID preparationId, UUID playerUUID, GameType gameType, BetSnapshot betSnapshot, long timestamp, PreparationStatus status) {
        this(preparationId, playerUUID, gameType, betSnapshot, timestamp, status, null);
    }

    public BetPreparation(UUID preparationId, UUID playerUUID, GameType gameType, BetSnapshot betSnapshot, long timestamp, PreparationStatus status, UUID associatedId) {
        this.preparationId = preparationId != null ? preparationId : UUID.randomUUID();
        this.playerUUID = Objects.requireNonNull(playerUUID, "playerUUID must not be null");
        this.gameType = Objects.requireNonNull(gameType, "gameType must not be null");
        this.betSnapshot = Objects.requireNonNull(betSnapshot, "betSnapshot must not be null");
        this.timestamp = timestamp;
        this.status = status != null ? status : PreparationStatus.PREPARED;
        this.associatedId = associatedId;
    }

    public UUID getPreparationId() {
        return preparationId;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public GameType getGameType() {
        return gameType;
    }

    public BetSnapshot getBetSnapshot() {
        return betSnapshot;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public PreparationStatus getStatus() {
        return status;
    }

    public void setStatus(PreparationStatus status) {
        this.status = status;
    }

    public UUID getAssociatedId() {
        return associatedId;
    }

    public void setAssociatedId(UUID associatedId) {
        this.associatedId = associatedId;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PreparationId", preparationId);
        tag.putUUID("PlayerUUID", playerUUID);
        tag.putString("GameType", gameType.name());
        tag.put("BetSnapshot", betSnapshot.toNbt());
        tag.putLong("Timestamp", timestamp);
        tag.putString("Status", status.name());
        if (associatedId != null) {
            tag.putUUID("AssociatedId", associatedId);
        }
        return tag;
    }

    public static BetPreparation fromNbt(CompoundTag tag) {
        UUID prepId = tag.contains("PreparationId") ? tag.getUUID("PreparationId") : UUID.randomUUID();
        UUID playerUUID = tag.getUUID("PlayerUUID");
        GameType gameType = tag.contains("GameType") ? GameType.valueOf(tag.getString("GameType")) : GameType.SLOT;
        BetSnapshot bet = BetSnapshot.fromNbt(tag.getCompound("BetSnapshot"));
        long time = tag.contains("Timestamp") ? tag.getLong("Timestamp") : System.currentTimeMillis();
        PreparationStatus st = tag.contains("Status") ? PreparationStatus.valueOf(tag.getString("Status")) : PreparationStatus.PREPARED;
        UUID assocId = tag.contains("AssociatedId") ? tag.getUUID("AssociatedId") : null;
        return new BetPreparation(prepId, playerUUID, gameType, bet, time, st, assocId);
    }
}
