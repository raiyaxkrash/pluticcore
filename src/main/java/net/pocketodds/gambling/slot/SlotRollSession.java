package net.pocketodds.gambling.slot;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.gambling.core.BetSnapshot;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.core.RewardLine;
import net.pocketodds.gambling.itembet.ItemRewardRegistry;
import net.pocketodds.gambling.itembet.PayoutMode;
import net.pocketodds.item.ChipTier;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import net.pocketodds.util.RewardDeliverySink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class SlotRollSession {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final UUID rollId;
    private final UUID playerUUID;
    private final String playerName;
    private final BetSnapshot betSnapshot;
    private final ChipTier betTier;
    private final int betCount;
    private final SlotSymbol[] symbols;
    private final SlotOutcome outcome;
    private final boolean insured;
    private long wonJackpotAmount = 0L;
    private int tick = 0;
    private boolean finished = false;
    private boolean finalized = false;

    public SlotRollSession(UUID playerUUID, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome) {
        this(UUID.randomUUID(), playerUUID, null, BetSnapshot.fromChip(betTier, betCount), symbols, outcome, false);
    }

    public SlotRollSession(UUID rollId, UUID playerUUID, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome) {
        this(rollId, playerUUID, null, BetSnapshot.fromChip(betTier, betCount), symbols, outcome, false);
    }

    public SlotRollSession(UUID playerUUID, String playerName, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome) {
        this(UUID.randomUUID(), playerUUID, playerName, BetSnapshot.fromChip(betTier, betCount), symbols, outcome, false);
    }

    public SlotRollSession(UUID rollId, UUID playerUUID, String playerName, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome) {
        this(rollId, playerUUID, playerName, BetSnapshot.fromChip(betTier, betCount), symbols, outcome, false);
    }

    public SlotRollSession(UUID playerUUID, String playerName, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome, boolean insured) {
        this(UUID.randomUUID(), playerUUID, playerName, BetSnapshot.fromChip(betTier, betCount), symbols, outcome, insured);
    }

    public SlotRollSession(UUID rollId, UUID playerUUID, String playerName, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome, boolean insured) {
        this(rollId, playerUUID, playerName, BetSnapshot.fromChip(betTier, betCount), symbols, outcome, insured);
    }

    public SlotRollSession(UUID rollId, UUID playerUUID, String playerName, BetSnapshot betSnapshot, SlotSymbol[] symbols, SlotOutcome outcome, boolean insured) {
        this.rollId = rollId != null ? rollId : UUID.randomUUID();
        this.playerUUID = Objects.requireNonNull(playerUUID, "playerUUID must not be null");
        this.playerName = playerName;
        this.betSnapshot = Objects.requireNonNull(betSnapshot, "betSnapshot must not be null");
        this.betTier = betSnapshot.isChipBet() ? betSnapshot.getChipTier() : ChipTier.COPPER;
        this.betCount = betSnapshot.getBetCount();
        this.symbols = symbols;
        this.outcome = outcome;
        this.insured = insured;
    }

    public UUID getRollId() {
        return rollId;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public String getPlayerName() {
        return playerName;
    }

    public BetSnapshot getBetSnapshot() {
        return betSnapshot;
    }

    public boolean isItemBet() {
        return betSnapshot.isItemBet();
    }

    public ChipTier getBetTier() {
        return betTier;
    }

    public int getBetCount() {
        return betCount;
    }

    public SlotSymbol[] getSymbols() {
        return symbols;
    }

    public SlotOutcome getOutcome() {
        return outcome;
    }

    public boolean isInsured() {
        return insured;
    }

    public long getWonJackpotAmount() {
        return wonJackpotAmount;
    }

    public void setWonJackpotAmount(long wonJackpotAmount) {
        this.wonJackpotAmount = wonJackpotAmount;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isFinalized() {
        return finalized;
    }

    public int getTick() {
        return tick;
    }

    public void setTick(int tick) {
        this.tick = tick;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 2);
        tag.putUUID("RollId", rollId);
        tag.putUUID("PlayerUUID", playerUUID);
        if (playerName != null && !playerName.isBlank()) {
            tag.putString("PlayerName", playerName);
        }
        tag.put("BetSnapshot", betSnapshot.toNbt());
        // Backward compatibility tags
        tag.putString("BetTier", betTier.getId());
        tag.putInt("BetCount", betCount);

        tag.putString("Sym0", symbols[0].name());
        tag.putString("Sym1", symbols[1].name());
        tag.putString("Sym2", symbols[2].name());
        tag.putInt("Tick", tick);
        tag.putBoolean("Finalized", finalized);
        tag.putBoolean("Insured", insured);
        if (wonJackpotAmount > 0L) {
            tag.putLong("WonJackpotAmount", wonJackpotAmount);
        }
        return tag;
    }

    public static SlotRollSession fromNbt(CompoundTag tag, PocketOddsConfig.Server config) {
        UUID rollId = tag.contains("RollId") ? tag.getUUID("RollId") : UUID.randomUUID();
        UUID uuid = tag.getUUID("PlayerUUID");
        String playerName = tag.contains("PlayerName") ? tag.getString("PlayerName") : null;

        BetSnapshot betSnapshot;
        if (tag.contains("BetSnapshot")) {
            betSnapshot = BetSnapshot.fromNbt(tag.getCompound("BetSnapshot"));
        } else {
            ChipTier tier = ChipTier.fromId(tag.getString("BetTier"));
            int betCount = tag.getInt("BetCount");
            betSnapshot = BetSnapshot.fromChip(tier, betCount);
        }

        SlotSymbol s0 = SlotSymbol.valueOf(tag.getString("Sym0"));
        SlotSymbol s1 = SlotSymbol.valueOf(tag.getString("Sym1"));
        SlotSymbol s2 = SlotSymbol.valueOf(tag.getString("Sym2"));
        SlotSymbol[] syms = new SlotSymbol[]{s0, s1, s2};
        SlotOutcome outcome = SlotEvaluator.evaluate(syms, config);
        boolean insured = tag.getBoolean("Insured");

        SlotRollSession session = new SlotRollSession(rollId, uuid, playerName, betSnapshot, syms, outcome, insured);
        session.setTick(tag.getInt("Tick"));
        if (tag.contains("WonJackpotAmount")) {
            session.setWonJackpotAmount(tag.getLong("WonJackpotAmount"));
        }
        if (tag.getBoolean("Finalized")) {
            session.finalized = true;
            session.finished = true;
        }
        return session;
    }

    public void tick(MinecraftServer server) {
        if (finished) return;

        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
        tick++;

        if (tick == 1 && player != null) {
            Component display = Component.literal("[ 🎰  ❓  |  ❓  |  ❓  ]").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            FeedbackEffects.sendActionBar(player, display);
        } else if (tick == 8 && player != null) {
            Component display = Component.literal("[ 🎰  ").withStyle(ChatFormatting.GOLD)
                    .append(symbols[0].getShortDisplay())
                    .append(Component.literal("  |  ❓  |  ❓  ]").withStyle(ChatFormatting.GOLD));
            FeedbackEffects.sendActionBar(player, display);
            FeedbackEffects.playSound(player, SoundEvents.NOTE_BLOCK_PLING.get(), 1.0f, 0.8f);
        } else if (tick == 16 && player != null) {
            Component display = Component.literal("[ 🎰  ").withStyle(ChatFormatting.GOLD)
                    .append(symbols[0].getShortDisplay())
                    .append(Component.literal("  |  ").withStyle(ChatFormatting.GOLD))
                    .append(symbols[1].getShortDisplay())
                    .append(Component.literal("  |  ❓  ]").withStyle(ChatFormatting.GOLD));
            FeedbackEffects.sendActionBar(player, display);
            FeedbackEffects.playSound(player, SoundEvents.NOTE_BLOCK_PLING.get(), 1.0f, 1.0f);
        } else if (tick == 24 && player != null) {
            Component display = Component.literal("[ 🎰  ").withStyle(ChatFormatting.GOLD)
                    .append(symbols[0].getShortDisplay())
                    .append(Component.literal("  |  ").withStyle(ChatFormatting.GOLD))
                    .append(symbols[1].getShortDisplay())
                    .append(Component.literal("  |  ").withStyle(ChatFormatting.GOLD))
                    .append(symbols[2].getShortDisplay())
                    .append(Component.literal("  ]").withStyle(ChatFormatting.GOLD));
            FeedbackEffects.sendActionBar(player, display);
            FeedbackEffects.playSound(player, SoundEvents.NOTE_BLOCK_PLING.get(), 1.0f, 1.25f);
            FeedbackEffects.spawnParticles(player, ParticleTypes.ENCHANT, 16, 0.4, 0.4, 0.4, 0.1);
        } else if (tick >= 28) {
            boolean success = finalizeOutcome(server, player);
            if (success) {
                this.finished = true;
            }
        }
    }

    public void prepareAndCommitOutcome(JackpotSavedData jackpotData, String playerNameFallback) {
        prepareAndCommitOutcome(jackpotData, playerNameFallback, SlotRewardFactory.DEFAULT);
    }

    public void prepareAndCommitOutcome(JackpotSavedData jackpotData, String playerNameFallback, SlotRewardFactory rewardFactory) {
        Objects.requireNonNull(jackpotData, "jackpotData must not be null for prepareAndCommitOutcome");
        SlotRewardFactory factory = (rewardFactory != null) ? rewardFactory : SlotRewardFactory.DEFAULT;

        // Idempotency: check both in-memory flag and persistent outbox presence
        if (this.finalized || jackpotData.hasPendingTransaction(rollId)) {
            jackpotData.claimJackpotForTransaction(rollId);
            jackpotData.removeActiveSession(playerUUID);
            jackpotData.setDirty();
            this.finalized = true;
            return;
        }

        List<ItemStack> rewardStacks = new ArrayList<>();
        boolean isJackpotHit = false;

        // 1. Grand Jackpot: Exactly 3 Stars
        if (outcome.isJackpot()) {
            isJackpotHit = true;
            long jackpotPoolAmount = (this.wonJackpotAmount > 0L)
                    ? this.wonJackpotAmount
                    : jackpotData.getJackpotAmount();
            this.wonJackpotAmount = jackpotPoolAmount;
            int betMultiplierPayout = (int) Math.round(betCount * outcome.getMultiplier());

            rewardStacks.addAll(ChipUtils.convertAmountToChips(jackpotPoolAmount));

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

            // Commemorative trophy token obtained directly via factory
            Item tokenItem = factory.getJackpotToken();
            ItemStack jackpotToken = new ItemStack(tokenItem, 1);
            CompoundTag tokenTag = jackpotToken.getOrCreateTag();
            String winnerName = this.playerName;
            if (winnerName == null || winnerName.isBlank() || "Unknown Player".equals(winnerName)) {
                winnerName = (playerNameFallback != null && !playerNameFallback.isBlank()) ? playerNameFallback : "Unknown Player";
            }
            tokenTag.putString("Winner", winnerName);
            tokenTag.putLong("JackpotAmount", jackpotPoolAmount);
            tokenTag.putString("Date", java.time.LocalDate.now().toString());
            rewardStacks.add(jackpotToken);
        } else if (outcome.isSkulls()) {
            // Disaster handled in delivery/effects phase (insurance does not protect against disaster)
        } else if (outcome.isWin()) {
            // 2. Regular Win
            if (betSnapshot.isItemBet()) {
                PayoutMode mode = PayoutMode.SAME_ITEM;
                if (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded()) {
                    mode = PayoutMode.fromId(PocketOddsConfig.SERVER.itemBetPayoutMode.get());
                }
                int maxStack = betSnapshot.getItemPrototype().getMaxStackSize();
                int globalCap = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                        ? PocketOddsConfig.SERVER.maxItemRewardCap.get() : 512;

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
                    double sameItemWeight = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                            ? PocketOddsConfig.SERVER.bothSameItemWeight.get() : 0.80;
                    double tableWeight = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                            ? PocketOddsConfig.SERVER.bothTableWeight.get() : 0.20;

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
                Item jokerItem = factory.getJokerItem();
                rewardStacks.add(new ItemStack(jokerItem, 1));
            }
        } else {
            // 3. Regular Loss - Check Insurance
            if (this.insured) {
                double refundRate = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                        ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
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
        }

        // STEP 1: Persist reward into transactional outbox before claiming jackpot or removing session
        if (!rewardStacks.isEmpty()) {
            JackpotSavedData.RewardTransaction tx = new JackpotSavedData.RewardTransaction(
                    rollId, playerUUID, rewardStacks, isJackpotHit, (isJackpotHit ? this.wonJackpotAmount : 0L), false
            );
            jackpotData.enqueueRewardTransaction(tx);
        }

        // STEP 2: Claim jackpot pool atomically and mark transaction claimed
        if (isJackpotHit) {
            jackpotData.claimJackpotForTransaction(rollId);
        }

        // STEP 3: Remove active session and commit state
        jackpotData.removeActiveSession(playerUUID);
        jackpotData.setDirty();

        // STEP 4: Set finalized flag strictly after transactional persistence
        this.finalized = true;
    }

    public void deliverCommittedReward(ServerPlayer player, RewardDeliverySink sink, JackpotSavedData jackpotData, MinecraftServer server) {
        boolean hasPendingTx = false;
        boolean allDelivered = true;

        if (jackpotData != null && (player != null || sink != null)) {
            RewardDeliverySink actualSink = (sink != null) ? sink : InventoryUtils::giveOrDrop;
            List<JackpotSavedData.RewardTransaction> pending = jackpotData.getPendingTransactions(playerUUID);
            for (JackpotSavedData.RewardTransaction tx : pending) {
                if (tx.getTransactionId().equals(rollId)) {
                    hasPendingTx = true;
                    boolean txFailed = false;
                    for (RewardLine line : tx.getLines()) {
                        if (line.isDelivered()) continue;
                        try {
                            actualSink.deliver(player, line.getStack());
                            jackpotData.confirmDeliveredLine(rollId, line.getLineId());
                        } catch (Exception e) {
                            LOGGER.error("Failed to deliver reward line {} for roll {} to player {}: {}",
                                    line.getLineId(), rollId, (player != null ? player.getScoreboardName() : playerUUID), e.getMessage(), e);
                            txFailed = true;
                            allDelivered = false;
                            break;
                        }
                    }
                    if (!txFailed && tx.isEmpty()) {
                        jackpotData.removePendingTransaction(rollId);
                    }
                    break;
                }
            }
        }

        if (player != null) {
            if (hasPendingTx && !allDelivered) {
                player.sendSystemMessage(Component.translatable("pocketodds.delivery.partial_queued").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
                FeedbackEffects.sendActionBar(player, Component.translatable("pocketodds.delivery.partial_queued").withStyle(ChatFormatting.GOLD));
                FeedbackEffects.playSound(player, SoundEvents.SHIELD_BLOCK, 1.0f, 0.8f);
                return;
            }

            if (outcome.isJackpot()) {
                long displayAmount = (this.wonJackpotAmount > 0L) ? this.wonJackpotAmount : 100L;
                int betMultiplierPayout = (int) Math.round(betCount * outcome.getMultiplier());
                if (betSnapshot.isItemBet()) {
                    FeedbackEffects.sendActionBar(player,
                            Component.translatable("pocketodds.item_bet.jackpot_won", displayAmount, betMultiplierPayout, betSnapshot.getItemPrototype().getHoverName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                } else {
                    FeedbackEffects.sendActionBar(player,
                            Component.translatable("pocketodds.jackpot.won", displayAmount + " + " + betMultiplierPayout).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                }
                FeedbackEffects.playSound(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                FeedbackEffects.spawnParticles(player, ParticleTypes.FIREWORK, 40, 0.5, 0.8, 0.5, 0.15);

                if (server != null) {
                    server.getPlayerList().broadcastSystemMessage(
                            Component.translatable("pocketodds.jackpot.broadcast", player.getScoreboardName(), displayAmount).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                            false
                    );
                }
            } else if (outcome.isSkulls()) {
                FeedbackEffects.sendActionBar(player, Component.translatable("pocketodds.skulls.curse").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                FeedbackEffects.playSound(player, SoundEvents.WITHER_SPAWN, 0.8f, 1.2f);
                FeedbackEffects.spawnParticles(player, ParticleTypes.SMOKE, 30, 0.5, 0.5, 0.5, 0.05);

                if (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded() && PocketOddsConfig.SERVER.allowDangerousEvents.get()) {
                    player.addEffect(new MobEffectInstance(MobEffects.WITHER, 160, 0));
                    player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 200, 0));

                    ServerLevel level = player.serverLevel();
                    WitherSkeleton skeleton = EntityType.WITHER_SKELETON.create(level);
                    if (skeleton != null) {
                        skeleton.moveTo(player.getX() + 1.5, player.getY(), player.getZ() + 1.5, 0, 0);
                        skeleton.setCustomName(Component.translatable("pocketodds.skulls.curse").withStyle(ChatFormatting.RED));
                        skeleton.setCustomNameVisible(true);
                        level.addFreshEntity(skeleton);
                    }
                }
            } else if (outcome.isWin()) {
                int payoutAmount = (int) Math.round(betCount * outcome.getMultiplier());
                Component winMsg;
                if (betSnapshot.isItemBet()) {
                    winMsg = Component.translatable("pocketodds.item_bet.win", payoutAmount, betSnapshot.getItemPrototype().getHoverName()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
                } else {
                    winMsg = Component.translatable("pocketodds.slot.win", "+" + payoutAmount + " " + betTier.getId()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
                }
                FeedbackEffects.sendActionBar(player, winMsg);
                FeedbackEffects.playSound(player, SoundEvents.PLAYER_LEVELUP, 0.9f, 1.2f);
                FeedbackEffects.spawnParticles(player, ParticleTypes.HAPPY_VILLAGER, 20, 0.5, 0.5, 0.5, 0.05);
            } else {
                // Loss - Check Insurance
                if (this.insured) {
                    double refundRate = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                            ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                    int refundAmount = Math.max(1, (int) Math.round(betCount * refundRate));
                    Component insMsg;
                    if (betSnapshot.isItemBet()) {
                        insMsg = Component.translatable("pocketodds.item_bet.insurance_refund", refundAmount, betSnapshot.getItemPrototype().getHoverName()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
                    } else {
                        insMsg = Component.translatable("pocketodds.insurance.triggered", refundAmount).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
                    }
                    FeedbackEffects.sendActionBar(player, insMsg);
                    FeedbackEffects.playSound(player, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
                    FeedbackEffects.spawnParticles(player, ParticleTypes.TOTEM_OF_UNDYING, 15, 0.3, 0.5, 0.3, 0.1);
                } else {
                    FeedbackEffects.sendActionBar(player, Component.translatable("pocketodds.slot.loss").withStyle(ChatFormatting.GRAY));
                    FeedbackEffects.playSound(player, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                }
            }
        }
    }

    public boolean finalizeOutcome(MinecraftServer server, ServerPlayer player) {
        ServerLevel overworld = (server != null) ? server.overworld() : null;
        JackpotSavedData jackpotData = (overworld != null) ? JackpotSavedData.get(overworld) : null;
        if (jackpotData == null) {
            LOGGER.error("Cannot finalize roll session {}: JackpotSavedData is null (overworld unavailable)!", rollId);
            return false;
        }

        String playerNameFallback = null;
        if (player != null) {
            playerNameFallback = player.getScoreboardName();
        } else if (server != null && server.getProfileCache() != null) {
            playerNameFallback = server.getProfileCache().get(playerUUID).map(GameProfile::getName).orElse("Unknown Player");
        }

        prepareAndCommitOutcome(jackpotData, playerNameFallback);
        deliverCommittedReward(player, InventoryUtils::giveOrDrop, jackpotData, server);
        return this.finalized;
    }
}
