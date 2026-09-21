package net.pocketodds.gambling.slot;

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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.config.PocketOddsConfig;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.item.ChipTier;
import net.pocketodds.registration.ModItems;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SlotRollSession {
    private final UUID rollId;
    private final UUID playerUUID;
    private final ChipTier betTier;
    private final int betCount;
    private final SlotSymbol[] symbols;
    private final SlotOutcome outcome;
    private int tick = 0;
    private boolean finished = false;
    private boolean finalized = false;

    public SlotRollSession(UUID playerUUID, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome) {
        this(UUID.randomUUID(), playerUUID, betTier, betCount, symbols, outcome);
    }

    public SlotRollSession(UUID rollId, UUID playerUUID, ChipTier betTier, int betCount, SlotSymbol[] symbols, SlotOutcome outcome) {
        this.rollId = rollId != null ? rollId : UUID.randomUUID();
        this.playerUUID = playerUUID;
        this.betTier = betTier;
        this.betCount = betCount;
        this.symbols = symbols;
        this.outcome = outcome;
    }

    public UUID getRollId() {
        return rollId;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public boolean isFinished() {
        return finished;
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
        tag.putString("BetTier", betTier.getId());
        tag.putInt("BetCount", betCount);
        tag.putString("Sym0", symbols[0].name());
        tag.putString("Sym1", symbols[1].name());
        tag.putString("Sym2", symbols[2].name());
        tag.putInt("Tick", tick);
        tag.putBoolean("Finalized", finalized);
        return tag;
    }

    public static SlotRollSession fromNbt(CompoundTag tag, PocketOddsConfig.Server config) {
        UUID rollId = tag.contains("RollId") ? tag.getUUID("RollId") : UUID.randomUUID();
        UUID uuid = tag.getUUID("PlayerUUID");
        ChipTier tier = ChipTier.fromId(tag.getString("BetTier"));
        int betCount = tag.getInt("BetCount");
        SlotSymbol s0 = SlotSymbol.valueOf(tag.getString("Sym0"));
        SlotSymbol s1 = SlotSymbol.valueOf(tag.getString("Sym1"));
        SlotSymbol s2 = SlotSymbol.valueOf(tag.getString("Sym2"));
        SlotSymbol[] syms = new SlotSymbol[]{s0, s1, s2};
        SlotOutcome outcome = SlotEvaluator.evaluate(syms, config);
        SlotRollSession session = new SlotRollSession(rollId, uuid, tier, betCount, syms, outcome);
        session.setTick(tag.getInt("Tick"));
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
            FeedbackEffects.spawnParticles(player, ParticleTypes.CRIT, 8, 0.3, 0.3, 0.3, 0.05);
        } else if (tick == 16 && player != null) {
            Component display = Component.literal("[ 🎰  ").withStyle(ChatFormatting.GOLD)
                    .append(symbols[0].getShortDisplay())
                    .append(Component.literal("  |  ").withStyle(ChatFormatting.GOLD))
                    .append(symbols[1].getShortDisplay())
                    .append(Component.literal("  |  ❓  ]").withStyle(ChatFormatting.GOLD));
            FeedbackEffects.sendActionBar(player, display);
            FeedbackEffects.playSound(player, SoundEvents.NOTE_BLOCK_PLING.get(), 1.0f, 1.0f);
            FeedbackEffects.spawnParticles(player, ParticleTypes.CRIT, 12, 0.3, 0.3, 0.3, 0.05);
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

    public void finalizeOutcome(MinecraftServer server, ServerPlayer player) {
        if (this.finalized) {
            return; // Idempotency check: never pay out the same session twice
        }
        this.finalized = true;

        ServerLevel overworld = server.overworld();
        JackpotSavedData jackpotData = JackpotSavedData.get(overworld);

        List<ItemStack> rewardStacks = new ArrayList<>();
        boolean isJackpotHit = false;
        long jackpotPoolAmount = 0L;
        int betMultiplierPayout = 0;

        // 1. Grand Jackpot: Exactly 3 Stars
        if (outcome.isJackpot()) {
            isJackpotHit = true;
            jackpotPoolAmount = jackpotData.getJackpotAmount();
            betMultiplierPayout = (int) Math.round(betCount * outcome.getMultiplier());

            List<ItemStack> poolStacks = convertAmountToChips(jackpotPoolAmount);
            rewardStacks.addAll(poolStacks);
            addSplitChips(rewardStacks, betTier.getItem(), Math.max(1, betMultiplierPayout));

            // Commemorative trophy token
            ItemStack jackpotToken = new ItemStack(ModItems.JACKPOT_TOKEN.get(), 1);
            CompoundTag tokenTag = jackpotToken.getOrCreateTag();
            String winnerName = player != null ? player.getScoreboardName() : "Unknown Player";
            tokenTag.putString("Winner", winnerName);
            tokenTag.putLong("JackpotAmount", jackpotPoolAmount);
            tokenTag.putString("Date", java.time.LocalDate.now().toString());
            rewardStacks.add(jackpotToken);
        } else if (outcome.isSkulls()) {
            // 2. Disaster: Exactly 3 Skulls
            if (player != null) {
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
            }
        } else if (outcome.isWin()) {
            // 3. Regular Win
            int payoutAmount = (int) Math.round(betCount * outcome.getMultiplier());
            if (payoutAmount < 1) payoutAmount = 1;
            addSplitChips(rewardStacks, betTier.getItem(), payoutAmount);

            if (outcome.isThreeJokers()) {
                rewardStacks.add(new ItemStack(ModItems.JOKER.get(), 1));
            }
        } else {
            // 4. Loss - Check Insurance
            if (player != null && InventoryUtils.hasInsurance(player)) {
                InventoryUtils.consumeInsurance(player);
                double refundRate = (PocketOddsConfig.SERVER != null && PocketOddsConfig.isConfigLoaded())
                        ? PocketOddsConfig.SERVER.insuranceRefundRate.get() : 0.50;
                int refundAmount = Math.max(1, (int) Math.round(betCount * refundRate));
                addSplitChips(rewardStacks, betTier.getItem(), refundAmount);
            }
        }

        // STEP 1: Persist reward into transactional outbox before claiming jackpot or removing session
        if (!rewardStacks.isEmpty()) {
            JackpotSavedData.RewardTransaction tx = new JackpotSavedData.RewardTransaction(rollId, playerUUID, rewardStacks);
            jackpotData.enqueueRewardTransaction(tx);
        }

        // STEP 2: Claim jackpot pool safely after reward is securely enqueued
        if (isJackpotHit) {
            jackpotData.claimJackpot();
        }

        // STEP 3: Remove active session and commit state
        jackpotData.removeActiveSession(playerUUID);
        jackpotData.setDirty();

        // STEP 4: Deliver rewards to online player with item-by-item confirmation
        if (player != null) {
            if (!rewardStacks.isEmpty()) {
                for (ItemStack stack : rewardStacks) {
                    InventoryUtils.giveOrDrop(player, stack);
                    jackpotData.confirmDeliveredItem(rollId, stack);
                }
                jackpotData.removePendingTransaction(rollId);
            }

            if (isJackpotHit) {
                FeedbackEffects.sendActionBar(player,
                        Component.translatable("pocketodds.jackpot.won", jackpotPoolAmount + " + " + betMultiplierPayout).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                FeedbackEffects.playSound(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                FeedbackEffects.spawnParticles(player, ParticleTypes.FIREWORK, 40, 0.5, 0.8, 0.5, 0.15);

                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable("pocketodds.jackpot.broadcast", player.getScoreboardName(), jackpotPoolAmount).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                        false
                );
            } else if (outcome.isWin()) {
                int payoutAmount = (int) Math.round(betCount * outcome.getMultiplier());
                Component winMsg = Component.translatable("pocketodds.slot.win", "+" + payoutAmount + " " + betTier.getId()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
                FeedbackEffects.sendActionBar(player, winMsg);
                FeedbackEffects.playSound(player, SoundEvents.PLAYER_LEVELUP, 0.9f, 1.2f);
                FeedbackEffects.spawnParticles(player, ParticleTypes.HAPPY_VILLAGER, 20, 0.5, 0.5, 0.5, 0.05);
            } else if (!outcome.isSkulls()) {
                if (!rewardStacks.isEmpty()) {
                    FeedbackEffects.sendActionBar(player, Component.translatable("pocketodds.insurance.triggered", rewardStacks.get(0).getCount()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                    FeedbackEffects.playSound(player, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
                    FeedbackEffects.spawnParticles(player, ParticleTypes.TOTEM_OF_UNDYING, 15, 0.3, 0.5, 0.3, 0.1);
                } else {
                    FeedbackEffects.sendActionBar(player, Component.translatable("pocketodds.slot.loss").withStyle(ChatFormatting.GRAY));
                    FeedbackEffects.playSound(player, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                }
            }
        }
    }

    private static void addSplitChips(List<ItemStack> list, Item item, int totalCount) {
        int remaining = totalCount;
        while (remaining > 0) {
            int count = Math.min(remaining, 64);
            list.add(new ItemStack(item, count));
            remaining -= count;
        }
    }

    private List<ItemStack> convertAmountToChips(long amount) {
        List<ItemStack> list = new ArrayList<>();
        long remaining = amount;

        int netheriteCount = (int) (remaining / ChipTier.NETHERITE.getBaseValue());
        if (netheriteCount > 0) {
            addSplitChips(list, ModItems.NETHERITE_CHIP.get(), netheriteCount);
            remaining %= ChipTier.NETHERITE.getBaseValue();
        }

        int diamondCount = (int) (remaining / ChipTier.DIAMOND.getBaseValue());
        if (diamondCount > 0) {
            addSplitChips(list, ModItems.DIAMOND_CHIP.get(), diamondCount);
            remaining %= ChipTier.DIAMOND.getBaseValue();
        }

        int goldCount = (int) (remaining / ChipTier.GOLD.getBaseValue());
        if (goldCount > 0) {
            addSplitChips(list, ModItems.GOLD_CHIP.get(), goldCount);
            remaining %= ChipTier.GOLD.getBaseValue();
        }

        int copperCount = (int) remaining;
        if (copperCount > 0) {
            addSplitChips(list, ModItems.COPPER_CHIP.get(), copperCount);
        }

        return list;
    }
}
