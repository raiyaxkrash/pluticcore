package net.pocketodds.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class VoidDiceItem extends Item {
    private static final int[] BET_AMOUNTS = {1, 8, 32, 64};
    private static final String[] DICE_FACES = {"⚀", "⚁", "⚂", "⚃", "⚄", "⚅"};

    public VoidDiceItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static int getBetCount(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.contains("BetAmount")) ? tag.getInt("BetAmount") : 1;
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
        return (tag != null && tag.contains("BetTier")) ? ChipTier.fromId(tag.getString("BetTier")) : ChipTier.COPPER;
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
                    stack.getOrCreateTag().putString("BetTier", curTier.next().getId());
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
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                int betCount = getBetCount(stack);
                ChipTier betTier = getBetTier(stack, player);
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.dice.bet_changed", betCount, betTier.getColorCode() + betTier.getId()).withStyle(ChatFormatting.YELLOW));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.UI_BUTTON_CLICK.get(), 0.8f, 1.2f);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            if (serverPlayer.getCooldowns().isOnCooldown(this)) {
                return InteractionResultHolder.fail(stack);
            }

            ChipTier betTier = getBetTier(stack, serverPlayer);
            int betCount = getBetCount(stack);

            if (InventoryUtils.countChips(serverPlayer, betTier) < betCount) {
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.not_enough_chips", betCount, betTier.getColorCode() + betTier.getId()).withStyle(ChatFormatting.RED));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                return InteractionResultHolder.fail(stack);
            }

            // Deduct bet
            InventoryUtils.removeChips(serverPlayer, betTier, betCount);

            // Add small contribution to jackpot
            JackpotSavedData.get(serverPlayer.serverLevel()).addContribution((long) betTier.getBaseValue() * betCount);

            // Roll two dice
            int d1 = level.random.nextInt(6) + 1;
            int d2 = level.random.nextInt(6) + 1;
            int sum = d1 + d2;
            boolean isDouble = (d1 == d2);

            String d1Face = DICE_FACES[d1 - 1];
            String d2Face = DICE_FACES[d2 - 1];

            int cd = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.cooldownTicks.get() : 30;
            serverPlayer.getCooldowns().addCooldown(this, cd);

            // Case 1: Snake eyes (1 and 1) -> Disastrous loss
            if (d1 == 1 && d2 == 1) {
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎲 " + d1Face + " | " + d2Face + " ] ")
                                .append(Component.translatable("pocketodds.dice.snake_eyes").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.AMBIENT_CAVE.get(), 1.0f, 0.8f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.SQUID_INK, 20, 0.4, 0.4, 0.4, 0.05);

                serverPlayer.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 120, 0));
                serverPlayer.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 0));

                if (InventoryUtils.hasInsurance(serverPlayer)) {
                    InventoryUtils.consumeInsurance(serverPlayer);
                    double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                    int refund = Math.max(1, (int) Math.round(betCount * rate));
                    InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(betTier.getItem(), refund));
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.insurance.triggered", refund).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                }
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }

            // Case 2: Lucky Seven
            if (sum == 7) {
                double mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.diceLuckySevenPayout.get() : 3.5;
                int payout = (int) Math.round(betCount * mult);
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(betTier.getItem(), payout));

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎲 " + d1Face + " | " + d2Face + " = " + sum + " ] ")
                                .append(Component.translatable("pocketodds.dice.lucky_seven", "+" + payout).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.FIREWORK, 25, 0.5, 0.5, 0.5, 0.1);
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }

            // Case 3: Other Doubles (2-2, 3-3, 4-4, 5-5, 6-6)
            if (isDouble) {
                double mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.diceDoublePayout.get() : 2.5;
                int payout = (int) Math.round(betCount * mult);
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(betTier.getItem(), payout));

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎲 " + d1Face + " | " + d2Face + " ] ")
                                .append(Component.translatable("pocketodds.dice.double_win", "+" + payout).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.HAPPY_VILLAGER, 15, 0.4, 0.4, 0.4, 0.05);
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }

            // Case 4: Sum 11 (Minor Win)
            if (sum == 11) {
                int payout = betCount * 2;
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(betTier.getItem(), payout));

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎲 " + d1Face + " | " + d2Face + " = " + sum + " ] ")
                                .append(Component.translatable("pocketodds.dice.win", "+" + payout).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.PLAYER_LEVELUP, 0.8f, 1.0f);
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }

            // Case 5: Loss
            if (InventoryUtils.hasInsurance(serverPlayer)) {
                InventoryUtils.consumeInsurance(serverPlayer);
                double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                int refund = Math.max(1, (int) Math.round(betCount * rate));
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(betTier.getItem(), refund));
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎲 " + d1Face + " | " + d2Face + " = " + sum + " ] ")
                                .append(Component.translatable("pocketodds.insurance.triggered", refund).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
            } else {
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎲 " + d1Face + " | " + d2Face + " = " + sum + " ] ")
                                .append(Component.translatable("pocketodds.dice.loss").withStyle(ChatFormatting.GRAY)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
            }
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int betCount = getBetCount(stack);
        CompoundTag tag = stack.getTag();
        ChipTier betTier = (tag != null && tag.contains("BetTier")) ? ChipTier.fromId(tag.getString("BetTier")) : ChipTier.COPPER;

        tooltip.add(Component.translatable("tooltip.pocketodds.void_dice.title").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
        tooltip.add(Component.translatable("tooltip.pocketodds.void_dice.bet", betTier.getColorCode() + betCount + " " + betTier.getId()).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.pocketodds.void_dice.rules").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.pocketodds.void_dice.controls_shift").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.pocketodds.void_dice.controls_right").withStyle(ChatFormatting.GREEN));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
