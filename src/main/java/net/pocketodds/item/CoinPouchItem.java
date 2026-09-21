package net.pocketodds.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import net.pocketodds.gui.pouch.CoinPouchMenu;
import net.pocketodds.service.CoinPouchService;
import net.pocketodds.util.FeedbackEffects;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public class CoinPouchItem extends Item {
    public CoinPouchItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            int slotIndex = (hand == InteractionHand.MAIN_HAND)
                    ? serverPlayer.getInventory().selected
                    : 40; // offhand slot index in vanilla player inventory

            UUID pouchUUID = CoinPouchService.getOrCreatePouchUUID(stack);
            net.pocketodds.data.JackpotSavedData jackpotData = net.pocketodds.data.JackpotSavedData.get(serverPlayer.serverLevel());
            net.pocketodds.data.PouchBalance balance = jackpotData.getOrCreatePouchBalance(pouchUUID, stack);
            CoinPouchService.syncStackNbt(stack, balance);

            boolean acquired = CoinPouchService.acquirePouchLock(pouchUUID, serverPlayer.getUUID());
            if (!acquired) {
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.pouch.locked_by_other").withStyle(ChatFormatting.RED));
                return InteractionResultHolder.fail(stack);
            }

            NetworkHooks.openScreen(
                    serverPlayer,
                    new SimpleMenuProvider(
                            (id, inv, p) -> new CoinPouchMenu(id, inv, slotIndex),
                            Component.translatable("pocketodds.gui.pouch.title")
                    ),
                    buf -> buf.writeInt(slotIndex)
            );
            net.pocketodds.network.ModMessages.sendToPlayer(new net.pocketodds.network.s2c.CoinPouchSyncS2CPacket(
                    pouchUUID,
                    balance.getCount(ChipTier.COPPER),
                    balance.getCount(ChipTier.GOLD),
                    balance.getCount(ChipTier.DIAMOND),
                    balance.getCount(ChipTier.NETHERITE),
                    balance.getTotalCredits()
            ), serverPlayer);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        long totalCredits = CoinPouchService.getTotalCredits(stack);
        long copper = CoinPouchService.getChipCount(stack, ChipTier.COPPER);
        long gold = CoinPouchService.getChipCount(stack, ChipTier.GOLD);
        long diamond = CoinPouchService.getChipCount(stack, ChipTier.DIAMOND);
        long netherite = CoinPouchService.getChipCount(stack, ChipTier.NETHERITE);

        tooltip.add(Component.translatable("tooltip.pocketodds.pouch.credits", totalCredits).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        tooltip.add(Component.translatable("tooltip.pocketodds.pouch.chips", copper, gold, diamond, netherite).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.pocketodds.pouch.help").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.pocketodds.pouch.cached_notice").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}