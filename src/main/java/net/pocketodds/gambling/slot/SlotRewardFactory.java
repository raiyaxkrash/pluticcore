package net.pocketodds.gambling.slot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.gambling.core.BetSnapshot;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.itembet.ItemRewardRegistry;
import net.pocketodds.gambling.itembet.PayoutMode;
import net.pocketodds.item.ChipTier;
import net.pocketodds.registration.ModItems;
import net.pocketodds.util.ChipUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public interface SlotRewardFactory {
    Item getJackpotToken();
    Item getJokerItem();

    SlotRewardFactory DEFAULT = new SlotRewardFactory() {
        @Override
        public Item getJackpotToken() {
            return ModItems.JACKPOT_TOKEN.get();
        }

        @Override
        public Item getJokerItem() {
            return ModItems.JOKER.get();
        }
    };

    static List<ItemStack> createRewards(ServerPlayer player, SlotOutcome outcome, BetSnapshot betSnapshot, long wonJackpotAmount, boolean insured, PocketOddsConfig.Server config) {
        return createRewards(player != null ? player.getScoreboardName() : "Player", outcome, betSnapshot, wonJackpotAmount, insured, config, DEFAULT);
    }

    static List<ItemStack> createRewards(String playerName, SlotOutcome outcome, BetSnapshot betSnapshot, long wonJackpotAmount, boolean insured, PocketOddsConfig.Server config, SlotRewardFactory factory) {
        List<ItemStack> rewardStacks = new ArrayList<>();
        if (outcome == null || betSnapshot == null) return rewardStacks;

        int betCount = betSnapshot.getBetCount();
        ChipTier betTier = betSnapshot.isChipBet() ? betSnapshot.getChipTier() : ChipTier.COPPER;

        if (outcome.isJackpot()) {
            int betMultiplierPayout = (int) Math.round(betCount * outcome.getMultiplier());
            rewardStacks.addAll(ChipUtils.convertAmountToChips(wonJackpotAmount));

            if (betSnapshot.isItemBet()) {
                ItemStack proto = betSnapshot.getItemPrototype();
                int max = proto.getMaxStackSize();
                int rem = Math.max(1, betMultiplierPayout);
                while (rem > 0) {
                    int take = Math.min(rem, max);
                    ItemStack s = proto.copy();
                    s.setCount(take);
                    rewardStacks.add(s);
                    rem -= take;
                }
            } else {
                rewardStacks.addAll(ChipUtils.splitChips(betTier.getItem(), Math.max(1, betMultiplierPayout)));
            }

            Item tokenItem = factory != null ? factory.getJackpotToken() : ModItems.JACKPOT_TOKEN.get();
            ItemStack jackpotToken = new ItemStack(tokenItem, 1);
            CompoundTag tokenTag = jackpotToken.getOrCreateTag();
            tokenTag.putString("Winner", playerName != null ? playerName : "Unknown Player");
            tokenTag.putLong("JackpotAmount", wonJackpotAmount);
            tokenTag.putString("Date", LocalDate.now().toString());
            rewardStacks.add(jackpotToken);
        } else if (outcome.isWin()) {
            if (betSnapshot.isItemBet()) {
                PayoutMode mode = PayoutMode.SAME_ITEM;
                if (config != null) {
                    mode = PayoutMode.fromId(config.itemBetPayoutMode.get());
                }
                int maxStack = betSnapshot.getItemPrototype().getMaxStackSize();
                int globalCap = config != null ? config.maxItemRewardCap.get() : 512;

                if (mode == PayoutMode.SAME_ITEM) {
                    int payoutAmount = (int) Math.round(betCount * outcome.getMultiplier());
                    if (payoutAmount < 1) payoutAmount = 1;
                    payoutAmount = Math.min(payoutAmount, globalCap);
                    int rem = payoutAmount;
                    while (rem > 0) {
                        int take = Math.min(rem, maxStack);
                        ItemStack s = betSnapshot.getItemPrototype().copy();
                        s.setCount(take);
                        rewardStacks.add(s);
                        rem -= take;
                    }
                } else if (mode == PayoutMode.REWARD_TABLE) {
                    long budgetCredits = Math.max(1L, Math.round(betSnapshot.getTotalCreditValue() * outcome.getMultiplier()));
                    rewardStacks.addAll(ItemRewardRegistry.getTable(GameType.SLOT).rollRewards(
                            RandomSource.create(), budgetCredits, betSnapshot.getTotalCreditValue(), globalCap
                    ));
                } else if (mode == PayoutMode.BOTH) {
                    double sameItemWeight = config != null ? config.bothSameItemWeight.get() : 0.80;
                    double tableWeight = config != null ? config.bothTableWeight.get() : 0.20;

                    long totalWinCredits = Math.max(1L, Math.round(betSnapshot.getTotalCreditValue() * outcome.getMultiplier()));
                    double rawTableBudget = totalWinCredits * tableWeight;
                    long tableBase = (long) Math.floor(rawTableBudget);
                    long tableBudgetCredits = tableBase + (RandomSource.create().nextDouble() < (rawTableBudget - tableBase) ? 1L : 0L);

                    double sameTarget = betCount * outcome.getMultiplier() * sameItemWeight;
                    int sameBase = (int) Math.floor(sameTarget);
                    int payoutAmount = sameBase + (RandomSource.create().nextDouble() < (sameTarget - sameBase) ? 1 : 0);
                    payoutAmount = Math.min(payoutAmount, globalCap);
                    int rem = payoutAmount;
                    while (rem > 0) {
                        int take = Math.min(rem, maxStack);
                        ItemStack s = betSnapshot.getItemPrototype().copy();
                        s.setCount(take);
                        rewardStacks.add(s);
                        rem -= take;
                    }

                    if (tableBudgetCredits > 0L) {
                        rewardStacks.addAll(ItemRewardRegistry.getTable(GameType.SLOT).rollRewards(
                                RandomSource.create(), tableBudgetCredits, betSnapshot.getTotalCreditValue(), globalCap
                        ));
                    }
                }
            } else {
                int payoutAmount = (int) Math.round(betCount * outcome.getMultiplier());
                if (payoutAmount < 1) payoutAmount = 1;
                rewardStacks.addAll(ChipUtils.splitChips(betTier.getItem(), payoutAmount));
            }

            if (outcome.isThreeJokers()) {
                Item jokerItem = factory != null ? factory.getJokerItem() : ModItems.JOKER.get();
                rewardStacks.add(new ItemStack(jokerItem, 1));
            }
        } else if (insured) {
            double refundRate = config != null ? config.insuranceRefundRate.get() : 0.50;
            int refundAmount = Math.max(1, (int) Math.round(betCount * refundRate));
            if (betSnapshot.isItemBet()) {
                int maxStack = betSnapshot.getItemPrototype().getMaxStackSize();
                int rem = refundAmount;
                while (rem > 0) {
                    int take = Math.min(rem, maxStack);
                    ItemStack s = betSnapshot.getItemPrototype().copy();
                    s.setCount(take);
                    rewardStacks.add(s);
                    rem -= take;
                }
            } else {
                rewardStacks.addAll(ChipUtils.splitChips(betTier.getItem(), refundAmount));
            }
        }

        return rewardStacks;
    }
}
