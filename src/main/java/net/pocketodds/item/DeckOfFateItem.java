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
import net.pocketodds.registration.ModItems;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class DeckOfFateItem extends Item {

    public DeckOfFateItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static int getStreak(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.contains("Streak")) ? tag.getInt("Streak") : 0;
    }

    public static void setStreak(ItemStack stack, int streak) {
        stack.getOrCreateTag().putInt("Streak", streak);
    }

    public static int getPot(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.contains("AccumulatedPot")) ? tag.getInt("AccumulatedPot") : 0;
    }

    public static void setPot(ItemStack stack, int pot) {
        stack.getOrCreateTag().putInt("AccumulatedPot", pot);
    }

    /**
     * Retrieves the locked-in bet tier of an active round from NBT.
     * During an active streak or when cashing out, this MUST be used
     * to prevent switching offhand chips to netherite!
     */
    public static ChipTier getLockedRoundTier(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.contains("BetTier")) ? ChipTier.fromId(tag.getString("BetTier")) : ChipTier.COPPER;
    }

    /**
     * Determines initial bet tier only when beginning a brand-new round (streak == 0).
     */
    public static ChipTier determineInitialTier(ItemStack stack, Player player) {
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.getItem() instanceof ChipItem chipItem) {
            return chipItem.getTier();
        }
        return getLockedRoundTier(stack);
    }

    public static void setBetTier(ItemStack stack, ChipTier tier) {
        stack.getOrCreateTag().putString("BetTier", tier.getId());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // Shift + Right Click = Cash Out!
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }

            if (player instanceof ServerPlayer serverPlayer) {
                int pot = getPot(stack);
                int streak = getStreak(stack);

                // FIX #1: Use the locked-in round tier from NBT, NOT player's current offhand!
                ChipTier tier = getLockedRoundTier(stack);

                if (pot <= 0) {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.deck.empty_pot").withStyle(ChatFormatting.YELLOW));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
                }

                // Give accumulated pot to player in the locked-in tier
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(tier.getItem(), pot));
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.cashed_out", pot + " " + tier.getId(), streak).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.FIREWORK, 20, 0.4, 0.4, 0.4, 0.1);

                // Reset streak and pot
                setPot(stack, 0);
                setStreak(stack, 0);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        // Regular Right Click = Push luck (draw next card)
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            if (serverPlayer.getCooldowns().isOnCooldown(this)) {
                return InteractionResultHolder.fail(stack);
            }

            int streak = getStreak(stack);
            int pot = getPot(stack);
            ChipTier tier;

            // Starting a new round
            if (streak == 0 || pot <= 0) {
                // Determine initial tier from offhand or saved setting
                tier = determineInitialTier(stack, serverPlayer);

                if (InventoryUtils.countChips(serverPlayer, tier) < 1) {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.not_enough_chips", 1, tier.getColorCode() + tier.getId()).withStyle(ChatFormatting.RED));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                    return InteractionResultHolder.fail(stack);
                }

                InventoryUtils.removeChips(serverPlayer, tier, 1);
                pot = 1;
                streak = 1;
                // FIX #1: Lock in the round tier in NBT
                setBetTier(stack, tier);
                JackpotSavedData.get(serverPlayer.serverLevel()).addContribution(tier.getBaseValue());
            } else {
                // Ongoing streak: ALWAYS use the locked-in tier!
                tier = getLockedRoundTier(stack);
            }

            serverPlayer.getCooldowns().addCooldown(this, 15);

            // FIX #2: Rebalanced probability distribution for RTP <= 95%
            int roll = level.random.nextInt(100);

            if (roll < 35) {
                // CURSE CARD (35% chance to bust)
                if (InventoryUtils.hasInsurance(serverPlayer)) {
                    InventoryUtils.consumeInsurance(serverPlayer);
                    double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                    int refund = Math.max(1, (int) Math.round(pot * rate));
                    InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(tier.getItem(), refund));

                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.deck.curse_insured", refund).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
                } else {
                    InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(ModItems.CURSED_CARD.get(), 1));
                    serverPlayer.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 0));
                    serverPlayer.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, 0));

                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.deck.curse_lost").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.WITHER_HURT, 0.8f, 1.2f);
                    FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.SMOKE, 25, 0.4, 0.4, 0.4, 0.05);
                }

                setPot(stack, 0);
                setStreak(stack, 0);

            } else if (roll < 70) {
                // Neutral Patience Card (35% chance): Pot stays same, streak advances
                streak++;
                setStreak(stack, streak);

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_patience", pot, streak).withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.BOOK_PAGE_TURN, 1.0f, 1.0f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.ENCHANT, 10, 0.3, 0.3, 0.3, 0.05);

            } else if (roll < 88) {
                // Card of Fortune (18% chance)
                double mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.deckFortuneMultiplier.get() : 1.25;
                pot = (pot <= 1) ? 2 : (int) Math.round(pot * mult);
                streak++;
                setPot(stack, pot);
                setStreak(stack, streak);

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_fortune", pot, streak).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.HAPPY_VILLAGER, 10, 0.3, 0.3, 0.3, 0.05);

            } else if (roll < 94) {
                // Card of Riches (6% chance)
                double mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.deckRichesMultiplier.get() : 1.5;
                pot = (pot <= 1) ? 2 : (int) Math.round(pot * mult);
                streak++;
                setPot(stack, pot);
                setStreak(stack, streak);

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_riches", pot, streak).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.FIREWORK, 15, 0.4, 0.4, 0.4, 0.1);

            } else if (roll < 97) {
                // Joker's Favor (3% chance)
                pot += 1;
                streak++;
                setPot(stack, pot);
                setStreak(stack, streak);
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(ModItems.JOKER.get(), 1));

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_joker", pot, streak).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.NOTE_BLOCK_CHIME.get(), 1.0f, 1.4f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.WITCH, 15, 0.4, 0.4, 0.4, 0.1);

            } else {
                // Guardian Shield (3% chance)
                pot += 1;
                streak++;
                setPot(stack, pot);
                setStreak(stack, streak);
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(ModItems.INSURANCE.get(), 1));

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_guardian", pot, streak).withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.TOTEM_OF_UNDYING, 10, 0.3, 0.3, 0.3, 0.05);
            }
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int streak = getStreak(stack);
        int pot = getPot(stack);
        ChipTier tier = getLockedRoundTier(stack);

        tooltip.add(Component.translatable("tooltip.pocketodds.deck.title").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        if (streak > 0) {
            tooltip.add(Component.translatable("tooltip.pocketodds.deck.streak", streak).withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.translatable("tooltip.pocketodds.deck.pot", tier.getColorCode() + pot + " " + tier.getId()).withStyle(ChatFormatting.YELLOW));
        } else {
            tooltip.add(Component.translatable("tooltip.pocketodds.deck.idle").withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.pocketodds.deck.controls_right").withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.pocketodds.deck.controls_shift").withStyle(ChatFormatting.YELLOW));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
