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
import net.pocketodds.gambling.slot.SlotEvaluator;
import net.pocketodds.gambling.slot.SlotOutcome;
import net.pocketodds.gambling.slot.SlotRollSession;
import net.pocketodds.gambling.slot.SlotSymbol;
import net.pocketodds.gambling.tracker.ActiveRollTracker;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
        // If player holds a chip in offhand, prefer it
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
                    // Also cycle chip tier if wrapped around
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

        if (player.isShiftKeyDown()) {
            cycleBet(stack);
            if (!level.isClientSide) {
                int betCount = getBetCount(stack);
                ChipTier betTier = getBetTier(stack, player);
                if (player instanceof ServerPlayer serverPlayer) {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.slot.bet_changed", betCount, betTier.getColorCode() + betTier.getId()).withStyle(ChatFormatting.YELLOW));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.UI_BUTTON_CLICK.get(), 0.8f, 1.2f);
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            // Check cooldown
            if (serverPlayer.getCooldowns().isOnCooldown(this)) {
                return InteractionResultHolder.fail(stack);
            }

            // Check active session
            if (ActiveRollTracker.hasActiveSession(serverPlayer.getUUID())) {
                FeedbackEffects.sendActionBar(serverPlayer, Component.translatable("pocketodds.slot.already_rolling").withStyle(ChatFormatting.RED));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                return InteractionResultHolder.fail(stack);
            }

            ChipTier betTier = getBetTier(stack, serverPlayer);
            int betCount = getBetCount(stack);

            // Check if player has enough chips
            if (InventoryUtils.countChips(serverPlayer, betTier) < betCount) {
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.not_enough_chips", betCount, betTier.getColorCode() + betTier.getId()).withStyle(ChatFormatting.RED));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                return InteractionResultHolder.fail(stack);
            }

            // Deduct chips
            InventoryUtils.removeChips(serverPlayer, betTier, betCount);

            // Add contribution to jackpot
            long betBaseValue = (long) betTier.getBaseValue() * betCount;
            JackpotSavedData.get(serverPlayer.serverLevel()).addContribution(betBaseValue);

            // Calculate roll outcomes
            SlotSymbol[] symbols = new SlotSymbol[]{
                    SlotSymbol.getRandomSymbol(level.random, PocketOddsConfig.SERVER),
                    SlotSymbol.getRandomSymbol(level.random, PocketOddsConfig.SERVER),
                    SlotSymbol.getRandomSymbol(level.random, PocketOddsConfig.SERVER)
            };
            SlotOutcome outcome = SlotEvaluator.evaluate(symbols, PocketOddsConfig.SERVER);

            // Pre-session Joker rescue: Check if player has Joker and can rescue a losing or disastrous spin
            if (InventoryUtils.hasJoker(serverPlayer)) {
                SlotEvaluator.JokerRescueResult rescue = SlotEvaluator.tryRescueWithJoker(symbols, betCount, PocketOddsConfig.SERVER);
                if (rescue.isRescued()) {
                    InventoryUtils.consumeJoker(serverPlayer);
                    symbols = rescue.getSymbols();
                    outcome = rescue.getOutcome();
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.slot.joker_triggered").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.NOTE_BLOCK_CHIME.get(), 1.0f, 1.4f);
                    FeedbackEffects.spawnParticles(serverPlayer, net.minecraft.core.particles.ParticleTypes.WITCH, 15, 0.4, 0.4, 0.4, 0.1);
                }
            }

            // Apply cooldown
            int cd = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.cooldownTicks.get() : 30;
            serverPlayer.getCooldowns().addCooldown(this, cd);

            // Register session with finalized symbols and outcome
            JackpotSavedData jackpotData = JackpotSavedData.get(serverPlayer.serverLevel());
            ActiveRollTracker.addSession(new SlotRollSession(serverPlayer.getUUID(), betTier, betCount, symbols, outcome), jackpotData);

            // Initial feedback
            FeedbackEffects.playSound(serverPlayer, SoundEvents.NOTE_BLOCK_HAT.get(), 1.0f, 1.0f);
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
        tooltip.add(Component.translatable("tooltip.pocketodds.pocket_slot.offhand_tip").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
