package net.pocketodds;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.gambling.core.*;
import net.pocketodds.gambling.itembet.*;
import net.pocketodds.gambling.slot.SlotEvaluator;
import net.pocketodds.gambling.slot.SlotOutcome;
import net.pocketodds.gambling.slot.SlotRollSession;
import net.pocketodds.gambling.slot.SlotSymbol;
import net.pocketodds.item.ChipTier;
import net.pocketodds.item.DeckOfFateItem;
import net.pocketodds.item.JackpotTokenItem;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.RewardDeliverySink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

public class ItemBettingTest {

    @BeforeAll
    public static void setup() {
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
            net.minecraft.resources.ResourceLocation jokerLoc = new net.minecraft.resources.ResourceLocation("pocketodds", "joker");
            if (!forgeReg.containsKey(jokerLoc)) {
                forgeReg.register(jokerLoc, new net.minecraft.world.item.Item(new net.minecraft.world.item.Item.Properties()));
            }
            forgeReg.freeze();
        }
    }

    @Test
    public void test2PcLifecycle() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        UUID rollId = UUID.randomUUID();
        BetSnapshot bet = BetSnapshot.fromItem(new ItemStack(Items.IRON_INGOT), 10, 1L);

        // Step 1: Prepare via production RewardTransactionService
        BetPreparation prep = RewardTransactionService.prepareBet(playerUUID, GameType.SLOT, rollId, bet, data);
        Assertions.assertEquals(BetPreparation.PreparationStatus.PREPARED, data.getPreparedBet(prep.getPreparationId()).getStatus());
        Assertions.assertEquals(rollId, prep.getAssociatedId());

        // Step 2: Debit transition
        prep.setStatus(BetPreparation.PreparationStatus.DEBITED);
        data.savePreparedBet(prep);
        Assertions.assertEquals(BetPreparation.PreparationStatus.DEBITED, data.getPreparedBet(prep.getPreparationId()).getStatus());

        // Step 3: Commit via production RewardTransactionService
        RewardTransactionService.commitBet(prep, data);
        Assertions.assertNull(data.getPreparedBet(prep.getPreparationId()), "Committed bet must be cleared from preparation map!");
    }

    @Test
    public void testPreparedBetOnCrashDiscardedWithoutRefund() {
        JackpotSavedData beforeCrash = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        BetSnapshot bet = BetSnapshot.fromItem(new ItemStack(Items.IRON_INGOT), 10, 1L);
        BetPreparation prep = new BetPreparation(playerUUID, GameType.SLOT, bet);
        prep.setStatus(BetPreparation.PreparationStatus.PREPARED);
        beforeCrash.savePreparedBet(prep);

        // Simulate crash & world save/load
        CompoundTag tag = new CompoundTag();
        beforeCrash.save(tag);
        JackpotSavedData afterCrash = JackpotSavedData.load(tag);

        // Invariant: PREPARED bet items were not taken, so NO refund transaction should be queued!
        Assertions.assertTrue(afterCrash.getPendingTransactions(playerUUID).isEmpty(),
                "PREPARED bets must not generate outbox refunds upon crash recovery!");
    }

    @Test
    public void testDebitedBetOnCrashQueuesRefund() {
        JackpotSavedData beforeCrash = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        BetSnapshot bet = BetSnapshot.fromItem(new ItemStack(Items.DIAMOND), 5, 64L);
        BetPreparation prep = new BetPreparation(playerUUID, GameType.SLOT, bet);
        prep.setStatus(BetPreparation.PreparationStatus.DEBITED);
        beforeCrash.savePreparedBet(prep);

        // Simulate crash & world save/load
        CompoundTag tag = new CompoundTag();
        beforeCrash.save(tag);
        JackpotSavedData afterCrash = JackpotSavedData.load(tag);

        // Invariant: DEBITED bet items were taken, so a refund transaction MUST be queued in outbox!
        List<JackpotSavedData.RewardTransaction> txs = afterCrash.getPendingTransactions(playerUUID);
        Assertions.assertEquals(1, txs.size(), "DEBITED bets must generate an outbox refund upon crash recovery!");
        Assertions.assertEquals(Items.DIAMOND, txs.get(0).getItems().get(0).getItem());
        Assertions.assertEquals(5, txs.get(0).getItems().get(0).getCount());
    }

    @Test
    public void testMultipleIdenticalStacksConfirmedIndependentlyViaLineId() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        RewardLine line1 = new RewardLine(new ItemStack(Items.IRON_INGOT, 64));
        RewardLine line2 = new RewardLine(new ItemStack(Items.IRON_INGOT, 64));
        Assertions.assertNotEquals(line1.getLineId(), line2.getLineId());

        JackpotSavedData.RewardTransaction tx = JackpotSavedData.RewardTransaction.fromLines(
                txId, playerUUID, List.of(line1, line2), false, 0L, false
        );
        data.enqueueRewardTransaction(tx);

        // Confirm delivery of line1 only
        boolean confirmed1 = data.confirmDeliveredLine(txId, line1.getLineId());
        Assertions.assertTrue(confirmed1);

        // Transaction must NOT be empty because line2 is still pending!
        Assertions.assertTrue(data.hasPendingTransaction(txId));
        JackpotSavedData.RewardTransaction remainingTx = data.getTransaction(txId);
        Assertions.assertEquals(1, remainingTx.getLines().size());
        Assertions.assertEquals(line2.getLineId(), remainingTx.getLines().get(0).getLineId());

        // Confirm delivery of line2
        boolean confirmed2 = data.confirmDeliveredLine(txId, line2.getLineId());
        Assertions.assertTrue(confirmed2);

        // Now transaction must be completed and removed
        Assertions.assertFalse(data.hasPendingTransaction(txId));
    }

    @Test
    public void testDeckSessionServerPersistenceAndRestoration() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        BetSnapshot bet = BetSnapshot.fromItem(new ItemStack(Items.GOLD_INGOT), 2, 8L);
        DeckSession session = new DeckSession(playerUUID, bet);
        session.setStreak(4);
        session.setPotUnits(6);

        data.saveDeckSession(session);

        // Save and reload
        CompoundTag tag = new CompoundTag();
        data.save(tag);
        JackpotSavedData loaded = JackpotSavedData.load(tag);

        DeckSession restored = loaded.getDeckSession(session.getSessionId());
        Assertions.assertNotNull(restored);
        Assertions.assertEquals(playerUUID, restored.getPlayerUUID());
        Assertions.assertEquals(4, restored.getStreak());
        Assertions.assertEquals(6, restored.getPotUnits());
        Assertions.assertEquals(Items.GOLD_INGOT, restored.getInitialBet().getItemPrototype().getItem());
        Assertions.assertEquals(2, restored.getInitialBet().getBetCount());
    }

    @Test
    public void testDeckSessionCrossPlayerCashoutForbidden() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        BetSnapshot bet = BetSnapshot.fromChip(ChipTier.GOLD, 1);
        DeckSession session = new DeckSession(playerA, bet);

        Assertions.assertTrue(DeckOfFateItem.isSessionOwner(session, playerA), "Player A must be the session owner!");
        Assertions.assertFalse(DeckOfFateItem.isSessionOwner(session, playerB), "Player B cannot own Player A's session!");
        Assertions.assertFalse(DeckOfFateItem.isSessionOwner(null, playerA), "Null session must return false!");
        Assertions.assertFalse(DeckOfFateItem.isSessionOwner(session, null), "Null player UUID must return false!");
    }

    @Test
    public void testDeckSessionCashoutIdempotentWithDeterministicTxId() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        BetSnapshot bet = BetSnapshot.fromItem(new ItemStack(Items.EMERALD), 2, 32L);

        DeckSession session = new DeckSession(playerUUID, bet);
        session.setPotUnits(4); // 4 * 2 = 8 emeralds
        data.saveDeckSession(session);

        UUID sessionId = session.getSessionId();
        UUID cashoutTxId = UUID.nameUUIDFromBytes(("deck_cashout:" + sessionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Perform cashout
        session.setStatus(DeckSession.Status.CASHOUT_COMMITTED);
        session.setActive(false);
        data.saveDeckSession(session);

        List<ItemStack> cashoutItems = List.of(new ItemStack(Items.EMERALD, 8));
        RewardBundle bundle = new RewardBundle(cashoutItems, 0L, false);
        RewardTransactionService.enqueueRewardBundle(data, cashoutTxId, playerUUID, bundle);

        // Invariants:
        // 1. Session is not active
        Assertions.assertFalse(session.isActive(), "Committed session must not be active!");
        Assertions.assertEquals(DeckSession.Status.CASHOUT_COMMITTED, session.getStatus());

        // 2. Outbox has transaction
        Assertions.assertTrue(data.hasPendingTransaction(cashoutTxId));

        // 3. Re-enqueuing with deterministic cashoutTxId is idempotent (no duplicate)
        RewardTransactionService.enqueueRewardBundle(data, cashoutTxId, playerUUID, bundle);
        Assertions.assertEquals(1, data.getPendingTransactions(playerUUID).size());

        // 4. Delivery completes idempotently
        List<ItemStack> delivered = new ArrayList<>();
        boolean deliveredSuccess = RewardTransactionService.deliverPendingTransactions(playerUUID, null, data, (p, s) -> delivered.add(s.copy()));
        Assertions.assertTrue(deliveredSuccess);
        Assertions.assertEquals(8, delivered.get(0).getCount());
        Assertions.assertFalse(data.hasPendingTransaction(cashoutTxId));
    }

    @Test
    public void testDebitedBetWithCommittedOutcomeIgnoredOnCrashRecovery() {
        JackpotSavedData beforeCrash = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();
        UUID rollId = UUID.randomUUID();
        BetSnapshot bet = BetSnapshot.fromItem(new ItemStack(Items.DIAMOND), 5, 64L);

        // Bet was debited and associated with rollId
        BetPreparation prep = new BetPreparation(playerUUID, GameType.SLOT, bet, rollId);
        prep.setStatus(BetPreparation.PreparationStatus.DEBITED);
        beforeCrash.savePreparedBet(prep);

        // Outcome already committed into outbox with txId = rollId
        JackpotSavedData.RewardTransaction winTx = JackpotSavedData.RewardTransaction.fromLines(
                rollId, playerUUID, List.of(new RewardLine(new ItemStack(Items.DIAMOND, 15))), false, 0L, false
        );
        beforeCrash.enqueueRewardTransaction(winTx);

        // Crash and recover
        CompoundTag tag = new CompoundTag();
        beforeCrash.save(tag);
        JackpotSavedData afterCrash = JackpotSavedData.load(tag);

        // Invariant: The win transaction is present, but NO refund transaction is created for the debited bet!
        List<JackpotSavedData.RewardTransaction> txs = afterCrash.getPendingTransactions(playerUUID);
        Assertions.assertEquals(1, txs.size(), "Only the win transaction must exist, no duplicate refund!");
        Assertions.assertEquals(rollId, txs.get(0).getTransactionId());
        Assertions.assertEquals(15, txs.get(0).getItems().get(0).getCount());
    }

    @Test
    public void testInstantGamesPreCommitOutcomeIdempotent() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID playerUUID = UUID.randomUUID();

        // Roll 7 (Lucky Seven) -> 3x payout
        BetSnapshot bet = BetSnapshot.fromItem(new ItemStack(Items.IRON_INGOT), 10, 1L);
        List<ItemStack> rewards = List.of(new ItemStack(Items.IRON_INGOT, 30));
        RewardBundle bundle = new RewardBundle(rewards, 0L, false);

        UUID txId = RewardTransactionService.enqueueRewardBundle(data, playerUUID, bundle);
        Assertions.assertNotNull(txId);
        Assertions.assertTrue(data.hasPendingTransaction(txId));

        // Delivering line-by-line via sink
        List<ItemStack> delivered = new ArrayList<>();
        RewardDeliverySink sink = (p, stack) -> delivered.add(stack.copy());

        boolean allDelivered = RewardTransactionService.deliverPendingTransactions(playerUUID, null, data, sink);
        Assertions.assertTrue(allDelivered);
        Assertions.assertEquals(1, delivered.size());
        Assertions.assertEquals(30, delivered.get(0).getCount());

        // Calling deliver again should be idempotent and deliver 0 items
        List<ItemStack> redelivered = new ArrayList<>();
        RewardTransactionService.deliverPendingTransactions(playerUUID, null, data, (p, stack) -> redelivered.add(stack.copy()));
        Assertions.assertTrue(redelivered.isEmpty(), "Redelivery must not duplicate items!");
    }

    @Test
    public void testMathMultiplyExactOverflowRejection() {
        // Attempting a bet count * unitCreditValue that overflows Long.MAX_VALUE
        Assertions.assertThrows(ArithmeticException.class, () -> {
            BetSnapshot.fromItem(new ItemStack(Items.DIAMOND), Integer.MAX_VALUE, Long.MAX_VALUE / 2);
        });
    }

    @Test
    public void testBasisPointsJackpotContribution() {
        JackpotSavedData data = new JackpotSavedData(100L);
        // 500 basis points = 5.00%
        // Bet: 200 credits -> 5% = 10 credits exactly
        data.addContributionBasisPoints(200L, 500);
        Assertions.assertEquals(110L, data.getJackpotAmount());

        // Bet: 1 credit -> Math.max(1, 0) = 1 credit minimum
        data.addContributionBasisPoints(1L, 500);
        Assertions.assertEquals(111L, data.getJackpotAmount());
    }

    @Test
    public void testRewardTableCreditRangeFiltering() {
        ItemRewardEntry cheap = new ItemRewardEntry(new net.minecraft.resources.ResourceLocation("minecraft:iron_ingot"), 10, 1, 5, 10, 1L, 1L, 10L);
        ItemRewardEntry expensive = new ItemRewardEntry(new net.minecraft.resources.ResourceLocation("minecraft:diamond"), 10, 1, 2, 5, 64L, 50L, 500L);

        ItemRewardTable table = new ItemRewardTable(List.of(cheap, expensive));

        // When bet credit is 5, only cheap entry matches
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(42L);
        List<ItemStack> rollsSmall = table.rollRewards(random, 5L, 64, 1.0);
        Assertions.assertFalse(rollsSmall.isEmpty());
        Assertions.assertEquals(Items.IRON_INGOT, rollsSmall.get(0).getItem());

        // When bet credit is 100, only expensive entry matches
        List<ItemStack> rollsLarge = table.rollRewards(random, 100L, 64, 1.0);
        Assertions.assertFalse(rollsLarge.isEmpty());
        Assertions.assertEquals(Items.DIAMOND, rollsLarge.get(0).getItem());
    }

    @Test
    public void testContainerItemsStrictlyRejected() {
        // Shulker box block item
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        Assertions.assertTrue(ItemBetValidator.isContainerItem(shulker));

        ItemBetConfigEntry entry = new ItemBetConfigEntry(
                new net.minecraft.resources.ResourceLocation("minecraft:shulker_box"), 10L, 1, 10, Set.of(GameType.SLOT), false, false
        );
        ItemBetValidator.ValidationResult res = ItemBetValidator.validate(shulker, 1, entry, GameType.SLOT);
        Assertions.assertEquals(ItemBetValidator.ValidationResult.CONTAINER_FORBIDDEN, res);

        // Bundle
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        Assertions.assertTrue(ItemBetValidator.isContainerItem(bundle));

        // Regular item with BlockEntityTag containing Items
        ItemStack chestItem = new ItemStack(Items.CHEST);
        CompoundTag tag = chestItem.getOrCreateTag();
        CompoundTag beTag = new CompoundTag();
        beTag.put("Items", new ListTag());
        tag.put("BlockEntityTag", beTag);
        Assertions.assertTrue(ItemBetValidator.isContainerItem(chestItem));

        // Regular iron ingot must NOT be a container
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        Assertions.assertFalse(ItemBetValidator.isContainerItem(iron));
    }

    @Test
    public void testLegacySaveV1BackwardCompatibility() {
        CompoundTag v1SessionTag = new CompoundTag();
        UUID rollId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();
        v1SessionTag.putUUID("RollId", rollId);
        v1SessionTag.putUUID("PlayerUUID", playerUUID);
        v1SessionTag.putString("BetTier", "gold");
        v1SessionTag.putInt("BetCount", 8);
        v1SessionTag.putString("Sym0", "GOLD");
        v1SessionTag.putString("Sym1", "GOLD");
        v1SessionTag.putString("Sym2", "GOLD");
        v1SessionTag.putInt("Tick", 10);
        v1SessionTag.putBoolean("Finalized", false);
        v1SessionTag.putBoolean("Insured", false);

        SlotRollSession restored = SlotRollSession.fromNbt(v1SessionTag, null);
        Assertions.assertNotNull(restored);
        Assertions.assertTrue(restored.getBetSnapshot().isChipBet());
        Assertions.assertEquals(ChipTier.GOLD, restored.getBetTier());
        Assertions.assertEquals(8, restored.getBetCount());
        Assertions.assertEquals(10, restored.getTick());
        Assertions.assertEquals(SlotSymbol.GOLD, restored.getSymbols()[0]);
    }

    @Test
    public void testSameItemPayoutMultipliers() {
        BetSnapshot bet1 = BetSnapshot.fromItem(new ItemStack(Items.IRON_INGOT), 2, 1L);
        // Multiplier 0.5x on 2 items -> 1 item
        int payout05 = (int) Math.round(bet1.getBetCount() * 0.5);
        Assertions.assertEquals(1, payout05);

        // Multiplier 1.0x on 2 items -> 2 items
        int payout10 = (int) Math.round(bet1.getBetCount() * 1.0);
        Assertions.assertEquals(2, payout10);

        // Multiplier 1.5x on 2 items -> 3 items
        int payout15 = (int) Math.round(bet1.getBetCount() * 1.5);
        Assertions.assertEquals(3, payout15);

        // Multiplier 2.0x on 2 items -> 4 items
        int payout20 = (int) Math.round(bet1.getBetCount() * 2.0);
        Assertions.assertEquals(4, payout20);
    }

    @Test
    public void testStackSplittingByMaxStackSize() {
        RewardBundle bundle = new RewardBundle(List.of(new ItemStack(Items.IRON_INGOT, 150)), 0L, false);
        List<ItemStack> items = bundle.getItems();
        // 150 items split into 64 + 64 + 22
        Assertions.assertEquals(3, items.size());
        Assertions.assertEquals(64, items.get(0).getCount());
        Assertions.assertEquals(64, items.get(1).getCount());
        Assertions.assertEquals(22, items.get(2).getCount());
    }

    @Test
    public void testInsuranceRefundItemBet() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID rollId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();

        SlotSymbol[] losingSymbols = new SlotSymbol[]{SlotSymbol.CHERRY, SlotSymbol.IRON, SlotSymbol.GOLD};
        SlotOutcome outcome = new SlotOutcome(losingSymbols, false, false, false, null, 0, 0.0);
        BetSnapshot bet = BetSnapshot.fromItem(new ItemStack(Items.DIAMOND), 4, 64L);

        // Session with insured = true
        SlotRollSession session = new SlotRollSession(rollId, playerUUID, "TestPlayer", bet, losingSymbols, outcome, true);
        session.prepareAndCommitOutcome(data, "TestPlayer");

        // Outbox must contain insurance refund of 50% = 2 diamonds
        List<JackpotSavedData.RewardTransaction> txs = data.getPendingTransactions(playerUUID);
        Assertions.assertEquals(1, txs.size());
        Assertions.assertEquals(Items.DIAMOND, txs.get(0).getItems().get(0).getItem());
        Assertions.assertEquals(2, txs.get(0).getItems().get(0).getCount());
    }

    @Test
    public void testThreeSkullsDisasterIgnoresInsurance() {
        JackpotSavedData data = new JackpotSavedData(100L);
        UUID rollId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();

        SlotSymbol[] skulls = new SlotSymbol[]{SlotSymbol.SKULL, SlotSymbol.SKULL, SlotSymbol.SKULL};
        SlotOutcome disasterOutcome = new SlotOutcome(skulls, false, true, false, SlotSymbol.SKULL, 3, 0.0);
        BetSnapshot bet = BetSnapshot.fromItem(new ItemStack(Items.DIAMOND), 4, 64L);

        // Session with insured = true but outcome is disaster!
        SlotRollSession session = new SlotRollSession(rollId, playerUUID, "TestPlayer", bet, skulls, disasterOutcome, true);
        session.prepareAndCommitOutcome(data, "TestPlayer");

        // Rule: Insurance does NOT protect against disaster -> zero outbox rewards
        List<JackpotSavedData.RewardTransaction> txs = data.getPendingTransactions(playerUUID);
        Assertions.assertTrue(txs.isEmpty(), "Three skulls disaster must NEVER trigger insurance refund!");
    }

    @Test
    public void testJokerNotConsumedOnWinningSpin() {
        SlotSymbol[] winSymbols = new SlotSymbol[]{SlotSymbol.DIAMOND, SlotSymbol.DIAMOND, SlotSymbol.DIAMOND};
        SlotEvaluator.JokerRescueResult rescue = SlotEvaluator.tryRescueWithJoker(winSymbols, 1, null);
        Assertions.assertFalse(rescue.isRescued(), "Joker must NOT be consumed if spin is already winning!");
    }

    @Test
    public void testItemRewardTableBudgetExactScalingWithMultiplier() {
        ItemRewardEntry diamondEntry = new ItemRewardEntry(
                new net.minecraft.resources.ResourceLocation("minecraft:diamond"),
                10, 1, 64, 64, 64L, 1L, 10000L
        );
        ItemRewardTable table = new ItemRewardTable(List.of(diamondEntry));
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(12345L);

        // Bet: 64 iron ingots (64 credits). Multiplier 1x -> budget = 64 credits -> exactly 1 diamond (64 credits each)
        List<ItemStack> rewards1x = table.rollRewards(random, 64L, 64L, 64);
        Assertions.assertEquals(1, rewards1x.size());
        Assertions.assertEquals(Items.DIAMOND, rewards1x.get(0).getItem());
        Assertions.assertEquals(1, rewards1x.get(0).getCount());

        // Bet: 64 iron ingots (64 credits). Multiplier 3x -> budget = 192 credits -> exactly 3 diamonds (3 * 64 = 192)
        List<ItemStack> rewards3x = table.rollRewards(random, 192L, 64L, 64);
        Assertions.assertEquals(1, rewards3x.size());
        Assertions.assertEquals(Items.DIAMOND, rewards3x.get(0).getItem());
        Assertions.assertEquals(3, rewards3x.get(0).getCount());

        // Bet: 64 iron ingots (64 credits). Multiplier 35x -> budget = 2240 credits -> exactly 35 diamonds (35 * 64 = 2240)
        List<ItemStack> rewards35x = table.rollRewards(random, 2240L, 64L, 64);
        Assertions.assertEquals(1, rewards35x.size());
        Assertions.assertEquals(Items.DIAMOND, rewards35x.get(0).getItem());
        Assertions.assertEquals(35, rewards35x.get(0).getCount());
    }
}
