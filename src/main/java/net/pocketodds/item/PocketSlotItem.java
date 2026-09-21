package net.pocketodds.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.gambling.core.BetPreparation;
import net.pocketodds.gambling.core.BetSnapshot;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.core.RewardTransactionService;
import net.pocketodds.gambling.itembet.ItemBetConfigEntry;
import net.pocketodds.gambling.itembet.ItemBetRegistry;
import net.pocketodds.gambling.itembet.ItemBetValidator;
import net.pocketodds.gambling.slot.SlotEvaluator;
import net.pocketodds.gambling.slot.SlotOutcome;
import net.pocketodds.gambling.slot.SlotRollSession;
import net.pocketodds.gambling.slot.SlotSymbol;
import net.pocketodds.gambling.tracker.ActiveRollTracker;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

@Deprecated
public class PocketSlotItem extends Item {
    private static final int[] BET_AMOUNTS = {1, 8, 32, 64};

    public PocketSlotItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static int getBetCount(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("BetAmount")) {
            return tag.getInt("BetAmount");
        }
        return 1;
    }

    public static void setBetCount(ItemStack stack, int amount) {
        stack.getOrCreateTag().putInt("BetAmount", amount);
    }

    public static ChipTier getBetTier(ItemStack stack, Player player) {
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.getItem() instanceof ChipItem chipItem) {
            return chipItem.getTier();
        }

        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("BetTier")) {
            return ChipTier.fromId(tag.getString("BetTier"));
        }
        return ChipTier.COPPER;
    }

    public static void setBetTier(ItemStack stack, ChipTier tier) {
        stack.getOrCreateTag().putString("BetTier", tier.getId());
    }

    public static void cycleBet(ItemStack stack) {
        int current = getBetCount(stack);
        int next = 1;
        for (int i = 0; i < BET_AMOUNTS.length; i++) {
            if (BET_AMOUNTS[i] == current) {
                if (i + 1 < BET_AMOUNTS.length) {
                    next = BET_AMOUNTS[i + 1];
                } else {
                    next = BET_AMOUNTS[0];
                    CompoundTag tag = stack.getTag();
                    ChipTier curTier = (tag != null && tag.contains("BetTier")) ? ChipTier.fromId(tag.getString("BetTier")) : ChipTier.COPPER;
                    setBetTier(stack, curTier.next());
                }
                break;
            }
        }
        setBetCount(stack, next);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            net.minecraftforge.network.NetworkHooks.openScreen(
                    serverPlayer,
                    new net.minecraft.world.SimpleMenuProvider(
                            (id, inv, p) -> new net.pocketodds.gui.casino.PocketCasinoMenu(id, inv, net.pocketodds.gui.casino.CasinoCategory.SLOTS),
                            Component.translatable("pocketodds.gui.casino.title")
                    ),
                    buf -> buf.writeInt(net.pocketodds.gui.casino.CasinoCategory.SLOTS.ordinal())
            );
            net.pocketodds.service.CasinoGameService.onPlayerOpenCasino(serverPlayer);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int betCount = getBetCount(stack);
        CompoundTag tag = stack.getTag();
        ChipTier betTier = (tag != null && tag.contains("BetTier")) ? ChipTier.fromId(tag.getString("BetTier")) : ChipTier.COPPER;

        tooltip.add(Component.translatable("tooltip.pocketodds.pocket_slot.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        tooltip.add(Component.translatable("tooltip.pocketodds.pocket_slot.bet", betTier.getColorCode() + betCount + " " + betTier.getId()).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.pocketodds.pocket_slot.controls_shift").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.pocketodds.pocket_slot.controls_right").withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.pocketodds.offhand_item_tip").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
