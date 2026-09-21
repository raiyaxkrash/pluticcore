package net.pocketodds.gambling.tracker;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.pocketodds.PocketOdds;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.gambling.slot.SlotRollSession;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = PocketOdds.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ActiveRollTracker {
    private static final Map<UUID, SlotRollSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    public static boolean hasActiveSession(UUID uuid) {
        return ACTIVE_SESSIONS.containsKey(uuid);
    }

    public static Map<UUID, SlotRollSession> getActiveSessions() {
        return Collections.unmodifiableMap(ACTIVE_SESSIONS);
    }

    public static void addSession(SlotRollSession session, JackpotSavedData jackpotData) {
        ACTIVE_SESSIONS.put(session.getPlayerUUID(), session);
        if (jackpotData != null) {
            jackpotData.saveActiveSession(session.getPlayerUUID(), session.toNbt());
        }
    }

    public static void clear() {
        ACTIVE_SESSIONS.clear();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        clear();
        MinecraftServer server = event.getServer();
        JackpotSavedData jackpotData = JackpotSavedData.get(server.overworld());
        Map<UUID, CompoundTag> savedSessions = jackpotData.getActiveSessions();

        for (Map.Entry<UUID, CompoundTag> entry : savedSessions.entrySet()) {
            try {
                SlotRollSession session = SlotRollSession.fromNbt(entry.getValue(), PocketOddsConfig.SERVER);
                ACTIVE_SESSIONS.put(entry.getKey(), session);
            } catch (Exception e) {
                PocketOdds.LOGGER.error("Failed to restore saved slot session for player " + entry.getKey(), e);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        // Gracefully finalize any pending sessions so bets and rewards are not lost!
        for (SlotRollSession session : ACTIVE_SESSIONS.values()) {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(session.getPlayerUUID());
                session.finalizeOutcome(server, player);
            } catch (Exception e) {
                PocketOdds.LOGGER.error("Error finalizing slot session on server stop", e);
            }
        }
        clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server == null || ACTIVE_SESSIONS.isEmpty()) return;

        Iterator<Map.Entry<UUID, SlotRollSession>> it = ACTIVE_SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SlotRollSession> entry = it.next();
            SlotRollSession session = entry.getValue();
            session.tick(server);
            if (session.isFinished()) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            JackpotSavedData data = JackpotSavedData.get(player.serverLevel());
            data.auditAndResolvePendingDeposits(player);
            deliverPendingTransactions(player, data, InventoryUtils::giveOrDrop);
        }
    }

    public static boolean deliverPendingTransactions(UUID playerUUID, @org.jetbrains.annotations.Nullable ServerPlayer player, JackpotSavedData data, net.pocketodds.util.RewardDeliverySink sink) {
        return net.pocketodds.gambling.core.RewardTransactionService.deliverPendingTransactions(playerUUID, player, data, sink);
    }

    public static boolean deliverPendingTransactions(ServerPlayer player, JackpotSavedData data, net.pocketodds.util.RewardDeliverySink sink) {
        if (player == null) return false;
        return deliverPendingTransactions(player.getUUID(), player, data, sink);
    }
}
