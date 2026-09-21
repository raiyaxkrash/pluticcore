package net.pocketodds.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
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
import net.pocketodds.gambling.core.BetPreparation;
import net.pocketodds.gambling.core.BetSnapshot;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.core.RewardBundle;
import net.pocketodds.gambling.core.RewardTransactionService;
import net.pocketodds.gambling.itembet.ItemBetConfigEntry;
import net.pocketodds.gambling.itembet.ItemBetRegistry;
import net.pocketodds.gambling.itembet.ItemBetValidator;
import net.pocketodds.gambling.itembet.ItemRewardRegistry;
import net.pocketodds.gambling.itembet.PayoutMode;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
        ItemStack offhand = player.getOffhandItem();
        boolean isOffhandItemBet = !offhand.isEmpty() && !(offhand.getItem() instanceof ChipItem);

        if (player.isShiftKeyDown()) {
            if (isOffhandItemBet) {
                if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                    ItemBetConfigEntry entry = ItemBetRegistry.getEntry(offhand.getItem());
                    int count = entry != null ? Math.min(offhand.getCount(), entry.getMaxCount()) : offhand.getCount();
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.item_bet.bet_changed", count, offhand.getHoverName()).withStyle(ChatFormatting.YELLOW));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.UI_BUTTON_CLICK.get(), 0.8f, 1.2f);
                }
            } else {
                cycleBet(stack);
                if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                    int betCount = getBetCount(stack);
                    ChipTier betTier = getBetTier(stack, player);
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.dice.bet_changed", betCount, betTier.getColorCode() + betTier.getId()).withStyle(ChatFormatting.YELLOW));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.UI_BUTTON_CLICK.get(), 0.8f, 1.2f);
                }
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

            BetSnapshot betSnapshot;
            if (isOffhandItemBet) {
                ItemBetConfigEntry entry = ItemBetRegistry.getEntry(offhand.getItem());
                int count = entry != null ? Math.min(offhand.getCount(), entry.getMaxCount()) : offhand.getCount();
                ItemBetValidator.ValidationResult val = ItemBetValidator.validate(offhand, count, entry, GameType.DICE);
                if (val != ItemBetValidator.ValidationResult.VALID) {
                    Component errorMsg = switch (val) {
                        case NOT_ALLOWED_ITEM -> Component.translatable("pocketodds.item_bet.not_allowed");
                        case GAME_NOT_ALLOWED -> Component.translatable("pocketodds.item_bet.game_not_allowed");
                        case CONTAINER_FORBIDDEN -> Component.translatable("pocketodds.item_bet.container_forbidden");
                        case DAMAGED_FORBIDDEN -> Component.translatable("pocketodds.item_bet.damaged_forbidden");
                        case NBT_OR_ENCHANTS_FORBIDDEN -> Component.translatable("pocketodds.item_bet.nbt_forbidden");
                        case COUNT_OUT_OF_RANGE -> Component.translatable("pocketodds.item_bet.count_out_of_range", count, entry != null ? entry.getMinCount() : 1, entry != null ? entry.getMaxCount() : 64);
                        default -> Component.translatable("pocketodds.item_bet.not_allowed");
                    };
                    FeedbackEffects.sendActionBar(serverPlayer, errorMsg.copy().withStyle(ChatFormatting.RED));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                    return InteractionResultHolder.fail(stack);
                }
                betSnapshot = BetSnapshot.fromItem(offhand, count, entry.getUnitCreditValue());
            } else {
                ChipTier betTier = getBetTier(stack, serverPlayer);
                int betCount = getBetCount(stack);
                betSnapshot = BetSnapshot.fromChip(betTier, betCount);
            }

            JackpotSavedData jackpotData = JackpotSavedData.get(serverPlayer.serverLevel());

            // 2PC Step 1: PREPARE
            BetPreparation prep = RewardTransactionService.prepareBet(serverPlayer, GameType.DICE, betSnapshot, jackpotData);
            prep.setAssociatedId(prep.getPreparationId());
            jackpotData.savePreparedBet(prep);

            // 2PC Step 2: DEBIT
            boolean debited = RewardTransactionService.debitBet(prep, serverPlayer, jackpotData);
            if (!debited) {
                if (betSnapshot.isItemBet()) {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.item_bet.not_enough", betSnapshot.getBetCount(), betSnapshot.getItemPrototype().getHoverName()).withStyle(ChatFormatting.RED));
                } else {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.not_enough_chips", betSnapshot.getBetCount(), betSnapshot.getChipTier().getColorCode() + betSnapshot.getChipTier().getId()).withStyle(ChatFormatting.RED));
                }
                FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                return InteractionResultHolder.fail(stack);
            }

            int cd = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.cooldownTicks.get() : 30;
            serverPlayer.getCooldowns().addCooldown(this, cd);

            // Roll two dice
            int d1 = level.random.nextInt(6) + 1;
            int d2 = level.random.nextInt(6) + 1;
            int sum = d1 + d2;
            boolean isDouble = (d1 == d2);

            String d1Face = DICE_FACES[d1 - 1];
            String d2Face = DICE_FACES[d2 - 1];

            List<ItemStack> rewardItems = new ArrayList<>();
            double mult = 0.0;
            boolean isSnakeEyes = (d1 == 1 && d2 == 1);
            boolean isWin = false;

            if (isSnakeEyes) {
                // Snake eyes disaster
                serverPlayer.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 120, 0));
                serverPlayer.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 0));
                if (InventoryUtils.hasInsurance(serverPlayer)) {
                    InventoryUtils.consumeInsurance(serverPlayer);
                    double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                    int refund = Math.max(1, (int) Math.round(betSnapshot.getBetCount() * rate));
                    rewardItems.addAll(createRewardList(betSnapshot, refund));
                }
            } else if (sum == 7) {
                isWin = true;
                mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.diceLuckySevenPayout.get() : 3.0;
                rewardItems.addAll(calculateDiceRewards(betSnapshot, mult));
            } else if (isDouble) {
                isWin = true;
                mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.diceDoublePayout.get() : 2.0;
                rewardItems.addAll(calculateDiceRewards(betSnapshot, mult));
            } else if (sum == 11) {
                isWin = true;
                mult = 2.0;
                rewardItems.addAll(calculateDiceRewards(betSnapshot, mult));
            } else {
                // Regular loss
                if (InventoryUtils.hasInsurance(serverPlayer)) {
                    InventoryUtils.consumeInsurance(serverPlayer);
                    double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                    int refund = Math.max(1, (int) Math.round(betSnapshot.getBetCount() * rate));
                    rewardItems.addAll(createRewardList(betSnapshot, refund));
                }
            }

            // Pre-commit outcome to transactional outbox BEFORE delivery!
            RewardBundle bundle = new RewardBundle(rewardItems, 0L, false);
            if (!rewardItems.isEmpty()) {
                RewardTransactionService.enqueueRewardBundle(jackpotData, prep.getPreparationId(), serverPlayer.getUUID(), bundle);
            }

            // 2PC Step 3: COMMIT
            RewardTransactionService.commitBet(prep, jackpotData);

            // Deliver outbox transactions
            RewardTransactionService.deliverPendingTransactions(serverPlayer.getUUID(), serverPlayer, jackpotData, InventoryUtils::giveOrDrop);

            // Feedback
            if (isSnakeEyes) {
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎲 " + d1Face + " | " + d2Face + " ] ")
                                .append(Component.translatable("pocketodds.dice.snake_eyes").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.AMBIENT_CAVE.get(), 1.0f, 0.8f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.SQUID_INK, 20, 0.4, 0.4, 0.4, 0.05);
            } else if (isWin) {
                int totalOut = (int) Math.round(betSnapshot.getBetCount() * mult);
                Component winMsg = betSnapshot.isItemBet()
                        ? Component.translatable("pocketodds.item_bet.win", totalOut, betSnapshot.getItemPrototype().getHoverName())
                        : Component.translatable("pocketodds.dice.win", "+" + totalOut);

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎲 " + d1Face + " | " + d2Face + (sum == 7 ? " = 7" : (isDouble ? "" : " = " + sum)) + " ] ")
                                .append(winMsg.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.FIREWORK, 25, 0.5, 0.5, 0.5, 0.1);
            } else {
                if (!rewardItems.isEmpty()) {
                    // Insured
                    int refund = Math.max(1, (int) Math.round(betSnapshot.getBetCount() * 0.50));
                    Component insMsg = betSnapshot.isItemBet()
                            ? Component.translatable("pocketodds.item_bet.insurance_refund", refund, betSnapshot.getItemPrototype().getHoverName())
                            : Component.translatable("pocketodds.insurance.triggered", refund);
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.literal("[ 🎲 " + d1Face + " | " + d2Face + " = " + sum + " ] ")
                                    .append(insMsg.copy().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
                } else {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.literal("[ 🎲 " + d1Face + " | " + d2Face + " = " + sum + " ] ")
                                    .append(Component.translatable("pocketodds.dice.loss").withStyle(ChatFormatting.GRAY)));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                }
            }
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static List<ItemStack> createRewardList(BetSnapshot bet, int count) {
        List<ItemStack> list = new ArrayList<>();
        if (bet.isChipBet()) {
            list.addAll(ChipUtils.splitChips(bet.getChipTier().getItem(), count));
        } else {
            ItemStack proto = bet.getItemPrototype();
            int max = proto.getMaxStackSize();
            int rem = count;
            while (rem > 0) {
                int take = Math.min(rem, max);
                ItemStack s = proto.copy();
                s.setCount(take);
                list.add(s);
                rem -= take;
            }
        }
        return list;
    }

    private static List<ItemStack> calculateDiceRewards(BetSnapshot bet, double multiplier) {
        List<ItemStack> list = new ArrayList<>();
        int count = Math.max(1, (int) Math.round(bet.getBetCount() * multiplier));

        if (bet.isChipBet()) {
            list.addAll(ChipUtils.splitChips(bet.getChipTier().getItem(), count));
            return list;
        }

        PayoutMode mode = PayoutMode.SAME_ITEM;
        if (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded()) {
            mode = PayoutMode.fromId(PocketOddsConfig.SERVER.itemBetPayoutMode.get());
        }
        int globalCap = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? PocketOddsConfig.SERVER.maxItemRewardCap.get() : 512;

        if (mode == PayoutMode.SAME_ITEM) {
            list.addAll(createRewardList(bet, Math.min(count, globalCap)));
        } else if (mode == PayoutMode.REWARD_TABLE) {
            long budgetCredits = Math.max(1L, Math.round(bet.getTotalCreditValue() * multiplier));
            list.addAll(ItemRewardRegistry.getTable(GameType.DICE).rollRewards(
                    RandomSource.create(), budgetCredits, bet.getTotalCreditValue(), globalCap
            ));
        } else if (mode == PayoutMode.BOTH) {
            double sameItemWeight = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                    ? PocketOddsConfig.SERVER.bothSameItemWeight.get() : 0.80;
            double tableWeight = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                    ? PocketOddsConfig.SERVER.bothTableWeight.get() : 0.20;

            long totalWinCredits = Math.max(1L, Math.round(bet.getTotalCreditValue() * multiplier));
            double rawTableBudget = totalWinCredits * tableWeight;
            long tableBase = (long) Math.floor(rawTableBudget);
            long tableBudgetCredits = tableBase + (RandomSource.create().nextDouble() < (rawTableBudget - tableBase) ? 1L : 0L);

            double sameTarget = count * sameItemWeight;
            int sameItemBase = (int) Math.floor(sameTarget);
            int sameItemCount = sameItemBase + (RandomSource.create().nextDouble() < (sameTarget - sameItemBase) ? 1 : 0);
            if (sameItemCount > 0) {
                list.addAll(createRewardList(bet, Math.min(sameItemCount, globalCap)));
            }
            if (tableBudgetCredits > 0L) {
                list.addAll(ItemRewardRegistry.getTable(GameType.DICE).rollRewards(
                        RandomSource.create(), tableBudgetCredits, bet.getTotalCreditValue(), globalCap
                ));
            }
        }
        return list;
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
        tooltip.add(Component.translatable("tooltip.pocketodds.offhand_item_tip").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
