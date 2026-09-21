package net.pocketodds.gambling.core;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.data.PouchBalance;
import net.pocketodds.gui.casino.PayoutDestination;
import net.pocketodds.item.ChipItem;
import net.pocketodds.item.ChipTier;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.s2c.CoinPouchSyncS2CPacket;
import net.pocketodds.service.CoinPouchService;
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

        return applyDebitTransition(prep, jackpotData);
    }

    /**
     * Internal transition of 2PC debit state and jackpot contribution.
     */
    public static boolean applyDebitTransition(BetPreparation prep, JackpotSavedData jackpotData) {
        Objects.requireNonNull(prep, "prep must not be null");
        Objects.requireNonNull(jackpotData, "jackpotData must not be null");

        BetSnapshot bet = prep.getBetSnapshot();
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
        return enqueueRewardBundle(jackpotData, UUID.randomUUID(), playerUUID, bundle, PayoutDestination.INVENTORY);
    }

    public static UUID enqueueRewardBundle(JackpotSavedData jackpotData, UUID playerUUID, RewardBundle bundle, PayoutDestination destination) {
        return enqueueRewardBundle(jackpotData, UUID.randomUUID(), playerUUID, bundle, destination);
    }

    public static UUID enqueueRewardBundle(JackpotSavedData jackpotData, UUID txId, UUID playerUUID, RewardBundle bundle) {
        return enqueueRewardBundle(jackpotData, txId, playerUUID, bundle, PayoutDestination.INVENTORY);
    }

    public static UUID enqueueRewardBundle(JackpotSavedData jackpotData, UUID txId, UUID playerUUID, RewardBundle bundle, PayoutDestination destination) {
        Objects.requireNonNull(jackpotData, "jackpotData must not be null");
        Objects.requireNonNull(playerUUID, "playerUUID must not be null");
        if (bundle == null || bundle.isEmpty()) {
            return null;
        }

        UUID effectiveTxId = txId != null ? txId : UUID.randomUUID();
        JackpotSavedData.RewardTransaction tx = JackpotSavedData.RewardTransaction.fromBundle(effectiveTxId, playerUUID, bundle, destination);
        jackpotData.enqueueRewardTransaction(tx);
        return effectiveTxId;
    }

    /**
     * Delivers a single transaction by ID.
     * Honors tx.getPayoutDestination():
     * - If POUCH and a coin pouch is present, chip lines are credited directly into the pouch balance.
     * - Any non-chip lines, or all lines if no pouch is present, are delivered via the delivery sink.
     */
    public static boolean deliverTransaction(UUID transactionId, ServerPlayer player, JackpotSavedData data, RewardDeliverySink sink) {
        ItemStack pouch = (player != null) ? CoinPouchService.findFirstPouch(player) : ItemStack.EMPTY;
        return deliverTransaction(transactionId, player, pouch, data, sink);
    }

    public static boolean deliverTransaction(UUID transactionId, ServerPlayer player, ItemStack pouch, JackpotSavedData data, RewardDeliverySink sink) {
        if (data == null || transactionId == null) return false;
        if (player == null && sink == null && (pouch == null || pouch.isEmpty())) return false;

        JackpotSavedData.RewardTransaction tx = data.getTransaction(transactionId);
        if (tx == null) return true;
        if (tx.isEmpty()) {
            data.removePendingTransaction(transactionId);
            return true;
        }

        if (player != null && (pouch == null || pouch.isEmpty())) {
            pouch = CoinPouchService.findFirstPouch(player);
        }

        PayoutDestination destination = tx.getPayoutDestination();
        boolean pouchChanged = false;
        UUID pouchUUID = null;

        if (destination == PayoutDestination.POUCH && pouch != null && !pouch.isEmpty()) {
            pouchUUID = CoinPouchService.getOrCreatePouchUUID(pouch);
            for (RewardLine line : tx.getLines()) {
                if (!line.isDelivered() && line.getStack().getItem() instanceof ChipItem) {
                    boolean credited = data.creditRewardLineToPouch(pouchUUID, transactionId, line.getLineId(), pouch);
                    if (credited) {
                        pouchChanged = true;
                    }
                }
            }
            if (pouchChanged) {
                PouchBalance updatedBalance = data.getPouchBalance(pouchUUID);
                CoinPouchService.syncStackNbt(pouch, updatedBalance);
                if (player != null) {
                    ModMessages.sendToPlayer(new CoinPouchSyncS2CPacket(
                            pouchUUID,
                            updatedBalance.getCount(ChipTier.COPPER),
                            updatedBalance.getCount(ChipTier.GOLD),
                            updatedBalance.getCount(ChipTier.DIAMOND),
                            updatedBalance.getCount(ChipTier.NETHERITE),
                            updatedBalance.getTotalCredits()
                    ), player);
                }
            }
        }

        // Re-read remaining state of transaction
        tx = data.getTransaction(transactionId);
        if (tx == null || tx.isEmpty()) {
            data.removePendingTransaction(transactionId);
            return true;
        }

        RewardDeliverySink actualSink = (sink != null) ? sink : InventoryUtils::giveOrDrop;
        boolean allRemainingDelivered = true;

        for (RewardLine line : tx.getLines()) {
            if (line.isDelivered()) continue;
            try {
                actualSink.deliver(player, line.getStack());
                data.confirmDeliveredLine(transactionId, line.getLineId());
            } catch (Exception e) {
                LOGGER.error("Pocket Odds: Failed to deliver reward line {} in transaction {} to player {}: {}",
                        line.getLineId(), transactionId, (player != null ? player.getScoreboardName() : tx.getPlayerUUID()), e.getMessage(), e);
                allRemainingDelivered = false;
                break;
            }
        }

        tx = data.getTransaction(transactionId);
        if (allRemainingDelivered && (tx == null || tx.isEmpty())) {
            data.removePendingTransaction(transactionId);
        }

        return allRemainingDelivered;
    }

    /**
     * Delivers pending reward transactions to the player.
     * Honors each transaction's PayoutDestination:
     * - POUCH transactions deposit chips into the pouch (if available) and deliver non-chips to sink.
     * - INVENTORY transactions deliver all lines to sink.
     */
    public static boolean deliverPendingTransactions(UUID playerUUID, ServerPlayer player, JackpotSavedData data, RewardDeliverySink sink) {
        ItemStack pouch = (player != null) ? CoinPouchService.findFirstPouch(player) : ItemStack.EMPTY;
        return deliverPendingTransactions(playerUUID, player, pouch, data, sink);
    }

    public static boolean deliverPendingTransactions(UUID playerUUID, ServerPlayer player, ItemStack pouch, JackpotSavedData data, RewardDeliverySink sink) {
        if (data == null) return false;
        if (player == null && sink == null && (pouch == null || pouch.isEmpty())) return false;
        RewardDeliverySink actualSink = (sink != null) ? sink : InventoryUtils::giveOrDrop;

        List<JackpotSavedData.RewardTransaction> pending = data.getPendingTransactions(playerUUID);
        if (pending.isEmpty()) return true;

        if (player != null && (pouch == null || pouch.isEmpty())) {
            pouch = CoinPouchService.findFirstPouch(player);
        }

        boolean allDelivered = true;
        int deliveredLinesCount = 0;

        for (JackpotSavedData.RewardTransaction tx : pending) {
            int undeliveredBefore = (int) tx.getLines().stream().filter(l -> !l.isDelivered()).count();
            boolean success = deliverTransaction(tx.getTransactionId(), player, pouch, data, actualSink);
            if (success) {
                deliveredLinesCount += undeliveredBefore;
            } else {
                allDelivered = false;
            }
        }

        if (allDelivered && player != null && deliveredLinesCount > 0) {
            player.sendSystemMessage(Component.translatable("pocketodds.reconnect.rewards_delivered").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            FeedbackEffects.playSound(player, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
        }
        return allDelivered;
    }
}
