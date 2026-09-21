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
import net.pocketodds.gambling.core.BetPreparation;
import net.pocketodds.gambling.core.BetSnapshot;
import net.pocketodds.gambling.core.DeckSession;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.core.RewardBundle;
import net.pocketodds.gambling.core.RewardTransactionService;
import net.minecraft.util.RandomSource;
import net.pocketodds.gambling.itembet.ItemBetConfigEntry;
import net.pocketodds.gambling.itembet.ItemBetRegistry;
import net.pocketodds.gambling.itembet.ItemBetValidator;
import net.pocketodds.gambling.itembet.ItemRewardRegistry;
import net.pocketodds.gambling.itembet.PayoutMode;
import net.pocketodds.registration.ModItems;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Deprecated
public class DeckOfFateItem extends Item {

    public DeckOfFateItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Nullable
    public static UUID getSessionId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.contains("SessionId")) ? tag.getUUID("SessionId") : null;
    }

    public static void setSessionId(ItemStack stack, @Nullable UUID id) {
        if (id == null) {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove("SessionId");
            }
        } else {
            stack.getOrCreateTag().putUUID("SessionId", id);
        }
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

    public static ChipTier getLockedRoundTier(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.contains("BetTier")) ? ChipTier.fromId(tag.getString("BetTier")) : ChipTier.COPPER;
    }

    public static void setBetTier(ItemStack stack, ChipTier tier) {
        stack.getOrCreateTag().putString("BetTier", tier.getId());
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
                            (id, inv, p) -> new net.pocketodds.gui.casino.PocketCasinoMenu(id, inv, net.pocketodds.gui.casino.CasinoCategory.DECK_OF_FATE),
                            Component.translatable("pocketodds.gui.casino.title")
                    ),
                    buf -> buf.writeInt(net.pocketodds.gui.casino.CasinoCategory.DECK_OF_FATE.ordinal())
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

    public static List<ItemStack> calculateDeckCashoutRewards(DeckSession session) {
        List<ItemStack> list = new ArrayList<>();
        if (session == null || session.getInitialBet() == null || session.getPotUnits() <= 0) {
            return list;
        }

        BetSnapshot bet = session.getInitialBet();
        int totalUnits = session.getPotUnits();
        int totalCount = totalUnits * bet.getBetCount();

        if (bet.isChipBet()) {
            list.addAll(createRewardList(bet, totalCount));
            return list;
        }

        PayoutMode mode = PayoutMode.SAME_ITEM;
        if (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded()) {
            mode = PayoutMode.fromId(PocketOddsConfig.SERVER.itemBetPayoutMode.get());
        }
        int globalCap = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                ? PocketOddsConfig.SERVER.maxItemRewardCap.get() : 512;

        if (mode == PayoutMode.SAME_ITEM) {
            list.addAll(createRewardList(bet, Math.min(totalCount, globalCap)));
        } else if (mode == PayoutMode.REWARD_TABLE) {
            long totalWinCredits = (long) totalUnits * bet.getTotalCreditValue();
            list.addAll(ItemRewardRegistry.getTable(GameType.DECK).rollRewards(
                    RandomSource.create(), totalWinCredits, bet.getTotalCreditValue(), globalCap
            ));
        } else if (mode == PayoutMode.BOTH) {
            double sameItemWeight = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                    ? PocketOddsConfig.SERVER.bothSameItemWeight.get() : 0.80;
            double tableWeight = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                    ? PocketOddsConfig.SERVER.bothTableWeight.get() : 0.20;

            long totalWinCredits = (long) totalUnits * bet.getTotalCreditValue();
            double rawTableBudget = totalWinCredits * tableWeight;
            long tableBase = (long) Math.floor(rawTableBudget);
            long tableBudgetCredits = tableBase + (RandomSource.create().nextDouble() < (rawTableBudget - tableBase) ? 1L : 0L);

            double sameTarget = totalCount * sameItemWeight;
            int sameItemBase = (int) Math.floor(sameTarget);
            int sameItemCount = sameItemBase + (RandomSource.create().nextDouble() < (sameTarget - sameItemBase) ? 1 : 0);
            if (sameItemCount > 0) {
                list.addAll(createRewardList(bet, Math.min(sameItemCount, globalCap)));
            }
            if (tableBudgetCredits > 0L) {
                list.addAll(ItemRewardRegistry.getTable(GameType.DECK).rollRewards(
                        RandomSource.create(), tableBudgetCredits, bet.getTotalCreditValue(), globalCap
                ));
            }
        }
        return list;
    }

    public static String formatItemRewards(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return "0";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (stack != null && !stack.isEmpty()) {
                String name = stack.getHoverName().getString();
                counts.put(name, counts.getOrDefault(name, 0) + stack.getCount());
            }
        }
        if (counts.isEmpty()) {
            return "0";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(entry.getValue() + "x " + entry.getKey());
        }
        if (parts.size() <= 3) {
            return String.join(", ", parts);
        } else {
            return parts.get(0) + ", " + parts.get(1) + " + " + (parts.size() - 2) + " ...";
        }
    }

    public static boolean isSessionOwner(DeckSession session, UUID playerUUID) {
        return session != null && playerUUID != null && playerUUID.equals(session.getPlayerUUID());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        UUID sId = getSessionId(stack);
        tooltip.add(Component.translatable("tooltip.pocketodds.deck.title").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        if (sId != null) {
            tooltip.add(Component.translatable("tooltip.pocketodds.deck.streak", "...").withStyle(ChatFormatting.GOLD));
        } else {
            tooltip.add(Component.translatable("tooltip.pocketodds.deck.idle").withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.pocketodds.deck.controls_right").withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.pocketodds.deck.controls_shift").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.pocketodds.offhand_item_tip").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
