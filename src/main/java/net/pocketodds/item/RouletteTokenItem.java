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
import java.util.Set;

public class RouletteTokenItem extends Item {
    private static final Set<Integer> RED_NUMBERS = Set.of(
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
    );

    public RouletteTokenItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static String getBetType(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.contains("BetType")) ? tag.getString("BetType") : "RED";
    }

    public static void cycleBetType(ItemStack stack) {
        String current = getBetType(stack);
        String next = switch (current) {
            case "RED" -> "BLACK";
            case "BLACK" -> "GREEN";
            default -> "RED";
        };
        stack.getOrCreateTag().putString("BetType", next);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            cycleBetType(stack);
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                String betType = getBetType(stack);
                ChatFormatting color = switch (betType) {
                    case "RED" -> ChatFormatting.RED;
                    case "BLACK" -> ChatFormatting.GRAY;
                    default -> ChatFormatting.GREEN;
                };
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.roulette.bet_type", Component.literal(betType).withStyle(color)).withStyle(ChatFormatting.YELLOW));
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

            ItemStack offhand = serverPlayer.getOffhandItem();
            boolean isOffhandItemBet = !offhand.isEmpty() && !(offhand.getItem() instanceof ChipItem);

            BetSnapshot betSnapshot;
            if (isOffhandItemBet) {
                ItemBetConfigEntry entry = ItemBetRegistry.getEntry(offhand.getItem());
                int count = entry != null ? Math.min(offhand.getCount(), entry.getMaxCount()) : offhand.getCount();
                ItemBetValidator.ValidationResult val = ItemBetValidator.validate(offhand, count, entry, GameType.ROULETTE);
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
                ChipTier tier = (!offhand.isEmpty() && offhand.getItem() instanceof ChipItem chipItem)
                        ? chipItem.getTier()
                        : ChipTier.GOLD;
                int count = (!offhand.isEmpty()) ? Math.min(offhand.getCount(), 16) : 1;
                betSnapshot = BetSnapshot.fromChip(tier, count);
            }

            JackpotSavedData jackpotData = JackpotSavedData.get(serverPlayer.serverLevel());

            // 2PC Step 1: PREPARE
            BetPreparation prep = RewardTransactionService.prepareBet(serverPlayer, GameType.ROULETTE, betSnapshot, jackpotData);
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

            // Apply cooldown (token is permanent and NOT shrunk!)
            serverPlayer.getCooldowns().addCooldown(this, 20);

            // Roll 0..36
            int number = level.random.nextInt(37);
            boolean isZero = (number == 0);
            boolean isRed = RED_NUMBERS.contains(number);
            String outcomeType = isZero ? "GREEN" : (isRed ? "RED" : "BLACK");

            String playerBet = getBetType(stack);
            boolean won = playerBet.equalsIgnoreCase(outcomeType);

            List<ItemStack> rewardItems = new ArrayList<>();
            double mult = 0.0;

            if (won) {
                mult = isZero ? 35.0 : 2.0;
                rewardItems.addAll(calculateRouletteRewards(betSnapshot, mult));
            } else {
                if (InventoryUtils.hasInsurance(serverPlayer)) {
                    InventoryUtils.consumeInsurance(serverPlayer);
                    double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                    int refund = Math.max(1, (int) Math.round(betSnapshot.getBetCount() * rate));
                    rewardItems.addAll(createRewardList(betSnapshot, refund));
                }
            }

            // 2PC Step 3: COMMIT with settlement (records outcome transaction even on loss to prevent crash recovery refund)
            RewardBundle bundle = new RewardBundle(rewardItems, 0L, false);
            jackpotData.settleAndCommitBet(prep, prep.getPreparationId(), serverPlayer.getUUID(), bundle);

            // Deliver outbox transactions
            RewardTransactionService.deliverPendingTransactions(serverPlayer.getUUID(), serverPlayer, jackpotData, InventoryUtils::giveOrDrop);

            // Visual feedback
            if (won) {
                int totalOut = (int) Math.round(betSnapshot.getBetCount() * mult);
                Component winMsg = betSnapshot.isItemBet()
                        ? Component.translatable("pocketodds.item_bet.win", totalOut, betSnapshot.getItemPrototype().getHoverName())
                        : Component.translatable("pocketodds.roulette.win", "+" + totalOut + " " + betSnapshot.getChipTier().getId());

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎡 " + number + " " + outcomeType + " ] ")
                                .append(winMsg.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.FIREWORK, 20, 0.4, 0.4, 0.4, 0.1);
            } else {
                if (!rewardItems.isEmpty()) {
                    int refund = Math.max(1, (int) Math.round(betSnapshot.getBetCount() * 0.50));
                    Component insMsg = betSnapshot.isItemBet()
                            ? Component.translatable("pocketodds.item_bet.insurance_refund", refund, betSnapshot.getItemPrototype().getHoverName())
                            : Component.translatable("pocketodds.insurance.triggered", refund);
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.literal("[ 🎡 " + number + " " + outcomeType + " ] ")
                                    .append(insMsg.copy().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
                } else {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.literal("[ 🎡 " + number + " " + outcomeType + " ] ")
                                    .append(Component.translatable("pocketodds.roulette.loss").withStyle(ChatFormatting.GRAY)));
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

    public static List<ItemStack> calculateRouletteRewards(BetSnapshot bet, double multiplier) {
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
            list.addAll(ItemRewardRegistry.getTable(GameType.ROULETTE).rollRewards(
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
                list.addAll(ItemRewardRegistry.getTable(GameType.ROULETTE).rollRewards(
                        RandomSource.create(), tableBudgetCredits, bet.getTotalCreditValue(), globalCap
                ));
            }
        }
        return list;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        String betType = getBetType(stack);
        ChatFormatting color = switch (betType) {
            case "RED" -> ChatFormatting.RED;
            case "BLACK" -> ChatFormatting.GRAY;
            default -> ChatFormatting.GREEN;
        };

        tooltip.add(Component.translatable("tooltip.pocketodds.roulette.title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        tooltip.add(Component.translatable("tooltip.pocketodds.roulette.bet", Component.literal(betType).withStyle(color)).withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.pocketodds.roulette.controls_shift").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.pocketodds.roulette.controls_right").withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.pocketodds.offhand_item_tip").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
