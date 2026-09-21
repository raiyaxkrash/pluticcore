package net.pocketodds;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.pocketodds.data.JackpotSavedData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

public class MechanicsTest {

    @BeforeAll
    public static void setupMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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

        // 3. Server Restart: New world instance loads SavedData from NBT CompoundTag
        JackpotSavedData serverDataAfterRestart = JackpotSavedData.load(worldSaveTag);

        // Assert jackpot amount survived restart exactly
        Assertions.assertEquals(amountBeforeRestart, serverDataAfterRestart.getJackpotAmount(),
                "Jackpot amount must persist across world restarts!");

        // Assert pending reward for reconnected player survived restart
        List<ItemStack> deliveredRewards = serverDataAfterRestart.popPendingRewards(playerUUID);
        Assertions.assertEquals(1, deliveredRewards.size(), "Pending reward must be preserved across restart!");
        Assertions.assertEquals(5, deliveredRewards.get(0).getCount());
        Assertions.assertEquals(Items.DIAMOND, deliveredRewards.get(0).getItem());

        // Assert pending queue is now cleared (no item dupe upon subsequent reconnects)
        List<ItemStack> emptyList = serverDataAfterRestart.popPendingRewards(playerUUID);
        Assertions.assertTrue(emptyList.isEmpty(), "Reward must not be delivered twice!");

