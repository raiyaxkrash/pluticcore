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

@Deprecated
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
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            net.minecraftforge.network.NetworkHooks.openScreen(
                    serverPlayer,
                    new net.minecraft.world.SimpleMenuProvider(
                            (id, inv, p) -> new net.pocketodds.gui.casino.PocketCasinoMenu(id, inv, net.pocketodds.gui.casino.CasinoCategory.ROULETTE),
                            Component.translatable("pocketodds.gui.casino.title")
                    ),
                    buf -> buf.writeInt(net.pocketodds.gui.casino.CasinoCategory.ROULETTE.ordinal())
            );
            net.pocketodds.service.CasinoGameService.onPlayerOpenCasino(serverPlayer);
            return InteractionResultHolder.sidedSuccess(stack, false);
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
