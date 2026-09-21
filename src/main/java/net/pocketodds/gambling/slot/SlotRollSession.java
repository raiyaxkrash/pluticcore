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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.item.ChipTier;
import net.pocketodds.registration.ModItems;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import net.pocketodds.util.RewardDeliverySink;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SlotRollSession {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final UUID rollId;
    private final UUID playerUUID;
    private final String playerName;
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
        this(UUID.randomUUID(), playerUUID, null, betTier, betCount, symbols, outcome, false);
    }

    public SlotRollSession(UUID rollId, UUID playerUUID, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome) {
        this(rollId, playerUUID, null, betTier, betCount, symbols, outcome, false);
    }

    public SlotRollSession(UUID playerUUID, String playerName, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome) {
        this(UUID.randomUUID(), playerUUID, playerName, betTier, betCount, symbols, outcome, false);
    }

    public SlotRollSession(UUID rollId, UUID playerUUID, String playerName, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome) {
        this(rollId, playerUUID, playerName, betTier, betCount, symbols, outcome, false);
    }

    public SlotRollSession(UUID playerUUID, String playerName, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome, boolean insured) {
        this(UUID.randomUUID(), playerUUID, playerName, betTier, betCount, symbols, outcome, insured);
    }

    public SlotRollSession(UUID rollId, UUID playerUUID, String playerName, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome, boolean insured) {
        this.rollId = rollId != null ? rollId : UUID.randomUUID();
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.betTier = betTier;
        this.betCount = betCount;
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
        tag.putUUID("RollId", rollId);
        tag.putUUID("PlayerUUID", playerUUID);
        if (playerName != null && !playerName.isBlank()) {
            tag.putString("PlayerName", playerName);
        }
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
        ChipTier tier = ChipTier.fromId(tag.getString("BetTier"));
        int betCount = tag.getInt("BetCount");
        SlotSymbol s0 = SlotSymbol.valueOf(tag.getString("Sym0"));
        SlotSymbol s1 = SlotSymbol.valueOf(tag.getString("Sym1"));
        SlotSymbol s2 = SlotSymbol.valueOf(tag.getString("Sym2"));
        SlotSymbol[] syms = new SlotSymbol[]{s0, s1, s2};
        SlotOutcome outcome = SlotEvaluator.evaluate(syms, config);
        boolean insured = tag.getBoolean("Insured");
        SlotRollSession session = new SlotRollSession(rollId, uuid, playerName, tier, betCount, syms, outcome, insured);
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
            finalizeOutcome(server, player);
            finished = true;
        }
    }

    /**
     * Phase 1: Pure business and transactional commit phase.
     * Enqueues reward transaction into outbox, claims jackpot pool if won,
     * removes active session, and sets dirty.
     * Sets this.finalized = true STRICTLY AFTER successful commit.
     */
    public void prepareAndCommitOutcome(JackpotSavedData jackpotData, String playerNameFallback) {
        java.util.Objects.requireNonNull(jackpotData, "jackpotData must not be null for prepareAndCommitOutcome");

        // Idempotency: check both in-memory flag and persistent outbox presence
        if (this.finalized || jackpotData.hasPendingTransaction(rollId)) {
            // Safely complete unfulfilled jackpot pool reset if previous attempt crashed right after enqueuing transaction
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
            rewardStacks.addAll(ChipUtils.splitChips(betTier.getItem(), Math.max(1, betMultiplierPayout)));

            // Commemorative trophy token
            net.minecraft.world.item.Item tokenItem;
            try {
                tokenItem = ModItems.JACKPOT_TOKEN.get();
            } catch (Exception e) {
                tokenItem = net.minecraft.world.item.Items.GOLD_NUGGET;
            }
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
            // 3. Regular Win
            int payoutAmount = (int) Math.round(betCount * outcome.getMultiplier());
            if (payoutAmount < 1) payoutAmount = 1;
            rewardStacks.addAll(ChipUtils.splitChips(betTier.getItem(), payoutAmount));

            if (outcome.isThreeJokers()) {
                net.minecraft.world.item.Item jokerItem;
                try {
                    jokerItem = ModItems.JOKER.get();
                } catch (Exception e) {
                    jokerItem = net.minecraft.world.item.Items.PAPER;
                }
                rewardStacks.add(new ItemStack(jokerItem, 1));
            }
        } else {
            // 4. Regular Loss - Check Insurance
            // Игровое правило: Страховка защищает только от обычного проигрыша и НЕ защищает от Проклятия Азарта (трёх черепов / катастрофы).
            if (this.insured) {
                double refundRate = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                        ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                int refundAmount = Math.max(1, (int) Math.round(betCount * refundRate));
                rewardStacks.addAll(ChipUtils.splitChips(betTier.getItem(), refundAmount));
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

    /**
     * Phase 2: Deliver committed rewards to online player and trigger feedback effects.
     */
    public void deliverCommittedReward(ServerPlayer player, RewardDeliverySink sink, JackpotSavedData jackpotData, MinecraftServer server) {
        if (jackpotData != null && (player != null || sink != null)) {
            RewardDeliverySink actualSink = (sink != null) ? sink : InventoryUtils::giveOrDrop;
            List<JackpotSavedData.RewardTransaction> pending = jackpotData.getPendingTransactions(playerUUID);
            for (JackpotSavedData.RewardTransaction tx : pending) {
                if (tx.getTransactionId().equals(rollId)) {
                    boolean txFailed = false;
                    for (ItemStack stack : tx.getItems()) {
                        try {
                            actualSink.deliver(player, stack);
                            jackpotData.confirmDeliveredItem(rollId, stack);
                        } catch (Exception e) {
                            LOGGER.error("Failed to deliver reward item {} for roll {} to player {}: {}",
                                    stack, rollId, (player != null ? player.getScoreboardName() : playerUUID), e.getMessage(), e);
                            txFailed = true;
                            break;
                        }
                    }
                    if (!txFailed) {
                        jackpotData.removePendingTransaction(rollId);
                    }
                    break;
                }
            }
        }

        if (player != null) {
            if (outcome.isJackpot()) {
                long displayAmount = (this.wonJackpotAmount > 0L) ? this.wonJackpotAmount : 100L;
                int betMultiplierPayout = (int) Math.round(betCount * outcome.getMultiplier());
                FeedbackEffects.sendActionBar(player,
                        Component.translatable("pocketodds.jackpot.won", displayAmount + " + " + betMultiplierPayout).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
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
                        skeleton.setCustomName(Component.literal("§cПроклятие Азарта"));
                        skeleton.setCustomNameVisible(true);
                        level.addFreshEntity(skeleton);
                    }
                }
            } else if (outcome.isWin()) {
                int payoutAmount = (int) Math.round(betCount * outcome.getMultiplier());
                Component winMsg = Component.translatable("pocketodds.slot.win", "+" + payoutAmount + " " + betTier.getId()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
                FeedbackEffects.sendActionBar(player, winMsg);
                FeedbackEffects.playSound(player, SoundEvents.PLAYER_LEVELUP, 0.9f, 1.2f);
                FeedbackEffects.spawnParticles(player, ParticleTypes.HAPPY_VILLAGER, 20, 0.5, 0.5, 0.5, 0.05);
            } else {
                // Loss - Check Insurance
                if (this.insured) {
                    double refundRate = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                            ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                    int refundAmount = Math.max(1, (int) Math.round(betCount * refundRate));
                    FeedbackEffects.sendActionBar(player, Component.translatable("pocketodds.insurance.triggered", refundAmount).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                    FeedbackEffects.playSound(player, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
                    FeedbackEffects.spawnParticles(player, ParticleTypes.TOTEM_OF_UNDYING, 15, 0.3, 0.5, 0.3, 0.1);
                } else {
                    FeedbackEffects.sendActionBar(player, Component.translatable("pocketodds.slot.loss").withStyle(ChatFormatting.GRAY));
                    FeedbackEffects.playSound(player, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                }
            }
        }
    }

    public void finalizeOutcome(MinecraftServer server, ServerPlayer player) {
        ServerLevel overworld = (server != null) ? server.overworld() : null;
        JackpotSavedData jackpotData = (overworld != null) ? JackpotSavedData.get(overworld) : null;
        if (jackpotData == null) {
            LOGGER.error("Cannot finalize roll session {}: JackpotSavedData is null (overworld unavailable)!", rollId);
            return;
        }

        String playerNameFallback = null;
        if (player != null) {
            playerNameFallback = player.getScoreboardName();
        } else if (server != null && server.getProfileCache() != null) {
            playerNameFallback = server.getProfileCache().get(playerUUID).map(GameProfile::getName).orElse("Unknown Player");
        }

        prepareAndCommitOutcome(jackpotData, playerNameFallback);
        deliverCommittedReward(player, InventoryUtils::giveOrDrop, jackpotData, server);
    }
}
