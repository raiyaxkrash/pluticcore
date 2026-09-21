package net.pocketodds.shop;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.gambling.core.RewardTransactionService;
import net.pocketodds.gui.casino.PocketCasinoMenu;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.s2c.SyncShopCatalogS2CPacket;
import net.pocketodds.registration.ModItems;
import net.pocketodds.util.InventoryUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ShopService {

    public static int getPlayerPrizeTokens(ServerPlayer player) {
        if (player == null) return 0;
        return InventoryUtils.countChips(player, null); // We will count specific Item
    }

    public static int countPrizeTokens(ServerPlayer player) {
        if (player == null) return 0;
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == ModItems.PRIZE_TOKEN.get()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static boolean debitPrizeTokens(ServerPlayer player, int amount) {
        if (player == null || amount <= 0) return false;
        if (countPrizeTokens(player) < amount) return false;

        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == ModItems.PRIZE_TOKEN.get()) {
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
            }
        }
        player.inventoryMenu.broadcastChanges();
        return remaining == 0;
    }

    public static boolean processPurchase(ServerPlayer player, int containerId, String offerId, UUID operationId, JackpotSavedData jackpotData) {
        if (player == null || offerId == null || operationId == null || jackpotData == null) {
            return false;
        }

        // 1. Verify container
        if (player.containerMenu.containerId != containerId || !(player.containerMenu instanceof PocketCasinoMenu)) {
            sendShopError(player, "shop.pocketodds.error.invalid_menu");
            return false;
        }

        // 2. Check operation deduplication
        if (jackpotData.isShopOperationProcessed(operationId)) {
            syncShopToPlayer(player, jackpotData);
            return true;
        }

        // 3. Verify offer
        ShopOffer offer = ShopOfferRegistry.getOffer(offerId);
        if (offer == null || !offer.isEnabled() || !offer.isItemAvailable()) {
            sendShopError(player, "shop.pocketodds.error.unavailable");
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

        // 5. Verify token balance
        int price = offer.getPrice();
        if (countPrizeTokens(player) < price) {
            sendShopError(player, "shop.pocketodds.error.insufficient_tokens");
            return false;
        }

        ItemStack rewardStack = offer.createRewardStack();
        if (rewardStack.isEmpty()) {
            sendShopError(player, "shop.pocketodds.error.unavailable");
            return false;
        }

        // 6. 2PC Phase 1: PREPARED
        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                operationId, player.getUUID(), offer.getOfferId(), price,
                rewardStack, ShopPurchaseTransaction.State.PREPARED, System.currentTimeMillis()
        );
        jackpotData.recordShopPurchase(tx);

        // 7. Debit tokens from inventory
        boolean debited = debitPrizeTokens(player, price);
        if (!debited) {
            tx.setState(ShopPurchaseTransaction.State.PREPARED);
            sendShopError(player, "shop.pocketodds.error.insufficient_tokens");
            return false;
        }

        // 8. 2PC Phase 2: TOKENS_DEBITED
        tx.setState(ShopPurchaseTransaction.State.TOKENS_DEBITED);
        jackpotData.updateShopPurchaseState(operationId, ShopPurchaseTransaction.State.TOKENS_DEBITED);

        // 9. Enqueue RewardTransaction in Outbox with deterministic ID
        UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward_" + operationId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction rewardTx = new JackpotSavedData.RewardTransaction(
                rewardTxId, player.getUUID(), Collections.singletonList(rewardStack)
        );
        jackpotData.enqueueRewardTransaction(rewardTx);

        // 10. Record limit and mark COMMITTED
        jackpotData.incrementPlayerPurchaseCount(player.getUUID(), offer.getOfferId(), offer.getLimitPeriod());
        tx.setState(ShopPurchaseTransaction.State.COMMITTED);
        jackpotData.updateShopPurchaseState(operationId, ShopPurchaseTransaction.State.COMMITTED);

        // 11. Deliver reward via Outbox
        RewardTransactionService.deliverTransaction(rewardTxId, player, ItemStack.EMPTY, jackpotData, InventoryUtils::giveOrDrop);

        // 12. Send success feedback and sync
        player.sendSystemMessage(Component.translatable("shop.pocketodds.success", rewardStack.getHoverName(), rewardStack.getCount())
                .withStyle(ChatFormatting.GREEN));
        syncShopToPlayer(player, jackpotData);
        return true;
    }

    public static void syncShopToPlayer(ServerPlayer player, JackpotSavedData jackpotData) {
        if (player == null || jackpotData == null) return;
        List<ShopOffer> available = ShopOfferRegistry.getAvailableOffers();
        int tokens = countPrizeTokens(player);

        List<SyncShopCatalogS2CPacket.ClientShopEntry> entries = available.stream().map(o -> {
            int used = (o.getPurchaseLimit() > 0)
                    ? jackpotData.getPlayerPurchaseCount(player.getUUID(), o.getOfferId(), o.getLimitPeriod())
                    : 0;
            int remainingLimit = (o.getPurchaseLimit() > 0) ? Math.max(0, o.getPurchaseLimit() - used) : -1;
            boolean canAfford = tokens >= o.getPrice();
            boolean limitOk = (remainingLimit == -1 || remainingLimit > 0);
            return new SyncShopCatalogS2CPacket.ClientShopEntry(
                    o.getOfferId(),
                    o.createRewardStack(),
                    o.getPrice(),
                    o.getCategory().ordinal(),
                    o.getLimitPeriod().ordinal(),
                    remainingLimit,
                    canAfford && limitOk,
                    o.getNameKey(),
                    o.getDescriptionKey()
            );
        }).toList();

        ModMessages.sendToPlayer(new SyncShopCatalogS2CPacket(tokens, entries), player);
    }

    private static void sendShopError(ServerPlayer player, String messageKey) {
        player.sendSystemMessage(Component.translatable(messageKey).withStyle(ChatFormatting.RED));
        if (player.getServer() != null) {
            JackpotSavedData data = JackpotSavedData.get(player.serverLevel());
            syncShopToPlayer(player, data);
        }
    }
}