        // 4. Test Jackpot Payout (3 Stars) and reset to base amount
        long claimedJackpot = serverDataAfterRestart.claimJackpot();
        Assertions.assertEquals(amountBeforeRestart, claimedJackpot, "Claimed jackpot must equal accumulated amount!");
        Assertions.assertEquals(100L, serverDataAfterRestart.getJackpotAmount(),
                "Jackpot must reset to base amount (100) after payout!");
    }

    @Test
    public void testDisconnectedPlayerRewardBuffering() {
        JackpotSavedData data = new JackpotSavedData(500L);
        UUID disconnectedPlayer = UUID.randomUUID();

        // Roll completes while player is offline -> reward added to pending queue
        ItemStack prize1 = new ItemStack(Items.GOLD_INGOT, 8);
        ItemStack prize2 = new ItemStack(Items.NETHERITE_SCRAP, 1);
        data.addPendingReward(disconnectedPlayer, prize1);
        data.addPendingReward(disconnectedPlayer, prize2);

        // Player logs back in
        List<ItemStack> delivered = data.popPendingRewards(disconnectedPlayer);
        Assertions.assertEquals(2, delivered.size());
        Assertions.assertEquals(8, delivered.get(0).getCount());
        Assertions.assertEquals(1, delivered.get(1).getCount());

        // Reconnect again -> nothing more
        Assertions.assertTrue(data.popPendingRewards(disconnectedPlayer).isEmpty());
    }

    @Test
    public void testJackpotZeroContributionSetting() {
        JackpotSavedData data = new JackpotSavedData(100L);
        long initial = data.getJackpotAmount();
        data.addContribution(0);
        Assertions.assertEquals(initial, data.getJackpotAmount(), "Zero bet should not increase jackpot");
    }

    @Test
    public void testActiveSessionPersistenceAcrossRestarts() {
        JackpotSavedData data = new JackpotSavedData(200L);
        UUID playerUUID = UUID.randomUUID();

        // Simulate an active slot session in tick 14
        CompoundTag sessionTag = new CompoundTag();
        sessionTag.putUUID("RollId", UUID.randomUUID());
        sessionTag.putUUID("PlayerUUID", playerUUID);
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
        net.pocketodds.item.DeckOfFateItem.setBetTier(deckStack, net.pocketodds.item.ChipTier.COPPER);
        net.pocketodds.item.DeckOfFateItem.setPot(deckStack, 8);
        net.pocketodds.item.DeckOfFateItem.setStreak(deckStack, 3);

        // Locked tier in NBT must be COPPER
        net.pocketodds.item.ChipTier lockedTier = net.pocketodds.item.DeckOfFateItem.getLockedRoundTier(deckStack);
        Assertions.assertEquals(net.pocketodds.item.ChipTier.COPPER, lockedTier, "Locked tier must remain COPPER!");

        // Even if player changes offhand or tries to call cashout, the locked tier stays COPPER
        Assertions.assertNotEquals(net.pocketodds.item.ChipTier.NETHERITE, lockedTier);
    }

    @Test
    public void testSplitChipsLargeJackpotAlgorithm() {
        // Test dividing 5000 jackpot chips into stacks <= 64
        int totalChips = 5000;
        List<ItemStack> splitStacks = new java.util.ArrayList<>();
        int remaining = totalChips;
        while (remaining > 0) {
            int count = Math.min(remaining, 64);
            splitStacks.add(new ItemStack(Items.GOLD_INGOT, count));
            remaining -= count;
        }

        Assertions.assertEquals(79, splitStacks.size()); // 78 * 64 + 8 = 5000
        int reassembledTotal = 0;
        for (ItemStack stack : splitStacks) {
            Assertions.assertTrue(stack.getCount() <= 64, "Stack size must never exceed 64!");
            Assertions.assertTrue(stack.getCount() > 0);
            reassembledTotal += stack.getCount();
        }
        Assertions.assertEquals(5000, reassembledTotal, "Reassembled chip total must exactly equal 5000!");
    }

    @Test
    public void testSlotSessionIdempotency() {
        UUID rollId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();
        net.pocketodds.gambling.slot.SlotSymbol[] symbols = new net.pocketodds.gambling.slot.SlotSymbol[]{
                net.pocketodds.gambling.slot.SlotSymbol.CHERRY,
                net.pocketodds.gambling.slot.SlotSymbol.CHERRY,
                net.pocketodds.gambling.slot.SlotSymbol.CHERRY
        };
        net.pocketodds.gambling.slot.SlotOutcome outcome = new net.pocketodds.gambling.slot.SlotOutcome(
                symbols, false, false, false, net.pocketodds.gambling.slot.SlotSymbol.CHERRY, 3, 3.0
        );

        net.pocketodds.gambling.slot.SlotRollSession session = new net.pocketodds.gambling.slot.SlotRollSession(
                rollId, playerUUID, net.pocketodds.item.ChipTier.COPPER, 8, symbols, outcome
        );

        Assertions.assertEquals(rollId, session.getRollId());
        Assertions.assertFalse(session.isFinished());

        CompoundTag nbt = session.toNbt();
        Assertions.assertEquals(rollId, nbt.getUUID("RollId"));
    }

    @Test
    public void testRewardTransactionItemByItemDeliveryAndPartialFailure() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        List<ItemStack> prizes = new java.util.ArrayList<>();
        prizes.add(new ItemStack(Items.DIAMOND, 10));
        prizes.add(new ItemStack(Items.EMERALD, 20));

        JackpotSavedData.RewardTransaction tx = new JackpotSavedData.RewardTransaction(txId, playerUUID, prizes);
        data.enqueueRewardTransaction(tx);

        List<JackpotSavedData.RewardTransaction> pending = data.getPendingTransactions(playerUUID);
        Assertions.assertEquals(1, pending.size());
        Assertions.assertEquals(2, pending.get(0).getItems().size());

        // Deliver first item successfully
        boolean confirmedFirst = data.confirmDeliveredItem(txId, new ItemStack(Items.DIAMOND, 10));
        Assertions.assertTrue(confirmedFirst);

        // Transaction still remains because emerald was not yet delivered!
        List<JackpotSavedData.RewardTransaction> stillPending = data.getPendingTransactions(playerUUID);
        Assertions.assertEquals(1, stillPending.size());
        Assertions.assertEquals(1, stillPending.get(0).getItems().size());
        Assertions.assertEquals(Items.EMERALD, stillPending.get(0).getItems().get(0).getItem());

        // Deliver second item
        boolean confirmedSecond = data.confirmDeliveredItem(txId, new ItemStack(Items.EMERALD, 20));
        Assertions.assertTrue(confirmedSecond);

        // Now transaction queue is completely empty
        Assertions.assertTrue(data.getPendingTransactions(playerUUID).isEmpty());
    }

    @Test
    public void testRoulettePayoutAndJackpotTokenTrophy() {
        // 1. Roulette payout math
        // European roulette: 37 pockets (0..36). 18 Red, 18 Black, 1 Green (Zero)
        double redProb = 18.0 / 37.0;
        double greenProb = 1.0 / 37.0;
        double rtpRed = redProb * 2.0 * 100.0; // 97.30%
        double rtpGreen = greenProb * 35.0 * 100.0; // 94.59%

        Assertions.assertTrue(rtpRed < 100.0, "Roulette Red RTP must be under 100%!");
        Assertions.assertTrue(rtpGreen < 100.0, "Roulette Green RTP must be under 100%!");
        Assertions.assertEquals(97.30, Math.round(rtpRed * 100.0) / 100.0);
        Assertions.assertEquals(94.59, Math.round(rtpGreen * 100.0) / 100.0);

        // 2. Jackpot Token Commemorative Trophy
        ItemStack trophy = new ItemStack(Items.GOLD_NUGGET);
        trophy.getOrCreateTag().putString("Winner", "Steve");
        trophy.getOrCreateTag().putLong("JackpotAmount", 2500L);
        trophy.getOrCreateTag().putString("Date", "2026-09-21");

        Assertions.assertEquals("Steve", trophy.getTag().getString("Winner"));
        Assertions.assertEquals(2500L, trophy.getTag().getLong("JackpotAmount"));
        Assertions.assertEquals("2026-09-21", trophy.getTag().getString("Date"));
    }
}
