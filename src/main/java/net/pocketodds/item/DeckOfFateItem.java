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

        // Shift + Right Click = Cash Out!
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }

            if (player instanceof ServerPlayer serverPlayer) {
                JackpotSavedData jackpotData = JackpotSavedData.get(serverPlayer.serverLevel());
                UUID sessionId = getSessionId(stack);
                DeckSession session = sessionId != null ? jackpotData.getDeckSession(sessionId) : null;

                if (session == null || !session.isActive() || session.getStatus() != DeckSession.Status.ACTIVE) {
                    setSessionId(stack, null);
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.deck.empty_pot").withStyle(ChatFormatting.YELLOW));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
                }

                // Ownership verification: prevent cross-player cashout
                if (!isSessionOwner(session, serverPlayer.getUUID())) {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.deck.not_owner").withStyle(ChatFormatting.RED));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                    return InteractionResultHolder.fail(stack);
                }

                List<ItemStack> cashoutItems = calculateDeckCashoutRewards(session);
                UUID cashoutTxId = UUID.nameUUIDFromBytes(("deck_cashout:" + sessionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                RewardBundle bundle = new RewardBundle(cashoutItems, 0L, false);

                // Atomically set CASHOUT_COMMITTED and enqueue outbox transaction under single lock
                jackpotData.commitDeckCashout(sessionId, cashoutTxId, serverPlayer.getUUID(), bundle);

                // Clear item session tag immediately
                setSessionId(stack, null);

                // Deliver pending outbox transactions
                RewardTransactionService.deliverPendingTransactions(serverPlayer.getUUID(), serverPlayer, jackpotData, InventoryUtils::giveOrDrop);

                // Mark delivered and record completion receipt
                jackpotData.markDeckSessionDelivered(sessionId);

                Component cashoutMsg;
                if (session.getInitialBet().isItemBet()) {
                    String summary = formatItemRewards(cashoutItems);
                    cashoutMsg = Component.translatable("pocketodds.deck.cashed_out_items", summary, session.getStreak());
                } else {
                    int totalCount = session.getPotUnits() * session.getInitialBet().getBetCount();
                    cashoutMsg = Component.translatable("pocketodds.deck.cashed_out", totalCount, session.getStreak());
                }
                FeedbackEffects.sendActionBar(serverPlayer,
                        cashoutMsg.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.FIREWORK, 20, 0.4, 0.4, 0.4, 0.1);
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

            JackpotSavedData jackpotData = JackpotSavedData.get(serverPlayer.serverLevel());
            UUID sessionId = getSessionId(stack);
            DeckSession session = sessionId != null ? jackpotData.getDeckSession(sessionId) : null;

            // Starting a new round
            if (session == null || !session.isActive()) {
                ItemStack offhand = serverPlayer.getOffhandItem();
                boolean isOffhandItemBet = !offhand.isEmpty() && !(offhand.getItem() instanceof ChipItem);

                BetSnapshot betSnapshot;
                if (isOffhandItemBet) {
                    ItemBetConfigEntry entry = ItemBetRegistry.getEntry(offhand.getItem());
                    int count = entry != null ? Math.min(offhand.getCount(), entry.getMaxCount()) : offhand.getCount();
                    ItemBetValidator.ValidationResult val = ItemBetValidator.validate(offhand, count, entry, GameType.DECK);
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
                            : ChipTier.COPPER;
                    betSnapshot = BetSnapshot.fromChip(tier, 1);
                }

                // 2PC Step 1: PREPARE
                BetPreparation prep = RewardTransactionService.prepareBet(serverPlayer, GameType.DECK, betSnapshot, jackpotData);

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

                // Create and register server-side DeckSession
                session = new DeckSession(serverPlayer.getUUID(), betSnapshot);
                prep.setAssociatedId(session.getSessionId());
                jackpotData.savePreparedBet(prep);
                jackpotData.saveDeckSession(session);
                setSessionId(stack, session.getSessionId());

                // 2PC Step 3: COMMIT
                RewardTransactionService.commitBet(prep, jackpotData);
            } else {
                // Ongoing streak: check ownership
                if (!isSessionOwner(session, serverPlayer.getUUID())) {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.translatable("pocketodds.deck.not_owner").withStyle(ChatFormatting.RED));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                    return InteractionResultHolder.fail(stack);
                }
            }

            serverPlayer.getCooldowns().addCooldown(this, 15);

            int roll = level.random.nextInt(100);
            int streak = session.getStreak();
            int potUnits = session.getPotUnits();
            BetSnapshot bet = session.getInitialBet();

            if (roll < 35) {
                // CURSE CARD (35% chance to bust)
                if (InventoryUtils.hasInsurance(serverPlayer)) {
                    InventoryUtils.consumeInsurance(serverPlayer);
                    double rate = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                    int refundCount = Math.max(1, (int) Math.round(potUnits * bet.getBetCount() * rate));
                    List<ItemStack> refundItems = createRewardList(bet, refundCount);

                    RewardBundle bundle = new RewardBundle(refundItems, 0L, false);
                    RewardTransactionService.enqueueRewardBundle(jackpotData, serverPlayer.getUUID(), bundle);
                    RewardTransactionService.deliverPendingTransactions(serverPlayer.getUUID(), serverPlayer, jackpotData, InventoryUtils::giveOrDrop);

                    Component insMsg;
                    if (bet.isItemBet()) {
                        insMsg = Component.translatable("pocketodds.item_bet.insurance_refund", refundCount, bet.getItemPrototype().getHoverName());
                    } else {
                        insMsg = Component.translatable("pocketodds.deck.curse_insured", refundCount);
                    }
                    FeedbackEffects.sendActionBar(serverPlayer,
                            insMsg.copy().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
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

                jackpotData.removeDeckSession(session.getSessionId());
                setSessionId(stack, null);

            } else if (roll < 70) {
                // Neutral Patience Card (35% chance)
                streak++;
                session.setStreak(streak);
                jackpotData.saveDeckSession(session);

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_patience", (potUnits * bet.getBetCount()) + " " + bet.getDisplayName().getString(), streak).withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.BOOK_PAGE_TURN, 1.0f, 1.0f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.ENCHANT, 10, 0.3, 0.3, 0.3, 0.05);

            } else if (roll < 88) {
                // Card of Fortune (18% chance)
                double mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.deckFortuneMultiplier.get() : 1.25;
                potUnits = (potUnits <= 1) ? 2 : (int) Math.round(potUnits * mult);
                streak++;
                session.setPotUnits(potUnits);
                session.setStreak(streak);
                jackpotData.saveDeckSession(session);

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_fortune", (potUnits * bet.getBetCount()) + " " + bet.getDisplayName().getString(), streak).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.HAPPY_VILLAGER, 10, 0.3, 0.3, 0.3, 0.05);

            } else if (roll < 94) {
                // Card of Riches (6% chance)
                double mult = PocketOddsConfig.SERVER != null ? PocketOddsConfig.SERVER.deckRichesMultiplier.get() : 1.5;
                potUnits = (potUnits <= 1) ? 2 : (int) Math.round(potUnits * mult);
                streak++;
                session.setPotUnits(potUnits);
                session.setStreak(streak);
                jackpotData.saveDeckSession(session);

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_riches", (potUnits * bet.getBetCount()) + " " + bet.getDisplayName().getString(), streak).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.FIREWORK, 15, 0.4, 0.4, 0.4, 0.1);

            } else if (roll < 97) {
                // Joker's Favor (3% chance)
                potUnits += 1;
                streak++;
                session.setPotUnits(potUnits);
                session.setStreak(streak);
                jackpotData.saveDeckSession(session);
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(ModItems.JOKER.get(), 1));

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_joker", (potUnits * bet.getBetCount()) + " " + bet.getDisplayName().getString(), streak).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.NOTE_BLOCK_CHIME.get(), 1.0f, 1.4f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.WITCH, 15, 0.4, 0.4, 0.4, 0.1);

            } else {
                // Guardian Shield (3% chance)
                potUnits += 1;
                streak++;
                session.setPotUnits(potUnits);
                session.setStreak(streak);
                jackpotData.saveDeckSession(session);
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(ModItems.INSURANCE.get(), 1));

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.deck.card_guardian", (potUnits * bet.getBetCount()) + " " + bet.getDisplayName().getString(), streak).withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.TOTEM_OF_UNDYING, 10, 0.3, 0.3, 0.3, 0.05);
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
