package net.pocketodds.gambling.core;

import net.minecraft.nbt.CompoundTag;
import net.pocketodds.service.CasinoGameResult;

import java.util.UUID;

public class CasinoGameSession {
    private final UUID playerUUID;
    private final UUID operationId;
    private final GameType gameType;
    private final CasinoGameResult result;
    private final long gameTimestamp;
    private final int animDurationTicks;

    public CasinoGameSession(UUID playerUUID, UUID operationId, GameType gameType, CasinoGameResult result, long gameTimestamp, int animDurationTicks) {
        this.playerUUID = playerUUID;
        this.operationId = operationId;
        this.gameType = gameType;
        this.result = result;
        this.gameTimestamp = gameTimestamp;
        this.animDurationTicks = animDurationTicks;
    }

    public UUID getPlayerUUID() { return playerUUID; }
    public UUID getOperationId() { return operationId; }
    public GameType getGameType() { return gameType; }
    public CasinoGameResult getResult() { return result; }
    public long getGameTimestamp() { return gameTimestamp; }
    public int getAnimDurationTicks() { return animDurationTicks; }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        if (playerUUID != null) tag.putUUID("PlayerUUID", playerUUID);
        if (operationId != null) tag.putUUID("OperationId", operationId);
        if (gameType != null) tag.putString("GameType", gameType.name());
        tag.putLong("Timestamp", gameTimestamp);
        tag.putInt("AnimDurationTicks", animDurationTicks);
        if (result != null) tag.put("Result", result.toNbt());
        return tag;
    }

    public static CasinoGameSession fromNbt(CompoundTag tag) {
        if (tag == null) return null;
        UUID pId = tag.hasUUID("PlayerUUID") ? tag.getUUID("PlayerUUID") : null;
        UUID opId = tag.hasUUID("OperationId") ? tag.getUUID("OperationId") : null;
        GameType type = tag.contains("GameType") ? GameType.valueOf(tag.getString("GameType")) : GameType.SLOT;
        long time = tag.getLong("Timestamp");
        int animTicks = tag.getInt("AnimDurationTicks");
        CasinoGameResult res = tag.contains("Result") ? CasinoGameResult.fromNbt(tag.getCompound("Result")) : null;
        return new CasinoGameSession(pId, opId, type, res, time, animTicks);
    }
}
