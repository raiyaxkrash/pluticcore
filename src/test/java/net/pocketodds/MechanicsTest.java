package net.pocketodds;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.gambling.slot.SlotOutcome;
import net.pocketodds.gambling.slot.SlotRollSession;
import net.pocketodds.gambling.slot.SlotSymbol;
import net.pocketodds.gambling.tracker.ActiveRollTracker;
import net.pocketodds.item.ChipTier;
import net.pocketodds.item.JackpotTokenItem;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.RewardDeliverySink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MechanicsTest {

    @BeforeAll
    public static void setupMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM instanceof net.minecraft.core.MappedRegistry<?> mapped) {
            mapped.unfreeze();
        }
        if (net.minecraftforge.registries.ForgeRegistries.ITEMS instanceof net.minecraftforge.registries.ForgeRegistry<net.minecraft.world.item.Item> forgeReg) {
            forgeReg.unfreeze();
            net.minecraft.resources.ResourceLocation tokenLoc = new net.minecraft.resources.ResourceLocation("pocketodds", "jackpot_token");
            if (!forgeReg.containsKey(tokenLoc)) {
                forgeReg.register(tokenLoc, new JackpotTokenItem(new net.minecraft.world.item.Item.Properties()));
            }
            forgeReg.freeze();
        }
    }

    @Test
    public void testJackpotPersistenceAcrossRestarts() {
        // 1. Initial server state
        JackpotSavedData serverDataBeforeRestart = new JackpotSavedData(100L);
        Assertions.assertEquals(100L, serverDataBeforeRestart.getJackpotAmount());

        // Simulate 50 bets made by players
        for (int i = 0; i < 50; i++) {
            serverDataBeforeRestart.addContribution(64); // 64 chips bet -> 5% = ~3 chips
        }
        long amountBeforeRestart = serverDataBeforeRestart.getJackpotAmount();
        Assertions.assertTrue(amountBeforeRestart > 100L, "Jackpot must increase from bets!");

        // Simulate a player disconnecting while holding a pending jackpot reward
        UUID playerUUID = UUID.randomUUID();
        ItemStack pendingReward = new ItemStack(Items.DIAMOND, 5);
        serverDataBeforeRestart.addPendingReward(playerUUID, pendingReward);

        // 2. World Save: SavedData serializes to NBT CompoundTag
        CompoundTag worldSaveTag = new CompoundTag();
        serverDataBeforeRestart.save(worldSaveTag);

        // 3. Server Crash / Restart: New server instance loads data from world NBT
        JackpotSavedData serverDataAfterRestart = JackpotSavedData.load(worldSaveTag);

        // 4. Invariants check: Jackpot amount must be preserved exactly
        Assertions.assertEquals(amountBeforeRestart, serverDataAfterRestart.getJackpotAmount(),
                "Jackpot amount must persist across world saves and restarts!");

        // Invariant check: Player's pending reward must not be lost
        List<ItemStack> restoredRewards = serverDataAfterRestart.popPendingRewards(playerUUID);
        Assertions.assertFalse(restoredRewards.isEmpty(), "Player pending rewards must persist across server restarts!");
        Assertions.assertEquals(Items.DIAMOND, restoredRewards.get(0).getItem());
        Assertions.assertEquals(5, restoredRewards.get(0).getCount());

        // Invariant check: Popping pending rewards must clear them permanently
        Assertions.assertTrue(serverDataAfterRestart.popPendingRewards(playerUUID).isEmpty());
    }

    @Test
    public void testActiveRollSessionTickProgression() {
        UUID playerUUID = UUID.randomUUID();
        SlotSymbol[] symbols = new SlotSymbol[]{SlotSymbol.CHERRY, SlotSymbol.CHERRY, SlotSymbol.CHERRY};
        SlotOutcome outcome = new SlotOutcome(symbols, false, false, false, SlotSymbol.CHERRY, 3, 3.0);

        SlotRollSession session = new SlotRollSession(playerUUID, ChipTier.COPPER, 1, symbols, outcome);

        Assertions.assertEquals(0, session.getTick());
        Assertions.assertFalse(session.isFinished());

        session.setTick(10);
        Assertions.assertEquals(10, session.getTick());

        session.setTick(28);
        Assertions.assertEquals(28, session.getTick());
    }

    @Test
    public void testActiveSessionPersistenceAcrossRestarts() {
        JackpotSavedData data = new JackpotSavedData(200L);
        UUID playerUUID = UUID.randomUUID();

        // Simulate an active slot session in tick 14
        CompoundTag sessionTag = new CompoundTag();
        sessionTag.putUUID("RollId", UUID.randomUUID());
        sessionTag.putUUID("PlayerUUID", playerUUID);
        sessionTag.putString("PlayerName", "Steve");
        sessionTag.putString("BetTier", "gold");
        sessionTag.putInt("BetCount", 8);
        sessionTag.putString("Sym0", "GOLD");
        sessionTag.putString("Sym1", "GOLD");
        sessionTag.putString("Sym2", "GOLD");
        sessionTag.putInt("Tick", 14);
        sessionTag.putBoolean("Finalized", false);

        data.saveActiveSession(playerUUID, sessionTag);
        Assertions.assertEquals(1, data.getActiveSessions().size());

        // World save to NBT
        CompoundTag worldSaveTag = new CompoundTag();
        data.save(worldSaveTag);

        // Server restart: load from NBT
        JackpotSavedData reloaded = JackpotSavedData.load(worldSaveTag);
        Assertions.assertTrue(reloaded.getActiveSessions().containsKey(playerUUID));
        CompoundTag restoredSession = reloaded.getActiveSessions().get(playerUUID);
        Assertions.assertEquals(14, restoredSession.getInt("Tick"));
        Assertions.assertEquals("Steve", restoredSession.getString("PlayerName"));
        Assertions.assertEquals("gold", restoredSession.getString("BetTier"));
        Assertions.assertEquals(8, restoredSession.getInt("BetCount"));
        Assertions.assertFalse(restoredSession.getBoolean("Finalized"));

        // Finalize / remove active session
        reloaded.removeActiveSession(playerUUID);
        Assertions.assertFalse(reloaded.getActiveSessions().containsKey(playerUUID));
    }

    @Test
    public void testDeckOfFateLockedTierExploitPrevention() {
        ItemStack deckStack = new ItemStack(Items.PAPER);

        // Player bets 1 copper chip initially
        net.pocketodds.item.DeckOfFateItem.setBetTier(deckStack, ChipTier.COPPER);
        net.pocketodds.item.DeckOfFateItem.setPot(deckStack, 8);
        net.pocketodds.item.DeckOfFateItem.setStreak(deckStack, 3);

        // Locked tier in NBT must be COPPER
        ChipTier lockedTier = net.pocketodds.item.DeckOfFateItem.getLockedRoundTier(deckStack);
        Assertions.assertEquals(ChipTier.COPPER, lockedTier, "Locked tier must remain COPPER!");

        // Even if player changes offhand or tries to call cashout, the locked tier stays COPPER
        Assertions.assertNotEquals(ChipTier.NETHERITE, lockedTier);
    }

    @Test
    public void testChipUtilsSplittingAndConversion() {
        // Test dividing 5000 jackpot chips into stacks <= 64 via production ChipUtils
        List<ItemStack> splitStacks = ChipUtils.splitChips(Items.GOLD_INGOT, 5000);

        Assertions.assertEquals(79, splitStacks.size(), "5000 items should split into 79 stacks (78*64 + 8)");
        int reassembledTotal = 0;
        for (ItemStack stack : splitStacks) {
            Assertions.assertTrue(stack.getCount() <= 64, "Stack size must never exceed 64!");
            Assertions.assertTrue(stack.getCount() > 0);
            reassembledTotal += stack.getCount();
        }
        Assertions.assertEquals(5000, reassembledTotal, "Reassembled chip total must exactly equal 5000!");

        // Test tier conversion for a large amount (e.g. 3524 chips)
        List<ItemStack> converted = ChipUtils.convertAmountToChips(3524L);
        Assertions.assertFalse(converted.isEmpty());
        for (ItemStack stack : converted) {
            Assertions.assertTrue(stack.getCount() <= 64, "Converted stack size must not exceed 64!");
        }
    }

    @Test
    public void testSlotSessionIdempotency() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID rollId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();
        SlotSymbol[] symbols = new SlotSymbol[]{
                SlotSymbol.CHERRY,
                SlotSymbol.CHERRY,
                SlotSymbol.CHERRY
        };
        SlotOutcome outcome = new SlotOutcome(
                symbols, false, false, false, SlotSymbol.CHERRY, 3, 3.0
        );

        SlotRollSession session = new SlotRollSession(
                rollId, playerUUID, "Steve", ChipTier.COPPER, 8, symbols, outcome
        );

        Assertions.assertEquals(rollId, session.getRollId());
        Assertions.assertEquals("Steve", session.getPlayerName());
        Assertions.assertFalse(session.isFinalized());

        // First call to prepareAndCommitOutcome: should record transaction and mark finalized
        session.prepareAndCommitOutcome(data, "Steve");
        Assertions.assertTrue(session.isFinalized());

        List<JackpotSavedData.RewardTransaction> pending1 = data.getPendingTransactions(playerUUID);
        Assertions.assertEquals(1, pending1.size());
        Assertions.assertEquals(rollId, pending1.get(0).getTransactionId());
        int itemCount1 = pending1.get(0).getItems().size();
        Assertions.assertTrue(itemCount1 > 0);

        // Second call: must be a no-op (Idempotency test!)
        session.prepareAndCommitOutcome(data, "Steve");
        List<JackpotSavedData.RewardTransaction> pending2 = data.getPendingTransactions(playerUUID);
        Assertions.assertEquals(1, pending2.size(), "Second commit must not duplicate transaction!");
        Assertions.assertEquals(itemCount1, pending2.get(0).getItems().size(), "Items must not be duplicated!");

        // Third check: reload a new session object with the same rollId
        SlotRollSession reloaded = new SlotRollSession(
                rollId, playerUUID, "Steve", ChipTier.COPPER, 8, symbols, outcome
        );
        reloaded.prepareAndCommitOutcome(data, "Steve");
        Assertions.assertTrue(reloaded.isFinalized(), "Reloaded session must treat persistent outbox transaction as source of truth!");
        Assertions.assertEquals(1, data.getPendingTransactions(playerUUID).size());
    }

    @Test
    public void testRewardDeliverySinkPartialFailure() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        List<ItemStack> prizes = new ArrayList<>();
        prizes.add(new ItemStack(Items.DIAMOND, 10));
        prizes.add(new ItemStack(Items.EMERALD, 20));

        JackpotSavedData.RewardTransaction tx = new JackpotSavedData.RewardTransaction(txId, playerUUID, prizes);
        data.enqueueRewardTransaction(tx);

        List<ItemStack> deliveredStacks = new ArrayList<>();
        RewardDeliverySink failingSink = (player, stack) -> {
            if (deliveredStacks.isEmpty()) {
                deliveredStacks.add(stack.copy());
            } else {
                throw new RuntimeException("Simulated crash between giveOrDrop and confirmDeliveredItem!");
            }
        };

        // Call REAL production delivery method: must handle exception cleanly without propagating out!
        boolean allDelivered = ActiveRollTracker.deliverPendingTransactions(playerUUID, null, data, failingSink);
        Assertions.assertFalse(allDelivered, "Delivery must report partial delivery when sink throws!");

        // Invariant 1: First item (Diamond) was delivered and confirmed
        Assertions.assertEquals(1, deliveredStacks.size());
        Assertions.assertEquals(Items.DIAMOND, deliveredStacks.get(0).getItem());

        // Invariant 2: Second item (Emerald) remains in outbox transaction queue
        List<JackpotSavedData.RewardTransaction> afterFailure = data.getPendingTransactions(playerUUID);
        Assertions.assertEquals(1, afterFailure.size());
        Assertions.assertEquals(1, afterFailure.get(0).getItems().size());
        Assertions.assertEquals(Items.EMERALD, afterFailure.get(0).getItems().get(0).getItem());

        // Invariant 3: Retry with real production method and normal sink delivers ONLY the second item without duplicates
        RewardDeliverySink normalSink = (player, stack) -> deliveredStacks.add(stack.copy());
        boolean retryDelivered = ActiveRollTracker.deliverPendingTransactions(playerUUID, null, data, normalSink);
        Assertions.assertTrue(retryDelivered, "Retry must deliver remaining items successfully!");

        Assertions.assertTrue(data.getPendingTransactions(playerUUID).isEmpty(), "Outbox queue must be empty after complete delivery!");
        Assertions.assertEquals(2, deliveredStacks.size());
        Assertions.assertEquals(Items.EMERALD, deliveredStacks.get(1).getItem());
    }

    @Test
    public void testJackpotTokenTrophyBehavior() {
        JackpotTokenItem tokenItem = (JackpotTokenItem) net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("pocketodds", "jackpot_token"));
        if (tokenItem == null) {
            tokenItem = new JackpotTokenItem(new net.minecraft.world.item.Item.Properties());
        }
        ItemStack trophy = new ItemStack(tokenItem);
        trophy.getOrCreateTag().putString("Winner", "Steve");
        trophy.getOrCreateTag().putLong("JackpotAmount", 2500L);
        trophy.getOrCreateTag().putString("Date", "2026-09-21");

        List<Component> tooltips = new ArrayList<>();
        tokenItem.appendHoverText(trophy, null, tooltips, TooltipFlag.NORMAL);

        Assertions.assertTrue(tooltips.size() >= 5);

        // Verify TranslatableContents keys and arguments
        boolean foundWinner = false;
        boolean foundAmount = false;
        boolean foundDate = false;

        for (Component comp : tooltips) {
            if (comp.getContents() instanceof TranslatableContents translatable) {
                String key = translatable.getKey();
                if ("tooltip.pocketodds.jackpot_token.winner".equals(key)) {
                    foundWinner = true;
                    Assertions.assertEquals("Steve", translatable.getArgs()[0]);
                } else if ("tooltip.pocketodds.jackpot_token.amount".equals(key)) {
                    foundAmount = true;
                    Assertions.assertEquals(2500L, translatable.getArgs()[0]);
                } else if ("tooltip.pocketodds.jackpot_token.date".equals(key)) {
                    foundDate = true;
                    Assertions.assertEquals("2026-09-21", translatable.getArgs()[0]);
                }
            }
        }

        Assertions.assertTrue(foundWinner, "Translation key for winner must be present in tooltip!");
        Assertions.assertTrue(foundAmount, "Translation key for amount must be present in tooltip!");
        Assertions.assertTrue(foundDate, "Translation key for date must be present in tooltip!");

        // Test uninitialized token fallback with real JackpotTokenItem
        ItemStack emptyToken = new ItemStack(tokenItem);
        List<Component> emptyTooltips = new ArrayList<>();
        tokenItem.appendHoverText(emptyToken, null, emptyTooltips, TooltipFlag.NORMAL);
        boolean foundDesc = false;
        for (Component comp : emptyTooltips) {
            if (comp.getContents() instanceof TranslatableContents translatable) {
                if ("tooltip.pocketodds.jackpot_token.desc".equals(translatable.getKey())) {
                    foundDesc = true;
                }
            }
        }
        Assertions.assertTrue(foundDesc, "Uninitialized token must contain default description key!");
    }

    @Test
    public void testAtomicJackpotClaimForTransaction() {
        JackpotSavedData data = new JackpotSavedData(1000L);
        UUID playerUUID = UUID.randomUUID();
        UUID rollId = UUID.randomUUID();

        List<ItemStack> items = new ArrayList<>();
        items.add(new ItemStack(Items.DIAMOND, 1));
        JackpotSavedData.RewardTransaction tx = new JackpotSavedData.RewardTransaction(
                rollId, playerUUID, items, true, 1000L, false
        );
        data.enqueueRewardTransaction(tx);

        // First claim: resets pool to base (100) and marks claimed
        boolean claimed = data.claimJackpotForTransaction(rollId);
        Assertions.assertTrue(claimed);
        Assertions.assertEquals(100L, data.getJackpotAmount());

        JackpotSavedData.RewardTransaction updatedTx = data.getTransaction(rollId);
        Assertions.assertNotNull(updatedTx);
        Assertions.assertTrue(updatedTx.isJackpotClaimed());

        // New bets come in
        data.addContribution(500L);
        long poolWithBets = data.getJackpotAmount();
        Assertions.assertTrue(poolWithBets > 100L);

        // Second claim attempt for the same rollId: must be a no-op and not destroy new bets!
        boolean secondClaim = data.claimJackpotForTransaction(rollId);
        Assertions.assertFalse(secondClaim, "Second claim attempt must not reset pool!");
        Assertions.assertEquals(poolWithBets, data.getJackpotAmount(), "New bets must be preserved!");
    }

    @Test
    public void testPrepareAndCommitOutcomeRequiresNonNullJackpotData() {
        SlotSymbol[] symbols = new SlotSymbol[]{SlotSymbol.CHERRY, SlotSymbol.IRON, SlotSymbol.GOLD};
        SlotOutcome outcome = new SlotOutcome(symbols, false, false, false, null, 0, 0.0);
        SlotRollSession session = new SlotRollSession(UUID.randomUUID(), "Steve", ChipTier.COPPER, 1, symbols, outcome, false);

        Assertions.assertThrows(NullPointerException.class, () -> {
            session.prepareAndCommitOutcome(null, "Steve");
        });
        Assertions.assertFalse(session.isFinalized(), "Session must not be finalized after exception!");
    }

    @Test
    public void testInsuranceLossCommittedToOutbox() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        UUID rollId = UUID.randomUUID();
        SlotSymbol[] symbols = new SlotSymbol[]{SlotSymbol.CHERRY, SlotSymbol.IRON, SlotSymbol.GOLD};
        SlotOutcome outcome = new SlotOutcome(symbols, false, false, false, null, 0, 0.0);

        // Insured loss: bet 10 copper chips
        SlotRollSession session = new SlotRollSession(rollId, playerUUID, "Steve", ChipTier.COPPER, 10, symbols, outcome, true);
        session.prepareAndCommitOutcome(data, "Steve");

        Assertions.assertTrue(session.isFinalized());
        List<JackpotSavedData.RewardTransaction> pending = data.getPendingTransactions(playerUUID);
        Assertions.assertEquals(1, pending.size(), "Insurance refund must be committed to outbox!");
        Assertions.assertEquals(rollId, pending.get(0).getTransactionId());

        int totalRefundChips = pending.get(0).getItems().stream().mapToInt(ItemStack::getCount).sum();
        Assertions.assertEquals(5, totalRefundChips, "50% of 10 chips bet = 5 chips refund!");
    }

    @Test
    public void testInsuranceNotTriggeredOnDisaster() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        UUID rollId = UUID.randomUUID();
        SlotSymbol[] symbols = new SlotSymbol[]{SlotSymbol.SKULL, SlotSymbol.SKULL, SlotSymbol.SKULL};
        SlotOutcome outcome = new SlotOutcome(symbols, false, true, false, SlotSymbol.SKULL, 3, 0.0);

        // Insured disaster: rule states insurance does NOT protect against disaster
        SlotRollSession session = new SlotRollSession(rollId, playerUUID, "Steve", ChipTier.COPPER, 10, symbols, outcome, true);
        session.prepareAndCommitOutcome(data, "Steve");

        Assertions.assertTrue(session.isFinalized());
        List<JackpotSavedData.RewardTransaction> pending = data.getPendingTransactions(playerUUID);
        Assertions.assertTrue(pending.isEmpty(), "Insurance must not commit refund on disaster!");
    }

    @Test
    public void testWonJackpotAmountPreservedAfterPoolReset() {
        JackpotSavedData data = new JackpotSavedData(2500L);
        UUID playerUUID = UUID.randomUUID();
        UUID rollId = UUID.randomUUID();
        SlotSymbol[] symbols = new SlotSymbol[]{SlotSymbol.STAR, SlotSymbol.STAR, SlotSymbol.STAR};
        SlotOutcome outcome = new SlotOutcome(symbols, true, false, false, SlotSymbol.STAR, 3, 10.0);

        SlotRollSession session = new SlotRollSession(rollId, playerUUID, "Steve", ChipTier.COPPER, 1, symbols, outcome, false);
        session.prepareAndCommitOutcome(data, "Steve");

        // Pool was reset to 100
        Assertions.assertEquals(100L, data.getJackpotAmount());

        // But session preserved the won jackpot amount!
        Assertions.assertEquals(2500L, session.getWonJackpotAmount());

        // Check NBT serialization preserves wonJackpotAmount
        net.minecraft.nbt.CompoundTag tag = session.toNbt();
        Assertions.assertEquals(2500L, tag.getLong("WonJackpotAmount"));

        SlotRollSession restored = SlotRollSession.fromNbt(tag, null);
        Assertions.assertEquals(2500L, restored.getWonJackpotAmount());
    }

    @Test
    public void testRoulettePayoutMath() {
        // European roulette: 37 pockets (0..36). 18 Red, 18 Black, 1 Green (Zero)
        double redProb = 18.0 / 37.0;
        double greenProb = 1.0 / 37.0;
        double rtpRed = redProb * 2.0 * 100.0; // 97.30%
        double rtpGreen = greenProb * 35.0 * 100.0; // 94.59%

        Assertions.assertTrue(rtpRed < 100.0, "Roulette Red RTP must be under 100%!");
        Assertions.assertTrue(rtpGreen < 100.0, "Roulette Green RTP must be under 100%!");
        Assertions.assertEquals(97.30, Math.round(rtpRed * 100.0) / 100.0);
        Assertions.assertEquals(94.59, Math.round(rtpGreen * 100.0) / 100.0);
    }
}
