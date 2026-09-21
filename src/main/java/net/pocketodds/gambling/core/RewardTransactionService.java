package net.pocketodds.gambling.core;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.item.ChipTier;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import net.pocketodds.util.RewardDeliverySink;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class RewardTransactionService {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Phase 1 of 2PC: Registers bet intention as PREPARED in persistent storage.
     * Items have NOT yet been deducted.
     */
    public static BetPreparation prepareBet(ServerPlayer player, GameType gameType, BetSnapshot bet, JackpotSavedData jackpotData) {
        return prepareBet(player, gameType, null, bet, jackpotData);
    }

    public static BetPreparation prepareBet(ServerPlayer player, GameType gameType, UUID associatedId, BetSnapshot bet, JackpotSavedData jackpotData) {
        Objects.requireNonNull(player, "player must not be null");
        return prepareBet(player.getUUID(), gameType, associatedId, bet, jackpotData);
    }

    public static BetPreparation prepareBet(UUID playerUUID, GameType gameType, UUID associatedId, BetSnapshot bet, JackpotSavedData jackpotData) {
        Objects.requireNonNull(playerUUID, "playerUUID must not be null");
        Objects.requireNonNull(gameType, "gameType must not be null");
        Objects.requireNonNull(bet, "bet must not be null");
        Objects.requireNonNull(jackpotData, "jackpotData must not be null");

        BetPreparation prep = new BetPreparation(playerUUID, gameType, bet, associatedId);
        jackpotData.savePreparedBet(prep);
        return prep;
    }

    /**
     * Phase 2 of 2PC: Verifies inventory and atomically debits the bet.
     * Transitions status from PREPARED to DEBITED in persistent storage.
     * Returns true if debited, false if player lacked required items.
     */
    public static boolean debitBet(BetPreparation prep, ServerPlayer player, JackpotSavedData jackpotData) {
        Objects.requireNonNull(prep, "prep must not be null");
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(jackpotData, "jackpotData must not be null");

        BetSnapshot bet = prep.getBetSnapshot();
        if (InventoryUtils.countMatching(player, bet) < bet.getBetCount()) {
            jackpotData.removePreparedBet(prep.getPreparationId());
            return false;
        }

        boolean removed = InventoryUtils.removeMatching(player, bet);
        if (!removed) {
            jackpotData.removePreparedBet(prep.getPreparationId());
            return false;
        }

        // Add contribution to server jackpot pool using basis points
        int basisPoints = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? (int) Math.round(PocketOddsConfig.SERVER.jackpotContributionRate.get() * 10_000.0)
                : 500;
        jackpotData.addContributionBasisPoints(bet.getTotalCreditValue(), basisPoints);

        prep.setStatus(BetPreparation.PreparationStatus.DEBITED);
        jackpotData.savePreparedBet(prep);
        return true;
    }

    /**
     * Phase 3 of 2PC: Commits the bet once game session is successfully registered or instant game outcome persisted.
     */
    public static void commitBet(BetPreparation prep, JackpotSavedData jackpotData) {
        if (prep != null && jackpotData != null) {
            prep.setStatus(BetPreparation.PreparationStatus.COMMITTED);
            jackpotData.removePreparedBet(prep.getPreparationId());
        }
    }

    /**
     * Rollback procedure: Safely converts a DEBITED bet into a persistent outbox refund transaction.
     * Does not require an online player or immediate delivery.
     */
    public static void rollbackBet(BetPreparation prep, JackpotSavedData jackpotData) {
        if (prep == null || jackpotData == null) return;
        if (prep.getStatus() == BetPreparation.PreparationStatus.DEBITED) {
            BetSnapshot bet = prep.getBetSnapshot();
            List<ItemStack> refundStacks = new ArrayList<>();
            if (bet.isChipBet()) {
                refundStacks.addAll(ChipUtils.splitChips(bet.getChipTier().getItem(), bet.getBetCount()));
            } else {
                int maxStack = bet.getItemPrototype().getMaxStackSize();
                int remaining = bet.getBetCount();
                while (remaining > 0) {
                    int take = Math.min(remaining, maxStack);
                    ItemStack s = bet.getItemPrototype().copy();
                    s.setCount(take);
                    refundStacks.add(s);
                    remaining -= take;
                }
            }

            JackpotSavedData.RewardTransaction tx = new JackpotSavedData.RewardTransaction(
                    UUID.randomUUID(), prep.getPlayerUUID(), refundStacks
            );
            jackpotData.enqueueRewardTransaction(tx);
            prep.setStatus(BetPreparation.PreparationStatus.REFUND_QUEUED);
            jackpotData.removePreparedBet(prep.getPreparationId());
            LOGGER.info("Pocket Odds: Rolled back debited bet {} for player {} into outbox refund transaction {}.",
                    prep.getPreparationId(), prep.getPlayerUUID(), tx.getTransactionId());
        } else {
            // If it was only PREPARED, no items were deducted -> just discard
            jackpotData.removePreparedBet(prep.getPreparationId());
        }
    }

    /**
     * Enqueues a RewardBundle into the persistent transactional outbox.
     */
    public static UUID enqueueRewardBundle(JackpotSavedData jackpotData, UUID playerUUID, RewardBundle bundle) {
        return enqueueRewardBundle(jackpotData, UUID.randomUUID(), playerUUID, bundle);
    }

    public static UUID enqueueRewardBundle(JackpotSavedData jackpotData, UUID txId, UUID playerUUID, RewardBundle bundle) {
        Objects.requireNonNull(jackpotData, "jackpotData must not be null");
        Objects.requireNonNull(playerUUID, "playerUUID must not be null");
        if (bundle == null || bundle.isEmpty()) {
            return null;
        }

        UUID effectiveTxId = txId != null ? txId : UUID.randomUUID();
        List<ItemStack> items = new ArrayList<>(bundle.getItems());

        if (bundle.isJackpot() && bundle.getJackpotCredits() > 0L) {
            items.addAll(ChipUtils.convertAmountToChips(bundle.getJackpotCredits()));
        }

        JackpotSavedData.RewardTransaction tx = new JackpotSavedData.RewardTransaction(
                effectiveTxId, playerUUID, items, bundle.isJackpot(), bundle.getJackpotCredits(), false
        );
        jackpotData.enqueueRewardTransaction(tx);
        return effectiveTxId;
    }

    /**
     * Delivers pending reward transactions to the player line-by-line via the delivery sink.
     * Each line is confirmed individually via confirmDeliveredLine.
     */
    public static boolean deliverPendingTransactions(UUID playerUUID, ServerPlayer player, JackpotSavedData data, RewardDeliverySink sink) {
        if (data == null) return false;
        if (player == null && sink == null) return false;
        RewardDeliverySink actualSink = (sink != null) ? sink : InventoryUtils::giveOrDrop;

        List<JackpotSavedData.RewardTransaction> pending = data.getPendingTransactions(playerUUID);
        if (pending.isEmpty()) return true;

        boolean allDelivered = true;
        for (JackpotSavedData.RewardTransaction tx : pending) {
            boolean txFailed = false;
            for (RewardLine line : tx.getLines()) {
                if (line.isDelivered()) continue;
                try {
                    actualSink.deliver(player, line.getStack());
                    data.confirmDeliveredLine(tx.getTransactionId(), line.getLineId());
                } catch (Exception e) {
                    LOGGER.error("Failed to deliver reward line {} in transaction {} to player {}: {}",
                            line.getLineId(), tx.getTransactionId(), (player != null ? player.getScoreboardName() : playerUUID), e.getMessage(), e);
                    txFailed = true;
                    allDelivered = false;
                    break;
                }
            }
            if (!txFailed && tx.isEmpty()) {
                data.removePendingTransaction(tx.getTransactionId());
            }
        }

        if (allDelivered && player != null) {
            player.sendSystemMessage(Component.translatable("pocketodds.reconnect.rewards_delivered").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            FeedbackEffects.playSound(player, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
        }
        return allDelivered;
    }
}
