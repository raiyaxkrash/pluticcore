package net.pocketodds.shop;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.data.PouchBalance;
import net.pocketodds.gambling.core.RewardTransactionService;
import net.pocketodds.gui.casino.PocketCasinoMenu;
import net.pocketodds.item.ChipTier;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.s2c.SyncShopCatalogS2CPacket;
import net.pocketodds.service.CoinPouchService;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.InventoryUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class ShopService {

    public static class PaymentPlan {
        public final Map<ChipTier, Integer> invDebits = new EnumMap<>(ChipTier.class);
        public final Map<ChipTier, Long> pouchDebits = new EnumMap<>(ChipTier.class);
        public long totalDebitedCredits = 0L;
        public long changeCredits = 0L;
        public List<ItemStack> changeStacks = new ArrayList<>();
    }

    private static final ChipTier[] TIERS_DESCENDING = new ChipTier[]{
            ChipTier.NETHERITE,
            ChipTier.DIAMOND,
            ChipTier.GOLD,
            ChipTier.COPPER
    };

    private static java.util.function.BiFunction<ServerPlayer, String, Boolean> stageChecker = null;

    public static void setStageChecker(java.util.function.BiFunction<ServerPlayer, String, Boolean> checker) {
        stageChecker = checker;
    }

    public static boolean hasStage(ServerPlayer player, String stage) {
        if (stage == null || stage.trim().isEmpty()) {
            return true;
        }
        if (player == null) {
            return false;
        }
        String s = stage.trim();
        if (stageChecker != null) {
            Boolean res = stageChecker.apply(player, s);
            if (res != null) return res;
        }
        // 1. Check player scoreboard tags
        if (player.getTags().contains(s) || player.getTags().contains("stage:" + s) || player.getTags().contains("gamestage:" + s)) {
            return true;
        }
        // 2. Reflection check for GameStageHelper
        try {
            Class<?> helperClass = Class.forName("net.darkhax.gamestages.GameStageHelper");
            java.lang.reflect.Method hasStageMethod = helperClass.getMethod("hasStage", net.minecraft.world.entity.player.Player.class, String.class);
            Object result = hasStageMethod.invoke(null, player, s);
            if (result instanceof Boolean b) {
                return b;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isStageSatisfied(ServerPlayer player, String minStage, String maxStage) {
        if (player == null) return false;
        if (minStage != null && !minStage.trim().isEmpty()) {
            if (!hasStage(player, minStage)) {
                return false;
            }
        }
        if (maxStage != null && !maxStage.trim().isEmpty()) {
            if (hasStage(player, maxStage)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isAdvancementSatisfied(ServerPlayer player, String requiredAdvancement) {
        if (requiredAdvancement == null || requiredAdvancement.trim().isEmpty()) {
            return true;
        }
        if (player == null || player.getServer() == null) {
            return false;
        }
        ResourceLocation advId = ResourceLocation.tryParse(requiredAdvancement.trim());
        if (advId == null) {
            return false;
        }
        net.minecraft.advancements.Advancement adv = player.getServer().getAdvancements().getAdvancement(advId);
        if (adv == null) {
            return false;
        }
        return player.getAdvancements().getOrStartProgress(adv).isDone();
    }

    public static PaymentPlan calculatePaymentPlan(ServerPlayer player, ItemStack pouch, PouchBalance pouchBalance,
                                                   ShopPaymentSource source, long priceCredits) {
        if (player == null || priceCredits <= 0 || priceCredits > ShopOffer.MAX_PRICE_CREDITS) {
            return null;
        }

        Map<ChipTier, Long> invAvail = new EnumMap<>(ChipTier.class);
        for (ChipTier tier : ChipTier.values()) {
            invAvail.put(tier, (long) InventoryUtils.countChips(player, tier));
        }
        long invTotal = CoinPouchService.getInventoryChipCredits(player);

        Map<ChipTier, Long> pouchAvail = new EnumMap<>(ChipTier.class);
        long pouchTotal = 0L;
        if (pouchBalance != null) {
            for (ChipTier tier : ChipTier.values()) {
                pouchAvail.put(tier, pouchBalance.getCount(tier));
            }
            pouchTotal = pouchBalance.getTotalCredits();
        } else if (pouch != null && !pouch.isEmpty()) {
            for (ChipTier tier : ChipTier.values()) {
                pouchAvail.put(tier, CoinPouchService.getChipCount(pouch, tier));
            }
            pouchTotal = CoinPouchService.getTotalCredits(pouch);
        }

        ShopPaymentSource effectiveSource = source != null ? source : ShopPaymentSource.INVENTORY;

        PaymentPlan plan = new PaymentPlan();

        switch (effectiveSource) {
            case INVENTORY -> {
                if (invTotal < priceCredits) return null;
                Map<ChipTier, Long> debits = computeTierDebits(invAvail, priceCredits);
                if (debits == null) return null;
                for (Map.Entry<ChipTier, Long> e : debits.entrySet()) {
                    plan.invDebits.put(e.getKey(), e.getValue().intValue());
                }
            }
            case POUCH -> {
                if (pouchTotal < priceCredits) return null;
                Map<ChipTier, Long> debits = computeTierDebits(pouchAvail, priceCredits);
                if (debits == null) return null;
                plan.pouchDebits.putAll(debits);
            }
            case INVENTORY_THEN_POUCH -> {
                if (Long.MAX_VALUE - invTotal < pouchTotal && (invTotal + pouchTotal) < 0) {
                    // Safe overflow check
                } else if (invTotal + pouchTotal < priceCredits) {
                    return null;
                }
                if (invTotal >= priceCredits) {
                    Map<ChipTier, Long> debits = computeTierDebits(invAvail, priceCredits);
                    if (debits == null) return null;
                    for (Map.Entry<ChipTier, Long> e : debits.entrySet()) {
                        plan.invDebits.put(e.getKey(), e.getValue().intValue());
                    }
                } else {
                    // Drain all inventory
                    for (Map.Entry<ChipTier, Long> e : invAvail.entrySet()) {
                        if (e.getValue() > 0) {
                            plan.invDebits.put(e.getKey(), e.getValue().intValue());
                        }
                    }
                    long remainder = priceCredits - invTotal;
                    Map<ChipTier, Long> pouchPart = computeTierDebits(pouchAvail, remainder);
                    if (pouchPart == null) return null;
                    plan.pouchDebits.putAll(pouchPart);
                }
            }
            case POUCH_THEN_INVENTORY -> {
                if (Long.MAX_VALUE - pouchTotal < invTotal && (pouchTotal + invTotal) < 0) {
                    // Safe overflow check
                } else if (pouchTotal + invTotal < priceCredits) {
                    return null;
                }
                if (pouchTotal >= priceCredits) {
                    Map<ChipTier, Long> debits = computeTierDebits(pouchAvail, priceCredits);
                    if (debits == null) return null;
                    plan.pouchDebits.putAll(debits);
                } else {
                    // Drain all pouch
                    for (Map.Entry<ChipTier, Long> e : pouchAvail.entrySet()) {
                        if (e.getValue() > 0) {
                            plan.pouchDebits.put(e.getKey(), e.getValue());
                        }
                    }
                    long remainder = priceCredits - pouchTotal;
                    Map<ChipTier, Long> invPart = computeTierDebits(invAvail, remainder);
                    if (invPart == null) return null;
                    for (Map.Entry<ChipTier, Long> e : invPart.entrySet()) {
                        plan.invDebits.put(e.getKey(), e.getValue().intValue());
                    }
                }
            }
        }

        // Calculate total credits debited
        long totalDebited = 0L;
        for (Map.Entry<ChipTier, Integer> e : plan.invDebits.entrySet()) {
            totalDebited += (long) e.getValue() * e.getKey().getBaseValue();
        }
        for (Map.Entry<ChipTier, Long> e : plan.pouchDebits.entrySet()) {
            totalDebited += e.getValue() * e.getKey().getBaseValue();
        }

        if (totalDebited < priceCredits) {
            return null;
        }

        plan.totalDebitedCredits = totalDebited;
        plan.changeCredits = totalDebited - priceCredits;
        if (plan.changeCredits > 0) {
            plan.changeStacks = ChipUtils.convertAmountToChips(plan.changeCredits);
        }

        return plan;
    }

    private static Map<ChipTier, Long> computeTierDebits(Map<ChipTier, Long> available, long targetCredits) {
        if (available == null || targetCredits <= 0) return null;

        Map<ChipTier, Long> debits = new EnumMap<>(ChipTier.class);
        long remaining = targetCredits;

        // Phase 1: Greedy take without exceeding remaining
        for (ChipTier tier : TIERS_DESCENDING) {
            long avail = available.getOrDefault(tier, 0L);
            long need = remaining / tier.getBaseValue();
            long take = Math.min(need, avail);
            if (take > 0) {
                debits.put(tier, take);
                remaining -= take * tier.getBaseValue();
            }
        }

        // Phase 2: If remaining > 0, find smallest available single chip >= remaining
        if (remaining > 0) {
            ChipTier chosenTier = null;
            for (int i = TIERS_DESCENDING.length - 1; i >= 0; i--) {
                ChipTier tier = TIERS_DESCENDING[i];
                long left = available.getOrDefault(tier, 0L) - debits.getOrDefault(tier, 0L);
                if (left > 0 && tier.getBaseValue() >= remaining) {
                    chosenTier = tier;
                    break;
                }
            }

            if (chosenTier != null) {
                debits.put(chosenTier, debits.getOrDefault(chosenTier, 0L) + 1L);
                remaining = 0L;
            } else {
                // Fallback: take from smallest available tiers until covered
                for (int i = TIERS_DESCENDING.length - 1; i >= 0; i--) {
                    ChipTier tier = TIERS_DESCENDING[i];
                    long left = available.getOrDefault(tier, 0L) - debits.getOrDefault(tier, 0L);
                    while (left > 0 && remaining > 0) {
                        debits.put(tier, debits.getOrDefault(tier, 0L) + 1L);
                        remaining -= tier.getBaseValue();
                        left--;
                    }
                    if (remaining <= 0) break;
                }
            }
        }

        if (remaining > 0) {
            return null; // Insufficient chips to cover target
        }
        return debits;
    }

    public static boolean processPurchase(ServerPlayer player, int containerId, String offerId, UUID operationId, JackpotSavedData jackpotData) {
        return processPurchase(player, containerId, offerId, operationId, ShopPaymentSource.INVENTORY, jackpotData);
    }

    public static synchronized boolean processPurchase(ServerPlayer player, int containerId, String offerId, UUID operationId,
                                                       ShopPaymentSource paymentSource, JackpotSavedData jackpotData) {
        if (player == null || offerId == null || operationId == null || jackpotData == null) {
            return false;
        }

        // 1. Verify container menu
        if (player.containerMenu.containerId != containerId || !(player.containerMenu instanceof PocketCasinoMenu casinoMenu)) {
            sendShopError(player, "shop.pocketodds.error.invalid_menu");
            return false;
        }

        // 2. Check operation deduplication and resume existing operations: any registered operationId is used
        if (jackpotData.isShopOperationProcessed(operationId)) {
            ShopPurchaseTransaction existingTx = jackpotData.getShopPurchase(operationId);
            if (existingTx != null) {
                if (!existingTx.getPlayerUUID().equals(player.getUUID())) {
                    sendShopError(player, "shop.pocketodds.error.unauthorized");
                    return false;
                }
                return resumeOrCompleteExistingOperation(player, existingTx, jackpotData);
            }
            syncShopToPlayer(player, jackpotData);
            return false;
        }

        // 3. Verify offer from server registry
        ShopOffer offer = ShopOfferRegistry.getOffer(offerId);
        if (offer == null || !offer.isEnabled() || !offer.isItemAvailable()) {
            sendShopError(player, "shop.pocketodds.error.unavailable");
            return false;
        }

        long priceCredits = offer.getPriceCredits();
        if (priceCredits < 1L || priceCredits > ShopOffer.MAX_PRICE_CREDITS) {
            sendShopError(player, "shop.pocketodds.error.invalid_price");
            return false;
        }

        // 4. Verify limit
        if (offer.getPurchaseLimit() > 0) {
            int currentPurchases = jackpotData.getPlayerPurchaseCount(player.getUUID(), offer.getOfferId(), offer.getLimitPeriod());
            if (currentPurchases >= offer.getPurchaseLimit()) {
                sendShopError(player, "shop.pocketodds.error.limit_reached");
                return false;
            }
        }

        // 5. Verify requiredAdvancement
        if (!isAdvancementSatisfied(player, offer.getRequiredAdvancement())) {
            sendShopError(player, "shop.pocketodds.error.advancement_required");
            return false;
        }

        // 5.5 Verify minStage and maxStage
        if (!isStageSatisfied(player, offer.getMinStage(), offer.getMaxStage())) {
            sendShopError(player, "shop.pocketodds.error.stage_required");
            return false;
        }

        // 6. Find pouch and lock
        ItemStack pouch = CoinPouchService.findFirstPouch(player);
        UUID pouchUUID = (pouch != null && !pouch.isEmpty()) ? CoinPouchService.getOrCreatePouchUUID(pouch) : null;
        PouchBalance pouchBalance = pouchUUID != null ? jackpotData.getOrCreatePouchBalance(pouchUUID, pouch) : null;

        // 7. Calculate exact payment plan
        PaymentPlan plan = calculatePaymentPlan(player, pouch, pouchBalance, paymentSource, priceCredits);
        if (plan == null) {
            sendShopError(player, "shop.pocketodds.error.insufficient_funds");
            return false;
        }

        ItemStack rewardStack = offer.createRewardStack();
        if (rewardStack.isEmpty()) {
            sendShopError(player, "shop.pocketodds.error.unavailable");
            return false;
        }

        // 8. 2PC Phase 1: PREPARED
        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                operationId, player.getUUID(), offer.getOfferId(), priceCredits,
                rewardStack, paymentSource,
                plan.invDebits, plan.pouchDebits, plan.changeCredits, plan.changeStacks,
                offer.getLimitPeriod(), false,
                ShopPurchaseTransaction.State.PREPARED, System.currentTimeMillis()
        );
        jackpotData.recordShopPurchase(tx);

        // 9. 2PC Phase 2: Perform Debit
        boolean debitFailed = false;

        if (paymentSource == ShopPaymentSource.POUCH_THEN_INVENTORY) {
            // Debit from pouch first
            if (pouchUUID != null && !plan.pouchDebits.isEmpty()) {
                boolean ok = jackpotData.debitPouchChips(pouchUUID, plan.pouchDebits);
                if (ok) {
                    for (Map.Entry<ChipTier, Long> e : plan.pouchDebits.entrySet()) {
                        if (e.getValue() > 0) {
                            tx.recordActualPouchDebit(e.getKey(), e.getValue());
                        }
                    }
                    jackpotData.recordShopPurchase(tx);
                    CoinPouchService.syncStackNbt(pouch, jackpotData.getPouchBalance(pouchUUID));
                } else {
                    debitFailed = true;
                }
            }

            // Debit from inventory ONLY if pouch debit succeeded
            if (!debitFailed && !plan.invDebits.isEmpty()) {
                for (Map.Entry<ChipTier, Integer> entry : plan.invDebits.entrySet()) {
                    ChipTier tier = entry.getKey();
                    int count = entry.getValue();
                    if (count <= 0) continue;
                    boolean ok = InventoryUtils.removeChips(player, tier, count);
                    if (ok) {
                        tx.recordActualInventoryDebit(tier, count);
                        jackpotData.recordShopPurchase(tx);
                    } else {
                        debitFailed = true;
                        break;
                    }
                }
            }
        } else {
            // For INVENTORY, POUCH, INVENTORY_THEN_POUCH:
            // Debit from inventory first
            if (!plan.invDebits.isEmpty()) {
                for (Map.Entry<ChipTier, Integer> entry : plan.invDebits.entrySet()) {
                    ChipTier tier = entry.getKey();
                    int count = entry.getValue();
                    if (count <= 0) continue;
                    boolean ok = InventoryUtils.removeChips(player, tier, count);
                    if (ok) {
                        tx.recordActualInventoryDebit(tier, count);
                        jackpotData.recordShopPurchase(tx);
                    } else {
                        debitFailed = true;
                        break;
                    }
                }
            }

            // Debit from pouch ONLY if inventory debit succeeded
            if (!debitFailed && pouchUUID != null && !plan.pouchDebits.isEmpty()) {
                boolean ok = jackpotData.debitPouchChips(pouchUUID, plan.pouchDebits);
                if (ok) {
                    for (Map.Entry<ChipTier, Long> e : plan.pouchDebits.entrySet()) {
                        if (e.getValue() > 0) {
                            tx.recordActualPouchDebit(e.getKey(), e.getValue());
                        }
                    }
                    jackpotData.recordShopPurchase(tx);
                    CoinPouchService.syncStackNbt(pouch, jackpotData.getPouchBalance(pouchUUID));
                } else {
                    debitFailed = true;
                }
            }
        }

        if (debitFailed) {
            // Terminate further debits and refund actually debited chips immediately via Outbox
            if (tx.hasActualDebits()) {
                tx.setState(ShopPurchaseTransaction.State.REFUND_QUEUED);
                jackpotData.recordShopPurchase(tx);

                UUID refundTxId = UUID.nameUUIDFromBytes(("shop_refund:" + operationId.toString()).getBytes(StandardCharsets.UTF_8));
                if (jackpotData.getTransaction(refundTxId) == null && !jackpotData.isReceiptCompleted(refundTxId)) {
                    List<ItemStack> refundStacks = tx.createActualRefundStacks();
                    if (!refundStacks.isEmpty()) {
                        JackpotSavedData.RewardTransaction refundTx = new JackpotSavedData.RewardTransaction(
                                refundTxId, tx.getPlayerUUID(), refundStacks
                        );
                        jackpotData.enqueueRewardTransaction(refundTx);
                        RewardTransactionService.deliverTransaction(refundTxId, player, pouch, jackpotData, InventoryUtils::giveOrDrop);
                    }
                }
            } else {
                tx.setState(ShopPurchaseTransaction.State.CANCELLED);
                jackpotData.recordShopPurchase(tx);
            }
            sendShopError(player, "shop.pocketodds.error.insufficient_funds");
            return false;
        }

        // 10. Mark PAYMENT_DEBITED
        tx.setState(ShopPurchaseTransaction.State.PAYMENT_DEBITED);
        jackpotData.recordShopPurchase(tx);

        // 11. 2PC Phase 3: Enqueue Reward and Change in Outbox with deterministic IDs
        UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + operationId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction rewardTx = new JackpotSavedData.RewardTransaction(
                rewardTxId, player.getUUID(), Collections.singletonList(rewardStack)
        );
        jackpotData.enqueueRewardTransaction(rewardTx);

        UUID changeTxId = null;
        if (plan.changeCredits > 0 && !plan.changeStacks.isEmpty()) {
            changeTxId = UUID.nameUUIDFromBytes(("shop_change:" + operationId.toString()).getBytes(StandardCharsets.UTF_8));
            JackpotSavedData.RewardTransaction changeTx = new JackpotSavedData.RewardTransaction(
                    changeTxId, player.getUUID(), plan.changeStacks
            );
            jackpotData.enqueueRewardTransaction(changeTx);
        }

        tx.setState(ShopPurchaseTransaction.State.REWARD_QUEUED);

        // 12. 2PC Phase 4: Record Limit and Mark COMMITTED
        if (!tx.isLimitRecorded()) {
            jackpotData.incrementPlayerPurchaseCount(player.getUUID(), offer.getOfferId(), offer.getLimitPeriod());
            tx.setLimitRecorded(true);
        }
        tx.setState(ShopPurchaseTransaction.State.COMMITTED);
        jackpotData.updateShopPurchaseState(operationId, ShopPurchaseTransaction.State.COMMITTED);

        // 13. Deliver reward and change via Outbox
        RewardTransactionService.deliverTransaction(rewardTxId, player, pouch, jackpotData, InventoryUtils::giveOrDrop);
        if (changeTxId != null) {
            RewardTransactionService.deliverTransaction(changeTxId, player, pouch, jackpotData, InventoryUtils::giveOrDrop);
        }

        // 14. Feedback and Synchronization
        net.minecraft.network.chat.MutableComponent msg = Component.translatable("shop.pocketodds.success", rewardStack.getHoverName(), rewardStack.getCount());
        if (plan.changeCredits > 0) {
            msg = Component.literal("").append(msg).append(" ")
                    .append(Component.translatable("shop.pocketodds.change_received", plan.changeCredits));
        }
        player.sendSystemMessage(msg.withStyle(ChatFormatting.GREEN));

        player.containerMenu.broadcastChanges();
        syncShopToPlayer(player, jackpotData);
        return true;
    }

    public static void syncShopToPlayer(ServerPlayer player, JackpotSavedData jackpotData) {
        if (player == null || jackpotData == null) return;
        List<ShopOffer> available = ShopOfferRegistry.getAvailableOffers();

        ItemStack pouch = CoinPouchService.findFirstPouch(player);
        long pouchCredits = 0L;
        if (pouch != null && !pouch.isEmpty()) {
            UUID pouchUUID = CoinPouchService.getOrCreatePouchUUID(pouch);
            PouchBalance balance = jackpotData.getPouchBalance(pouchUUID);
            pouchCredits = balance != null ? balance.getTotalCredits() : CoinPouchService.getTotalCredits(pouch);
        }
        long invCredits = CoinPouchService.getInventoryChipCredits(player);
        long totalCredits = invCredits + pouchCredits;

        final long finalPouchCredits = pouchCredits;
        List<SyncShopCatalogS2CPacket.ClientShopEntry> entries = available.stream().map(o -> {
            int used = (o.getPurchaseLimit() > 0)
                    ? jackpotData.getPlayerPurchaseCount(player.getUUID(), o.getOfferId(), o.getLimitPeriod())
                    : 0;
            int remainingLimit = (o.getPurchaseLimit() > 0) ? Math.max(0, o.getPurchaseLimit() - used) : -1;
            boolean advancementOk = isAdvancementSatisfied(player, o.getRequiredAdvancement());
            boolean stageOk = isStageSatisfied(player, o.getMinStage(), o.getMaxStage());
            String reqStage = "";
            if (!isStageSatisfied(player, o.getMinStage(), "")) {
                reqStage = o.getMinStage();
            } else if (!isStageSatisfied(player, "", o.getMaxStage())) {
                reqStage = "max:" + o.getMaxStage();
            }
            boolean canAfford = totalCredits >= o.getPriceCredits();
            boolean limitOk = (remainingLimit == -1 || remainingLimit > 0);
            return new SyncShopCatalogS2CPacket.ClientShopEntry(
                    o.getOfferId(),
                    o.createRewardStack(),
                    o.getPriceCredits(),
                    o.getCategory().ordinal(),
                    o.getLimitPeriod().ordinal(),
                    remainingLimit,
                    canAfford && limitOk && advancementOk && stageOk,
                    advancementOk,
                    stageOk,
                    reqStage,
                    o.getNameKey(),
                    o.getDescriptionKey()
            );
        }).toList();

        ModMessages.sendToPlayer(new SyncShopCatalogS2CPacket(finalPouchCredits, entries), player);
    }

    public static boolean resumeOrCompleteExistingOperation(ServerPlayer player,
                                                             ShopPurchaseTransaction tx,
                                                             JackpotSavedData jackpotData) {
        if (!tx.getPlayerUUID().equals(player.getUUID())) {
            sendShopError(player, "shop.pocketodds.error.unauthorized");
            return false;
        }

        ItemStack pouch = CoinPouchService.findFirstPouch(player);
        UUID opId = tx.getOperationId();

        switch (tx.getState()) {
            case COMMITTED -> {
                // Ensure outbox deliveries if any pending lines remained undelivered
                UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
                RewardTransactionService.deliverTransaction(rewardTxId, player, pouch, jackpotData, InventoryUtils::giveOrDrop);

                if (tx.getChangeCredits() > 0) {
                    UUID changeTxId = UUID.nameUUIDFromBytes(("shop_change:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
                    RewardTransactionService.deliverTransaction(changeTxId, player, pouch, jackpotData, InventoryUtils::giveOrDrop);
                }
                syncShopToPlayer(player, jackpotData);
                return true;
            }
            case PAYMENT_DEBITED, REWARD_QUEUED -> {
                UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
                if (jackpotData.getTransaction(rewardTxId) == null && !jackpotData.isReceiptCompleted(rewardTxId) && !tx.getRewardStack().isEmpty()) {
                    JackpotSavedData.RewardTransaction rewardTx = new JackpotSavedData.RewardTransaction(
                            rewardTxId, tx.getPlayerUUID(), Collections.singletonList(tx.getRewardStack())
                    );
                    jackpotData.enqueueRewardTransaction(rewardTx);
                }

                UUID changeTxId = null;
                if (tx.getChangeCredits() > 0 && !tx.getChangeStacks().isEmpty()) {
                    changeTxId = UUID.nameUUIDFromBytes(("shop_change:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
                    if (jackpotData.getTransaction(changeTxId) == null && !jackpotData.isReceiptCompleted(changeTxId)) {
                        JackpotSavedData.RewardTransaction changeTx = new JackpotSavedData.RewardTransaction(
                                changeTxId, tx.getPlayerUUID(), tx.getChangeStacks()
                        );
                        jackpotData.enqueueRewardTransaction(changeTx);
                    }
                }

                if (!tx.isLimitRecorded()) {
                    jackpotData.incrementPlayerPurchaseCount(tx.getPlayerUUID(), tx.getOfferId(), tx.getLimitPeriod());
                    tx.setLimitRecorded(true);
                }
                tx.setState(ShopPurchaseTransaction.State.COMMITTED);
                jackpotData.recordShopPurchase(tx);

                RewardTransactionService.deliverTransaction(rewardTxId, player, pouch, jackpotData, InventoryUtils::giveOrDrop);
                if (changeTxId != null) {
                    RewardTransactionService.deliverTransaction(changeTxId, player, pouch, jackpotData, InventoryUtils::giveOrDrop);
                }
                syncShopToPlayer(player, jackpotData);
                return true;
            }
            case REFUND_QUEUED -> {
                UUID refundTxId = UUID.nameUUIDFromBytes(("shop_refund:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
                if (jackpotData.getTransaction(refundTxId) == null && !jackpotData.isReceiptCompleted(refundTxId)) {
                    List<ItemStack> refundStacks = tx.createActualRefundStacks();
                    if (!refundStacks.isEmpty()) {
                        JackpotSavedData.RewardTransaction refundTx = new JackpotSavedData.RewardTransaction(
                                refundTxId, tx.getPlayerUUID(), refundStacks
                        );
                        jackpotData.enqueueRewardTransaction(refundTx);
                    }
                }
                RewardTransactionService.deliverTransaction(refundTxId, player, pouch, jackpotData, InventoryUtils::giveOrDrop);
                syncShopToPlayer(player, jackpotData);
                sendShopError(player, "shop.pocketodds.error.insufficient_funds");
                return false;
            }
            case PREPARED -> {
                if (tx.hasActualDebits()) {
                    tx.setState(ShopPurchaseTransaction.State.REFUND_QUEUED);
                    jackpotData.recordShopPurchase(tx);

                    UUID refundTxId = UUID.nameUUIDFromBytes(("shop_refund:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
                    if (jackpotData.getTransaction(refundTxId) == null && !jackpotData.isReceiptCompleted(refundTxId)) {
                        List<ItemStack> refundStacks = tx.createActualRefundStacks();
                        if (!refundStacks.isEmpty()) {
                            JackpotSavedData.RewardTransaction refundTx = new JackpotSavedData.RewardTransaction(
                                    refundTxId, tx.getPlayerUUID(), refundStacks
                            );
                            jackpotData.enqueueRewardTransaction(refundTx);
                        }
                    }
                    RewardTransactionService.deliverTransaction(refundTxId, player, pouch, jackpotData, InventoryUtils::giveOrDrop);
                    syncShopToPlayer(player, jackpotData);
                    sendShopError(player, "shop.pocketodds.error.insufficient_funds");
                    return false;
                } else {
                    tx.setState(ShopPurchaseTransaction.State.CANCELLED);
                    jackpotData.recordShopPurchase(tx);
                    syncShopToPlayer(player, jackpotData);
                    sendShopError(player, "shop.pocketodds.error.insufficient_funds");
                    return false;
                }
            }
            case CANCELLED -> {
                syncShopToPlayer(player, jackpotData);
                sendShopError(player, "shop.pocketodds.error.transaction_failed");
                return false;
            }
        }
        return false;
    }

    private static void sendShopError(ServerPlayer player, String messageKey) {
        player.sendSystemMessage(Component.translatable(messageKey).withStyle(ChatFormatting.RED));
        if (player.getServer() != null) {
            JackpotSavedData data = JackpotSavedData.get(player.serverLevel());
            syncShopToPlayer(player, data);
        }
    }
}
