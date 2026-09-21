package net.pocketodds.gambling.core;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

public class DeckSession {
    public enum Status {
        ACTIVE,
        CASHOUT_COMMITTED,
        BUSTED
    }

    private final UUID sessionId;
    private final UUID playerUUID;
    private final BetSnapshot initialBet;
    private int streak;
    private int potUnits;
    private boolean active;
    private Status status;

    public DeckSession(UUID playerUUID, BetSnapshot initialBet) {
        this(UUID.randomUUID(), playerUUID, initialBet, 1, 1, true, Status.ACTIVE);
    }

    public DeckSession(UUID sessionId, UUID playerUUID, BetSnapshot initialBet, int streak, int potUnits, boolean active) {
        this(sessionId, playerUUID, initialBet, streak, potUnits, active, Status.ACTIVE);
    }

    public DeckSession(UUID sessionId, UUID playerUUID, BetSnapshot initialBet, int streak, int potUnits, boolean active, Status status) {
        this.sessionId = sessionId != null ? sessionId : UUID.randomUUID();
        this.playerUUID = Objects.requireNonNull(playerUUID, "playerUUID must not be null");
        this.initialBet = Objects.requireNonNull(initialBet, "initialBet must not be null");
        this.streak = streak;
        this.potUnits = potUnits;
        this.active = active;
        this.status = status != null ? status : Status.ACTIVE;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public BetSnapshot getInitialBet() {
        return initialBet;
    }

    public int getStreak() {
        return streak;
    }

    public void setStreak(int streak) {
        this.streak = streak;
    }

    public int getPotUnits() {
        return potUnits;
    }

    public void setPotUnits(int potUnits) {
        this.potUnits = potUnits;
    }

    public boolean isActive() {
        return status == Status.ACTIVE && active && potUnits > 0;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status != null ? status : Status.ACTIVE;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("SessionId", sessionId);
        tag.putUUID("PlayerUUID", playerUUID);
        tag.put("InitialBet", initialBet.toNbt());
        tag.putInt("Streak", streak);
        tag.putInt("PotUnits", potUnits);
        tag.putBoolean("Active", active);
        tag.putString("Status", status.name());
        return tag;
    }

    public static DeckSession fromNbt(CompoundTag tag) {
        UUID sId = tag.contains("SessionId") ? tag.getUUID("SessionId") : UUID.randomUUID();
        UUID pId = tag.getUUID("PlayerUUID");
        BetSnapshot bet = BetSnapshot.fromNbt(tag.getCompound("InitialBet"));
        int streak = tag.getInt("Streak");
        int pot = tag.getInt("PotUnits");
        boolean active = tag.contains("Active") ? tag.getBoolean("Active") : true;
        Status st = tag.contains("Status") ? Status.valueOf(tag.getString("Status")) : Status.ACTIVE;
        return new DeckSession(sId, pId, bet, streak, pot, active, st);
    }
}
