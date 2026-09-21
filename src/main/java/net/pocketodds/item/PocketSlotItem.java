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
            if (serverPlayer.getCooldowns().isOnCooldown(this)) {
                return InteractionResultHolder.fail(stack);
            }

            if (ActiveRollTracker.hasActiveSession(serverPlayer.getUUID())) {
                FeedbackEffects.sendActionBar(serverPlayer, Component.translatable("pocketodds.slot.already_rolling").withStyle(ChatFormatting.RED));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                return InteractionResultHolder.fail(stack);
            }

            BetSnapshot betSnapshot;
            if (isOffhandItemBet) {
                ItemBetConfigEntry entry = ItemBetRegistry.getEntry(offhand.getItem());
                int count = entry != null ? Math.min(offhand.getCount(), entry.getMaxCount()) : offhand.getCount();
                ItemBetValidator.ValidationResult val = ItemBetValidator.validate(offhand, count, entry, GameType.SLOT);
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
            BetPreparation prep = RewardTransactionService.prepareBet(serverPlayer, GameType.SLOT, betSnapshot, jackpotData);

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

            try {
                // Calculate roll outcomes
                SlotSymbol[] symbols = new SlotSymbol[]{
                        SlotSymbol.getRandomSymbol(level.random, PocketOddsConfig.SERVER),
                        SlotSymbol.getRandomSymbol(level.random, PocketOddsConfig.SERVER),
                        SlotSymbol.getRandomSymbol(level.random, PocketOddsConfig.SERVER)
                };
                SlotOutcome outcome = SlotEvaluator.evaluate(symbols, PocketOddsConfig.SERVER);

                // Pre-session Joker rescue
                if (InventoryUtils.hasJoker(serverPlayer)) {
                    SlotEvaluator.JokerRescueResult rescue = SlotEvaluator.tryRescueWithJoker(symbols, betSnapshot.getBetCount(), PocketOddsConfig.SERVER);
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

                // Insurance check (does not protect against disaster)
                boolean insured = false;
                if (!outcome.isSkulls() && !outcome.isWin() && !outcome.isJackpot() && InventoryUtils.hasInsurance(serverPlayer)) {
                    InventoryUtils.consumeInsurance(serverPlayer);
                    insured = true;
                }

                // Apply cooldown
                int cd = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.cooldownTicks.get() : 30;
                serverPlayer.getCooldowns().addCooldown(this, cd);

                // Register session with finalized symbols, outcome and insurance state
                UUID rollId = UUID.randomUUID();
                prep.setAssociatedId(rollId);
                jackpotData.savePreparedBet(prep);

                SlotRollSession session = new SlotRollSession(rollId, serverPlayer.getUUID(), serverPlayer.getScoreboardName(), betSnapshot, symbols, outcome, insured);
                ActiveRollTracker.addSession(session, jackpotData);

                // 2PC Step 3: COMMIT
                RewardTransactionService.commitBet(prep, jackpotData);

                FeedbackEffects.playSound(serverPlayer, SoundEvents.NOTE_BLOCK_HAT.get(), 1.0f, 1.0f);
            } catch (Exception e) {
                // Rollback in case of registration failure
                RewardTransactionService.rollbackBet(prep, jackpotData);
                throw e;
            }
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
