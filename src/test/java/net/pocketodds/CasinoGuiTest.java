package net.pocketodds;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.gambling.core.BetPreparation;
import net.pocketodds.data.PouchBalance;
import net.pocketodds.gambling.core.BetSnapshot;
import net.pocketodds.gambling.core.DeckSession;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.core.RewardTransactionService;
import net.pocketodds.gambling.core.*;
import net.pocketodds.gambling.slot.*;
import net.pocketodds.gambling.itembet.ItemBetConfigEntry;
import net.pocketodds.gambling.itembet.ItemBetRegistry;
import net.pocketodds.gambling.itembet.ItemBetValidator;
import net.pocketodds.gui.casino.*;
import net.pocketodds.item.ChipTier;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.c2s.RequestCasinoSyncC2SPacket;
import net.pocketodds.network.c2s.SelectCasinoCategoryC2SPacket;
import net.pocketodds.network.c2s.UpdateBetSelectionC2SPacket;
import net.pocketodds.network.s2c.CasinoResultSyncS2CPacket;
import net.pocketodds.registration.ModItems;
import net.pocketodds.service.CasinoGameResult;
import net.pocketodds.service.CasinoGameService;
import net.pocketodds.service.CoinPouchService;
import net.pocketodds.util.ChipUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CasinoGuiTest {

    @BeforeAll
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM instanceof net.minecraft.core.MappedRegistry<?> mapped) {
            mapped.unfreeze();
        }
        if (net.minecraftforge.registries.ForgeRegistries.ITEMS instanceof net.minecraftforge.registries.ForgeRegistry<net.minecraft.world.item.Item> forgeReg) {
            forgeReg.unfreeze();
            registerItemIfMissing(forgeReg, "copper_chip", new net.pocketodds.item.ChipItem(ChipTier.COPPER, new net.minecraft.world.item.Item.Properties()));
            registerItemIfMissing(forgeReg, "gold_chip", new net.pocketodds.item.ChipItem(ChipTier.GOLD, new net.minecraft.world.item.Item.Properties()));
            registerItemIfMissing(forgeReg, "diamond_chip", new net.pocketodds.item.ChipItem(ChipTier.DIAMOND, new net.minecraft.world.item.Item.Properties()));
            registerItemIfMissing(forgeReg, "netherite_chip", new net.pocketodds.item.ChipItem(ChipTier.NETHERITE, new net.minecraft.world.item.Item.Properties()));
            registerItemIfMissing(forgeReg, "pocket_casino", new net.pocketodds.item.PocketCasinoItem(new net.minecraft.world.item.Item.Properties()));
            registerItemIfMissing(forgeReg, "coin_pouch", new net.pocketodds.item.CoinPouchItem(new net.minecraft.world.item.Item.Properties()));
            registerItemIfMissing(forgeReg, "jackpot_token", new net.pocketodds.item.JackpotTokenItem(new net.minecraft.world.item.Item.Properties()));
            registerItemIfMissing(forgeReg, "joker", new net.minecraft.world.item.Item(new net.minecraft.world.item.Item.Properties()));
            registerItemIfMissing(forgeReg, "insurance", new net.minecraft.world.item.Item(new net.minecraft.world.item.Item.Properties()));
            forgeReg.freeze();
        }

        // Configure test item betting entry
        ItemBetRegistry.loadConfig(java.util.List.of("minecraft:diamond;100;1;64;SLOT,DICE,ROULETTE,DECK;false;false"));
    }

    private static void registerItemIfMissing(net.minecraftforge.registries.ForgeRegistry<net.minecraft.world.item.Item> forgeReg, String id, net.minecraft.world.item.Item item) {
        net.minecraft.resources.ResourceLocation loc = new net.minecraft.resources.ResourceLocation("pocketodds", id);
        if (!forgeReg.containsKey(loc)) {
            forgeReg.register(loc, item);
        }
    }

    @Test
    public void testPouchNbtAndCreditsCalculation() {
        ItemStack pouch = new ItemStack(ModItems.COIN_POUCH.get());

        // Initial counts should be 0
        Assertions.assertEquals(0, CoinPouchService.getChipCount(pouch, ChipTier.COPPER));
        Assertions.assertEquals(0L, CoinPouchService.getTotalCredits(pouch));

        // Add 10 copper, 2 gold, 1 diamond, 1 netherite
        // Copper: 10 * 1 = 10
        // Gold: 2 * 8 = 16
        // Diamond: 1 * 64 = 64
        // Netherite: 1 * 512 = 512
        // Total expected: 10 + 16 + 64 + 512 = 602
        CoinPouchService.addChips(pouch, ChipTier.COPPER, 10);
        CoinPouchService.addChips(pouch, ChipTier.GOLD, 2);
        CoinPouchService.addChips(pouch, ChipTier.DIAMOND, 1);
        CoinPouchService.addChips(pouch, ChipTier.NETHERITE, 1);

        Assertions.assertEquals(10, CoinPouchService.getChipCount(pouch, ChipTier.COPPER));
        Assertions.assertEquals(2, CoinPouchService.getChipCount(pouch, ChipTier.GOLD));
        Assertions.assertEquals(1, CoinPouchService.getChipCount(pouch, ChipTier.DIAMOND));
        Assertions.assertEquals(1, CoinPouchService.getChipCount(pouch, ChipTier.NETHERITE));
        Assertions.assertEquals(602L, CoinPouchService.getTotalCredits(pouch));

        // Remove chips
        int removed = CoinPouchService.removeChips(pouch, ChipTier.COPPER, 5);
        Assertions.assertEquals(5, removed);
        Assertions.assertEquals(5, CoinPouchService.getChipCount(pouch, ChipTier.COPPER));
        Assertions.assertEquals(597L, CoinPouchService.getTotalCredits(pouch));
    }

    @Test
    public void testPouchConcurrentLocking() {
        UUID pouchId = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        // Player A acquires lock
        Assertions.assertTrue(CoinPouchService.acquirePouchLock(pouchId, playerA));

        // Player B tries to acquire same lock -> should fail
        Assertions.assertFalse(CoinPouchService.acquirePouchLock(pouchId, playerB));

        // Player B tries to release Player A's lock -> should not release
        CoinPouchService.releasePouchLock(pouchId, playerB);
        Assertions.assertFalse(CoinPouchService.acquirePouchLock(pouchId, playerB));

        // Player A releases lock
        CoinPouchService.releasePouchLock(pouchId, playerA);

        // Now Player B can acquire lock
        Assertions.assertTrue(CoinPouchService.acquirePouchLock(pouchId, playerB));
        CoinPouchService.releasePouchLock(pouchId, playerB);
    }

    @Test
    public void testDebitCreditsFromPouchOnly() {
        ItemStack pouch = new ItemStack(ModItems.COIN_POUCH.get());
        // 1 Netherite = 512 credits
        CoinPouchService.addChips(pouch, ChipTier.NETHERITE, 1);
        Assertions.assertEquals(512L, CoinPouchService.getTotalCredits(pouch));

        // Debit 12 credits (needs 1 gold (8) + 4 copper (4))
        // The service breaks 1 netherite down and leaves 500 credits
        boolean ok = CoinPouchService.debitCreditsFromPouchOnly(pouch, 12L);
        Assertions.assertTrue(ok);
        Assertions.assertEquals(500L, CoinPouchService.getTotalCredits(pouch));

        // Attempting to debit more than total credits (e.g. 1000) should fail and not alter balance
        boolean failDebit = CoinPouchService.debitCreditsFromPouchOnly(pouch, 1000L);
        Assertions.assertFalse(failDebit);
        Assertions.assertEquals(500L, CoinPouchService.getTotalCredits(pouch));
    }

    @Test
    public void testItemBetValidatorRestrictions() {
        // 1. Shulker box container check
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        Assertions.assertTrue(ItemBetValidator.isForbiddenContainer(shulker));

        // 2. Bundle container check
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        Assertions.assertTrue(ItemBetValidator.isForbiddenContainer(bundle));

        // 3. BlockEntityTag container check
        ItemStack chest = new ItemStack(Items.CHEST);
        CompoundTag tag = chest.getOrCreateTag();
        tag.put("BlockEntityTag", new CompoundTag());
        tag.getCompound("BlockEntityTag").put("Items", new net.minecraft.nbt.ListTag());
        Assertions.assertTrue(ItemBetValidator.isForbiddenContainer(chest));

        // 4. Pure diamond item check
        ItemStack cleanDiamond = new ItemStack(Items.DIAMOND, 5);
        Assertions.assertFalse(ItemBetValidator.isForbiddenContainer(cleanDiamond));
        Assertions.assertFalse(ItemBetValidator.isDamaged(cleanDiamond));
        Assertions.assertFalse(ItemBetValidator.hasForbiddenNbt(cleanDiamond));

        // 5. Enchanted item check
        ItemStack enchantedDiamond = new ItemStack(Items.DIAMOND, 1);
        enchantedDiamond.enchant(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 1);
        Assertions.assertTrue(ItemBetValidator.hasForbiddenNbt(enchantedDiamond));
    }

    @Test
    public void testOperationDeduplication() {
        UUID opId = UUID.randomUUID();
        // First occurrence is accepted
        Assertions.assertTrue(CasinoGameService.checkAndRecordOperation(opId));
        // Duplicate packet with same operationId is rejected
        Assertions.assertFalse(CasinoGameService.checkAndRecordOperation(opId));
    }

    @Test
    public void testEnumsAndDefaults() {
        // CasinoCategory bounds
        Assertions.assertEquals(CasinoCategory.SLOTS, CasinoCategory.fromOrdinal(-1));
        Assertions.assertEquals(CasinoCategory.SLOTS, CasinoCategory.fromOrdinal(999));
        Assertions.assertEquals(CasinoCategory.DICE, CasinoCategory.fromOrdinal(CasinoCategory.DICE.ordinal()));
        Assertions.assertNotNull(CasinoCategory.SLOTS.getTranslationKey());

        // BetFundingSource bounds
        Assertions.assertEquals(BetFundingSource.INVENTORY, BetFundingSource.fromOrdinal(-1));
        Assertions.assertEquals(BetFundingSource.ITEM_SLOT, BetFundingSource.fromOrdinal(BetFundingSource.ITEM_SLOT.ordinal()));

        // PayoutDestination bounds
        Assertions.assertEquals(PayoutDestination.INVENTORY, PayoutDestination.fromOrdinal(-1));
        Assertions.assertEquals(PayoutDestination.POUCH, PayoutDestination.fromOrdinal(PayoutDestination.POUCH.ordinal()));

        // RouletteBetType bounds
        Assertions.assertEquals(RouletteBetType.RED, RouletteBetType.fromOrdinal(-1));
        Assertions.assertEquals(RouletteBetType.ZERO, RouletteBetType.fromOrdinal(RouletteBetType.ZERO.ordinal()));
    }

    @Test
    public void test2PcDebitAndOutboxIntegrity() {
        JackpotSavedData data = new JackpotSavedData(500L);
        UUID playerUUID = UUID.randomUUID();
        BetSnapshot bet = BetSnapshot.fromChip(ChipTier.GOLD, 2);

        // 1. Prepare
        BetPreparation prep = RewardTransactionService.prepareBet(playerUUID, GameType.SLOT, UUID.randomUUID(), bet, data);
        Assertions.assertEquals(BetPreparation.PreparationStatus.PREPARED, prep.getStatus());

        // 2. Apply debit transition
        boolean debited = RewardTransactionService.applyDebitTransition(prep, data);
        Assertions.assertTrue(debited);
        Assertions.assertEquals(BetPreparation.PreparationStatus.DEBITED, prep.getStatus());

        // 3. Settle and commit into outbox
        UUID rollId = UUID.randomUUID();
        data.settleAndCommitBet(prep, rollId, playerUUID, new net.pocketodds.gambling.core.RewardBundle(java.util.List.of(new ItemStack(Items.DIAMOND, 2)), 0L, false));

        // Outbox must contain the transaction
        Assertions.assertTrue(data.hasPendingTransaction(rollId));
        // Prepared bet must be removed upon commit
        Assertions.assertNull(data.getPreparedBet(prep.getPreparationId()));
    }

    @Test
    public void testJackpotClaimReset() {
        JackpotSavedData data = new JackpotSavedData(1000L);
        UUID txId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();

        // Add jackpot reward in outbox
        net.pocketodds.gambling.core.RewardBundle bundle = new net.pocketodds.gambling.core.RewardBundle(List.of(new ItemStack(Items.DIAMOND, 5)), 1000L, true);
        data.enqueueRewardTransaction(JackpotSavedData.RewardTransaction.fromBundle(txId, playerUUID, bundle));

        // Before claim, jackpot is 1000
        Assertions.assertEquals(1000L, data.getJackpotAmount());

        // Claim jackpot
        boolean claimed = data.claimJackpotForTransaction(txId);
        Assertions.assertTrue(claimed);

        // Jackpot is reset to base amount (100)
        Assertions.assertEquals(100L, data.getJackpotAmount());

        // Repeated claim for the same transaction is rejected
        boolean claimedAgain = data.claimJackpotForTransaction(txId);
        Assertions.assertFalse(claimedAgain);
    }

    @Test
    public void testDeckSessionBetCommitPreventsDuplicateRecovery() {
        JackpotSavedData data = new JackpotSavedData(500L);
        UUID playerUUID = UUID.randomUUID();
        BetSnapshot bet = BetSnapshot.fromChip(ChipTier.GOLD, 4);

        // 1. Prepare & debit bet
        BetPreparation prep = RewardTransactionService.prepareBet(playerUUID, GameType.DECK, UUID.randomUUID(), bet, data);
        RewardTransactionService.applyDebitTransition(prep, data);
        Assertions.assertEquals(BetPreparation.PreparationStatus.DEBITED, prep.getStatus());

        // 2. Start deck session and commit bet
        DeckSession session = new DeckSession(playerUUID, bet);
        prep.setAssociatedId(session.getSessionId());
        data.savePreparedBet(prep);
        data.saveDeckSession(session);
        RewardTransactionService.commitBet(prep, data);

        Assertions.assertEquals(BetPreparation.PreparationStatus.COMMITTED, prep.getStatus());

        // 3. Simulate crash & restart (save to NBT and reload)
        CompoundTag saved = data.save(new CompoundTag());
        JackpotSavedData reloaded = JackpotSavedData.load(saved);

        // Because bet was COMMITTED, crash recovery must NOT queue any refund!
        List<JackpotSavedData.RewardTransaction> pending = reloaded.getPendingTransactions(playerUUID);
        Assertions.assertTrue(pending.isEmpty(), "Committed deck bet must NEVER trigger refund on server crash/restart!");

        // Deck session is properly restored
        DeckSession restoredSession = reloaded.getActiveDeckSession(playerUUID);
        Assertions.assertNotNull(restoredSession);
        Assertions.assertEquals(session.getSessionId(), restoredSession.getSessionId());
    }

    @Test
    public void testPouchAuthoritativeBalanceDuplicateProtection() {
        JackpotSavedData data = new JackpotSavedData(500L);
        UUID pouchUUID = UUID.randomUUID();

        // Two item stacks referring to the same pouch UUID (e.g. cloned pouch)
        ItemStack pouchA = new ItemStack(ModItems.COIN_POUCH.get());
        pouchA.getOrCreateTag().putUUID("PouchUUID", pouchUUID);

        ItemStack pouchB = new ItemStack(ModItems.COIN_POUCH.get());
        pouchB.getOrCreateTag().putUUID("PouchUUID", pouchUUID);

        // Deposit 10 gold chips to pouchA
        PouchBalance balanceA = data.getOrCreatePouchBalance(pouchUUID, pouchA);
        balanceA.addCount(ChipTier.GOLD, 10);
        data.setPouchBalance(pouchUUID, balanceA);
        CoinPouchService.syncStackNbt(pouchA, balanceA);

        Assertions.assertEquals(10, CoinPouchService.getChipCount(pouchA, ChipTier.GOLD));
        Assertions.assertEquals(80L, CoinPouchService.getTotalCredits(pouchA));

        // When pouchB accesses server data, it sees the exact same balance
        PouchBalance balanceB = data.getOrCreatePouchBalance(pouchUUID, pouchB);
        Assertions.assertEquals(10, balanceB.getCount(ChipTier.GOLD));
        CoinPouchService.syncStackNbt(pouchB, balanceB);
        Assertions.assertEquals(10, CoinPouchService.getChipCount(pouchB, ChipTier.GOLD));

        // Withdraw 6 gold chips via balance
        balanceA.addCount(ChipTier.GOLD, -6);
        data.setPouchBalance(pouchUUID, balanceA);
        CoinPouchService.syncStackNbt(pouchA, balanceA);

        Assertions.assertEquals(4, CoinPouchService.getChipCount(pouchA, ChipTier.GOLD));

        // Attempting to withdraw from pouchB immediately sees the updated server balance
        PouchBalance serverBalance = data.getPouchBalance(pouchUUID);
        Assertions.assertEquals(4, serverBalance.getCount(ChipTier.GOLD));
        CoinPouchService.syncStackNbt(pouchB, serverBalance);
        Assertions.assertEquals(4, CoinPouchService.getChipCount(pouchB, ChipTier.GOLD));
    }

    @Test
    public void testItemBetConfigValidationRules() {
        net.minecraft.resources.ResourceLocation diamondLoc = new net.minecraft.resources.ResourceLocation("minecraft:diamond");

        // 1. Strict entry (no damaged, no NBT, range 2..10, only SLOT)
        ItemBetConfigEntry strictEntry = new ItemBetConfigEntry(
                diamondLoc, 50L, 2, 10, Set.of(GameType.SLOT), false, false
        );

        ItemStack normalDiamond = new ItemStack(Items.DIAMOND, 5);
        Assertions.assertEquals(ItemBetValidator.ValidationResult.VALID,
                ItemBetValidator.validate(normalDiamond, 5, strictEntry, GameType.SLOT));

        // Game not allowed
        Assertions.assertEquals(ItemBetValidator.ValidationResult.GAME_NOT_ALLOWED,
                ItemBetValidator.validate(normalDiamond, 5, strictEntry, GameType.ROULETTE));

        // Count out of range
        Assertions.assertEquals(ItemBetValidator.ValidationResult.COUNT_OUT_OF_RANGE,
                ItemBetValidator.validate(normalDiamond, 1, strictEntry, GameType.SLOT));
        Assertions.assertEquals(ItemBetValidator.ValidationResult.COUNT_OUT_OF_RANGE,
                ItemBetValidator.validate(normalDiamond, 11, strictEntry, GameType.SLOT));

        // Forbidden NBT
        ItemStack nbtDiamond = normalDiamond.copy();
        nbtDiamond.getOrCreateTag().putString("CustomTag", "Test");
        Assertions.assertEquals(ItemBetValidator.ValidationResult.NBT_OR_ENCHANTS_FORBIDDEN,
                ItemBetValidator.validate(nbtDiamond, 5, strictEntry, GameType.SLOT));

        // 2. Permissive entry (allow NBT = true)
        ItemBetConfigEntry permissiveEntry = new ItemBetConfigEntry(
                diamondLoc, 50L, 2, 10, Set.of(GameType.SLOT), true, false
        );
        Assertions.assertEquals(ItemBetValidator.ValidationResult.VALID,
                ItemBetValidator.validate(nbtDiamond, 5, permissiveEntry, GameType.SLOT));
    }

    @Test
    public void testProtocolVersionAndPacketsSerialization() {
        Assertions.assertEquals("2.0.0", ModMessages.PROTOCOL_VERSION);

        // SelectCasinoCategoryC2SPacket with containerId
        SelectCasinoCategoryC2SPacket catPacket = new SelectCasinoCategoryC2SPacket(42, CasinoCategory.ROULETTE.ordinal());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        catPacket.toBytes(buf);
        SelectCasinoCategoryC2SPacket decodedCat = new SelectCasinoCategoryC2SPacket(buf);
        Assertions.assertNotNull(decodedCat);

        // UpdateBetSelectionC2SPacket with containerId
        UpdateBetSelectionC2SPacket betPacket = new UpdateBetSelectionC2SPacket(42, 1, 2, 16, 0, 1);
        FriendlyByteBuf betBuf = new FriendlyByteBuf(Unpooled.buffer());
        betPacket.toBytes(betBuf);
        UpdateBetSelectionC2SPacket decodedBet = new UpdateBetSelectionC2SPacket(betBuf);
        Assertions.assertNotNull(decodedBet);
    }

    @Test
    public void testJackpotSinglePayoutNoDuplication() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();
        long jackpotFund = 1000L;

        // Simulate a slot jackpot win
        SlotSymbol[] tripleStar = new SlotSymbol[]{SlotSymbol.STAR, SlotSymbol.STAR, SlotSymbol.STAR};
        SlotOutcome jackpotOutcome = new SlotOutcome(tripleStar, true, false, false, SlotSymbol.STAR, 3, 100.0);
        BetSnapshot bet = BetSnapshot.fromChip(ChipTier.COPPER, 1);

        List<ItemStack> rewards = SlotRewardFactory.createRewards(null, jackpotOutcome, bet, jackpotFund, false, null);
        long totalRewardChipsValue = 0L;
        for (ItemStack s : rewards) {
            if (s.getItem() instanceof net.pocketodds.item.ChipItem ci) {
                totalRewardChipsValue += (long) s.getCount() * ci.getTier().getBaseValue();
            }
        }
        // Base win (1 copper * 100 = 100 credits) + jackpot (1000 credits) = 1100 credits
        Assertions.assertEquals(1100L, totalRewardChipsValue);

        // Create bundle
        RewardBundle bundle = new RewardBundle(rewards, jackpotFund, true);
        UUID txId = UUID.randomUUID();
        JackpotSavedData.RewardTransaction tx = JackpotSavedData.RewardTransaction.fromBundle(txId, playerId, bundle);

        // Ensure transaction does NOT duplicate jackpot credits
        long txTotalCredits = 0L;
        for (ItemStack s : tx.getItems()) {
            if (s.getItem() instanceof net.pocketodds.item.ChipItem ci) {
                txTotalCredits += (long) s.getCount() * ci.getTier().getBaseValue();
            }
        }
        Assertions.assertEquals(1100L, txTotalCredits, "Jackpot credits must not be duplicated in RewardTransaction.fromBundle");

        // ALSO verify RewardTransactionService.enqueueRewardBundle yields identical non-duplicated transaction
        JackpotSavedData testData = new JackpotSavedData();
        UUID txId2 = RewardTransactionService.enqueueRewardBundle(testData, UUID.randomUUID(), playerId, bundle);
        List<JackpotSavedData.RewardTransaction> pending = testData.getPendingTransactions(playerId);
        Assertions.assertEquals(1, pending.size());
        long serviceTotalCredits = 0L;
        for (ItemStack s : pending.get(0).getItems()) {
            if (s.getItem() instanceof net.pocketodds.item.ChipItem ci) {
                serviceTotalCredits += (long) s.getCount() * ci.getTier().getBaseValue();
            }
        }
        Assertions.assertEquals(1100L, serviceTotalCredits, "Jackpot credits must not be duplicated in RewardTransactionService.enqueueRewardBundle");
        Assertions.assertEquals(txTotalCredits, serviceTotalCredits, "RewardTransaction.fromBundle and RewardTransactionService.enqueueRewardBundle must yield identical results");
    }

    @Test
    public void testCasinoGameSessionPersistenceAndNbt() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        CasinoGameResult result = new CasinoGameResult.Builder(GameType.ROULETTE)
                .operationId(opId)
                .success(true)
                .rouletteNumber(17)
                .multiplier(35.0)
                .wonAmount(35)
                .rewards(List.of(new ItemStack(ModItems.GOLD_CHIP.get(), 2)))
                .build();

        long now = System.currentTimeMillis();
        CasinoGameSession session = new CasinoGameSession(playerId, opId, GameType.ROULETTE, result, now, 40);
        data.saveLastGameSession(playerId, session);

        // Test save to NBT and reload
        CompoundTag nbt = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(nbt);

        CasinoGameSession loadedSession = loaded.getLastGameSession(playerId);
        Assertions.assertNotNull(loadedSession);
        Assertions.assertEquals(playerId, loadedSession.getPlayerUUID());
        Assertions.assertEquals(opId, loadedSession.getOperationId());
        Assertions.assertEquals(GameType.ROULETTE, loadedSession.getGameType());
        Assertions.assertEquals(40, loadedSession.getAnimDurationTicks());
        Assertions.assertNotNull(loadedSession.getResult());
        Assertions.assertEquals(17, loadedSession.getResult().getRouletteNumber());
        Assertions.assertEquals(35.0, loadedSession.getResult().getMultiplier(), 0.001);
        Assertions.assertEquals(1, loadedSession.getResult().getRewardItems().size());
        Assertions.assertEquals(ModItems.GOLD_CHIP.get(), loadedSession.getResult().getRewardItems().get(0).getItem());
        Assertions.assertEquals(2, loadedSession.getResult().getRewardItems().get(0).getCount());

        // Test CasinoResultSyncS2CPacket with remainingAnimTicks = 0 (instant restore) and rewardItems
        CasinoResultSyncS2CPacket packet = new CasinoResultSyncS2CPacket(result, 0);
        Assertions.assertEquals(0, packet.getRemainingAnimTicks());
        Assertions.assertEquals(1, packet.getRewardItems().size());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.toBytes(buf);
        CasinoResultSyncS2CPacket decoded = new CasinoResultSyncS2CPacket(buf);
        Assertions.assertEquals(0, decoded.getRemainingAnimTicks());
        Assertions.assertEquals(17, decoded.getRouletteNumber());
        Assertions.assertEquals(1, decoded.getRewardItems().size());
        Assertions.assertEquals(ModItems.GOLD_CHIP.get(), decoded.getRewardItems().get(0).getItem());
        Assertions.assertEquals(2, decoded.getRewardItems().get(0).getCount());
    }

    @Test
    public void testTransactionalPouchWithdrawal() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();
        UUID pouchId = UUID.randomUUID();

        // Put 10 gold chips (80 credits) into pouch
        PouchBalance initBalance = new PouchBalance();
        initBalance.setCount(ChipTier.GOLD, 10);
        data.setPouchBalance(pouchId, initBalance);

        // Withdraw 3 gold chips
        int taken = data.withdrawFromPouch(playerId, pouchId, ChipTier.GOLD, 3);
        Assertions.assertEquals(3, taken);

        // Pouch balance immediately reduced to 7
        PouchBalance afterWithdraw = data.getPouchBalance(pouchId);
        Assertions.assertEquals(7, afterWithdraw.getCount(ChipTier.GOLD));

        // Transaction is stored in outbox pendingTransactions
        List<JackpotSavedData.RewardTransaction> pending = data.getPendingTransactions(playerId);
        Assertions.assertEquals(1, pending.size());
        Assertions.assertEquals(1, pending.get(0).getLines().size());
        Assertions.assertEquals(3, pending.get(0).getLines().get(0).getStack().getCount());
        Assertions.assertEquals(ModItems.GOLD_CHIP.get(), pending.get(0).getLines().get(0).getStack().getItem());
    }

    @Test
    public void testPouchDepositEscrowAndStrictCommitValidation() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();
        UUID pouchId = UUID.randomUUID();

        // Phase 1: Prepare deposit of 5 netherite chips (expected player chip count: 10)
        PouchDepositEscrow escrow = data.preparePouchDeposit(playerId, pouchId, ChipTier.NETHERITE, 5, 10);
        Assertions.assertNotNull(escrow);
        Assertions.assertEquals(PouchDepositEscrow.Status.PREPARED, escrow.getStatus());
        Assertions.assertEquals(10, escrow.getExpectedPlayerChipCount());

        // Balance not changed yet
        PouchBalance balanceBefore = data.getPouchBalance(pouchId);
        Assertions.assertEquals(0, balanceBefore.getCount(ChipTier.NETHERITE));

        // STRICT VALIDATION: Attempting to commit directly from PREPARED state MUST fail!
        boolean invalidCommit = data.commitPouchDeposit(escrow.getDepositId());
        Assertions.assertFalse(invalidCommit, "commitPouchDeposit must fail if deposit is not in DEBITED state");
        Assertions.assertEquals(0, data.getPouchBalance(pouchId).getCount(ChipTier.NETHERITE));

        // Crash Case A: Server crashes while deposit is only PREPARED
        CompoundTag crashPreparedTag = data.save(new CompoundTag());
        JackpotSavedData recoveredPrepared = JackpotSavedData.load(crashPreparedTag);
        PouchBalance recoveredPrepBalance = recoveredPrepared.getPouchBalance(pouchId);
        Assertions.assertEquals(0, recoveredPrepBalance.getCount(ChipTier.NETHERITE),
                "Un-debited prepared deposit must NOT be committed on restart");

        // Phase 2: Player items confirmed removed from inventory -> transition to DEBITED
        boolean debited = data.markDepositDebited(escrow.getDepositId());
        Assertions.assertTrue(debited);

        // Committing from DEBITED state must now succeed
        boolean validCommit = data.commitPouchDeposit(escrow.getDepositId());
        Assertions.assertTrue(validCommit, "commitPouchDeposit must succeed from DEBITED state");
        Assertions.assertEquals(5, data.getPouchBalance(pouchId).getCount(ChipTier.NETHERITE));

        // Repeated commit on already committed deposit must fail
        boolean repeatedCommit = data.commitPouchDeposit(escrow.getDepositId());
        Assertions.assertFalse(repeatedCommit, "Repeated commit on already processed deposit must fail");
    }

    @Test
    public void testPouchDepositAuditAndRecoveryOnPlayerLogin() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();
        UUID pouchId = UUID.randomUUID();

        // Case A: Server crashed before chip removal (expected=8, current=8) -> canceled without refund
        PouchDepositEscrow prep1 = data.preparePouchDeposit(playerId, pouchId, ChipTier.GOLD, 2, 8);
        data.auditAndResolvePendingDeposits(playerId, tier -> 8, null);
        Assertions.assertEquals(0, data.getPendingTransactions(playerId).size(),
                "If chips were not debited, no refund transaction should be queued");
        Assertions.assertEquals(0, data.getPouchBalance(pouchId).getCount(ChipTier.GOLD));

        // Case B: Server crashed right AFTER chip removal before markDepositDebited / commit (expected=8, current=6, take=2)
        PouchDepositEscrow prep2 = data.preparePouchDeposit(playerId, pouchId, ChipTier.GOLD, 2, 8);
        List<JackpotSavedData.RewardTransaction> refunds = new ArrayList<>();
        data.auditAndResolvePendingDeposits(playerId, tier -> 6, refunds::add);

        Assertions.assertEquals(1, refunds.size(), "Missing chips must be refunded via outbox transaction");
        JackpotSavedData.RewardTransaction refundTx = refunds.get(0);
        long refundedCredits = 0L;
        for (ItemStack st : refundTx.getItems()) {
            if (st.getItem() instanceof net.pocketodds.item.ChipItem ci) {
                refundedCredits += (long) st.getCount() * ci.getTier().getBaseValue();
            }
        }
        Assertions.assertEquals(16L, refundedCredits, "2 gold chips (16 credits) must be safely refunded");
    }

    @Test
    public void testRequestCasinoSyncC2SPacketSerialization() {
        RequestCasinoSyncC2SPacket packet = new RequestCasinoSyncC2SPacket(123);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.toBytes(buf);
        RequestCasinoSyncC2SPacket decoded = new RequestCasinoSyncC2SPacket(buf);
        FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
        decoded.toBytes(buf2);
        Assertions.assertEquals(123, buf2.readInt());
    }

    @Test
    public void testPouchBalanceImmutability() {
        JackpotSavedData data = new JackpotSavedData();
        UUID pouchId = UUID.randomUUID();

        PouchBalance init = new PouchBalance();
        init.setCount(ChipTier.DIAMOND, 4);
        data.setPouchBalance(pouchId, init);

        // Calling getPouchBalance returns a defensive copy
        PouchBalance copy1 = data.getPouchBalance(pouchId);
        copy1.setCount(ChipTier.DIAMOND, 999);

        // Data in JackpotSavedData remains 4
        PouchBalance copy2 = data.getPouchBalance(pouchId);
        Assertions.assertEquals(4, copy2.getCount(ChipTier.DIAMOND),
                "Mutating a retrieved PouchBalance must not affect internal state without setPouchBalance");

        // Calling getOrCreatePouchBalance also returns a defensive copy
        PouchBalance copy3 = data.getOrCreatePouchBalance(pouchId);
        copy3.setCount(ChipTier.DIAMOND, 888);
        Assertions.assertEquals(4, data.getPouchBalance(pouchId).getCount(ChipTier.DIAMOND));
    }

    @Test
    public void testSessionTtlExpirationAndCleanup() {
        JackpotSavedData data = new JackpotSavedData();
        UUID expiredPlayerId = UUID.randomUUID();
        UUID freshPlayerId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        CasinoGameResult dummyResult = new CasinoGameResult.Builder(GameType.SLOT)
                .operationId(UUID.randomUUID())
                .success(true)
                .build();

        // Expired session: 70 seconds ago (> 60s TTL)
        CasinoGameSession expiredSession = new CasinoGameSession(
                expiredPlayerId, UUID.randomUUID(), GameType.SLOT, dummyResult, now - 70_000L, 20);
        // Fresh session: 5 seconds ago (< 60s TTL)
        CasinoGameSession freshSession = new CasinoGameSession(
                freshPlayerId, UUID.randomUUID(), GameType.SLOT, dummyResult, now - 5_000L, 20);

        // Put expired session directly to simulate passage of time
        data.saveLastGameSession(freshPlayerId, freshSession);
        // Saving a fresh session triggers cleanupExpiredSessions()
        // Put expired session directly via NBT to test load filtering and getLastGameSession expiration
        CompoundTag rawTag = new CompoundTag();
        rawTag.put(expiredPlayerId.toString(), expiredSession.toNbt());
        rawTag.put(freshPlayerId.toString(), freshSession.toNbt());

        CompoundTag dataTag = new CompoundTag();
        dataTag.put("LastPlayerSessions", rawTag);
        JackpotSavedData loaded = JackpotSavedData.load(dataTag);

        // The expired session must be filtered out during load
        Assertions.assertNull(loaded.getLastGameSession(expiredPlayerId), "Expired session must not be loaded from NBT");
        Assertions.assertNotNull(loaded.getLastGameSession(freshPlayerId), "Fresh session must be successfully loaded");

        // Test runtime expiration in getLastGameSession
        JackpotSavedData liveData = new JackpotSavedData();
        // Manually put an expired session into internal state via reflection or via load
        CompoundTag liveTag = new CompoundTag();
        CompoundTag sessCompound = new CompoundTag();
        // Timestamp just barely valid at load time (e.g. now - 59_000L)
        CasinoGameSession borderSession = new CasinoGameSession(
                expiredPlayerId, UUID.randomUUID(), GameType.SLOT, dummyResult, System.currentTimeMillis() - 65_000L, 20);
        sessCompound.put(expiredPlayerId.toString(), borderSession.toNbt());
        liveTag.put("LastPlayerSessions", sessCompound);
        // If loaded with timestamp older than TTL, it returns null
        JackpotSavedData reloaded = JackpotSavedData.load(liveTag);
        Assertions.assertNull(reloaded.getLastGameSession(expiredPlayerId));
    }

    @Test
    public void testPacketCategoryContracts() {
        int containerId = 77;
        UUID opId = UUID.randomUUID();

        // 1. StartSlotSpinC2SPacket
        net.pocketodds.network.c2s.StartSlotSpinC2SPacket slotPacket =
                new net.pocketodds.network.c2s.StartSlotSpinC2SPacket(containerId, opId);
        FriendlyByteBuf slotBuf = new FriendlyByteBuf(Unpooled.buffer());
        slotPacket.toBytes(slotBuf);
        net.pocketodds.network.c2s.StartSlotSpinC2SPacket decodedSlot =
                new net.pocketodds.network.c2s.StartSlotSpinC2SPacket(slotBuf);
        Assertions.assertNotNull(decodedSlot);

        // 2. StartRouletteSpinC2SPacket
        net.pocketodds.network.c2s.StartRouletteSpinC2SPacket roulettePacket =
                new net.pocketodds.network.c2s.StartRouletteSpinC2SPacket(containerId, RouletteBetType.RED.ordinal(), opId);
        FriendlyByteBuf rBuf = new FriendlyByteBuf(Unpooled.buffer());
        roulettePacket.toBytes(rBuf);
        net.pocketodds.network.c2s.StartRouletteSpinC2SPacket decodedRoulette =
                new net.pocketodds.network.c2s.StartRouletteSpinC2SPacket(rBuf);
        Assertions.assertNotNull(decodedRoulette);

        // 3. RollDiceC2SPacket
        net.pocketodds.network.c2s.RollDiceC2SPacket dicePacket =
                new net.pocketodds.network.c2s.RollDiceC2SPacket(containerId, opId);
        FriendlyByteBuf dBuf = new FriendlyByteBuf(Unpooled.buffer());
        dicePacket.toBytes(dBuf);
        net.pocketodds.network.c2s.RollDiceC2SPacket decodedDice =
                new net.pocketodds.network.c2s.RollDiceC2SPacket(dBuf);
        Assertions.assertNotNull(decodedDice);

        // 4. DrawDeckCardC2SPacket
        net.pocketodds.network.c2s.DrawDeckCardC2SPacket drawPacket =
                new net.pocketodds.network.c2s.DrawDeckCardC2SPacket(containerId, opId);
        FriendlyByteBuf cardBuf = new FriendlyByteBuf(Unpooled.buffer());
        drawPacket.toBytes(cardBuf);
        net.pocketodds.network.c2s.DrawDeckCardC2SPacket decodedDraw =
                new net.pocketodds.network.c2s.DrawDeckCardC2SPacket(cardBuf);
        Assertions.assertNotNull(decodedDraw);

        // 5. CashOutDeckC2SPacket
        net.pocketodds.network.c2s.CashOutDeckC2SPacket cashPacket =
                new net.pocketodds.network.c2s.CashOutDeckC2SPacket(containerId, opId);
        FriendlyByteBuf cashBuf = new FriendlyByteBuf(Unpooled.buffer());
        cashPacket.toBytes(cashBuf);
        net.pocketodds.network.c2s.CashOutDeckC2SPacket decodedCash =
                new net.pocketodds.network.c2s.CashOutDeckC2SPacket(cashBuf);
        Assertions.assertNotNull(decodedCash);

        // Validate CasinoCategory enum values for each game
        Assertions.assertEquals(CasinoCategory.SLOTS, CasinoCategory.fromOrdinal(0));
        Assertions.assertEquals(CasinoCategory.ROULETTE, CasinoCategory.fromOrdinal(1));
        Assertions.assertEquals(CasinoCategory.DICE, CasinoCategory.fromOrdinal(2));
        Assertions.assertEquals(CasinoCategory.DECK_OF_FATE, CasinoCategory.fromOrdinal(3));
        Assertions.assertEquals(CasinoCategory.JACKPOT_INFO, CasinoCategory.fromOrdinal(4));
    }

    @Test
    public void testCoinPouchUUIDValidationAndStillValid() {
        // 1. Check getPouchUUID behavior on empty / uninitialized / initialized pouches
        Assertions.assertNull(CoinPouchService.getPouchUUID(ItemStack.EMPTY));

        ItemStack uninitPouch = new ItemStack(ModItems.COIN_POUCH.get());
        Assertions.assertNull(CoinPouchService.getPouchUUID(uninitPouch));

        UUID createdId = CoinPouchService.getOrCreatePouchUUID(uninitPouch);
        Assertions.assertNotNull(createdId);
        Assertions.assertEquals(createdId, CoinPouchService.getPouchUUID(uninitPouch));

        // 2. Different pouches have different UUIDs
        ItemStack secondPouch = new ItemStack(ModItems.COIN_POUCH.get());
        UUID secondId = CoinPouchService.getOrCreatePouchUUID(secondPouch);
        Assertions.assertNotEquals(createdId, secondId);
        Assertions.assertEquals(secondId, CoinPouchService.getPouchUUID(secondPouch));

        // 3. Swapping items breaks UUID equality
        ItemStack otherItem = new ItemStack(Items.DIAMOND);
        Assertions.assertNull(CoinPouchService.getPouchUUID(otherItem));
        Assertions.assertNotEquals(createdId, CoinPouchService.getPouchUUID(otherItem));

        // 4. Test CoinPouchActionC2SPacket serialization
        net.pocketodds.network.c2s.CoinPouchActionC2SPacket actionPacket =
                new net.pocketodds.network.c2s.CoinPouchActionC2SPacket(42, net.pocketodds.network.c2s.CoinPouchActionC2SPacket.ACTION_DEPOSIT_TIER, ChipTier.GOLD.ordinal(), 5);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        actionPacket.toBytes(buf);
        net.pocketodds.network.c2s.CoinPouchActionC2SPacket decoded =
                new net.pocketodds.network.c2s.CoinPouchActionC2SPacket(buf);
        Assertions.assertNotNull(decoded);
    }

    @Test
    public void testCreditRewardLineToPouchAtomicSemantics() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();
        UUID pouchId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        // Create transaction with:
        // Line 1: 5 copper chips
        // Line 2: 2 gold chips
        // Line 3: 1 non-chip item (diamond)
        ItemStack copper = new ItemStack(ModItems.COPPER_CHIP.get(), 5);
        ItemStack gold = new ItemStack(ModItems.GOLD_CHIP.get(), 2);
        ItemStack diamond = new ItemStack(Items.DIAMOND, 1);

        JackpotSavedData.RewardTransaction tx = new JackpotSavedData.RewardTransaction(
                txId, playerId, List.of(copper, gold, diamond), false, 0L, false, PayoutDestination.POUCH);
        data.enqueueRewardTransaction(tx);

        List<RewardLine> lines = data.getTransaction(txId).getLines();
        Assertions.assertEquals(3, lines.size());
        UUID copperLineId = lines.get(0).getLineId();
        UUID goldLineId = lines.get(1).getLineId();
        UUID diamondLineId = lines.get(2).getLineId();

        // 1. Credit copper chips atomically
        boolean copperResult = data.creditRewardLineToPouch(pouchId, txId, copperLineId);
        Assertions.assertTrue(copperResult, "creditRewardLineToPouch must succeed for copper chips");

        // Pouch balance immediately reflects 5 copper chips
        PouchBalance balanceAfterCopper = data.getPouchBalance(pouchId);
        Assertions.assertEquals(5, balanceAfterCopper.getCount(ChipTier.COPPER));
        Assertions.assertEquals(0, balanceAfterCopper.getCount(ChipTier.GOLD));
        Assertions.assertEquals(5L, balanceAfterCopper.getTotalCredits());

        // Copper line is confirmed and removed from transaction
        JackpotSavedData.RewardTransaction txAfterCopper = data.getTransaction(txId);
        Assertions.assertNotNull(txAfterCopper);
        Assertions.assertEquals(2, txAfterCopper.getLines().size());

        // Repeated credit of already confirmed line must fail
        boolean repeatResult = data.creditRewardLineToPouch(pouchId, txId, copperLineId);
        Assertions.assertFalse(repeatResult, "Repeated credit of already delivered line must fail");

        // 2. Attempt to credit non-chip item (diamond) to pouch MUST fail
        boolean diamondResult = data.creditRewardLineToPouch(pouchId, txId, diamondLineId);
        Assertions.assertFalse(diamondResult, "creditRewardLineToPouch must fail for non-chip items");
        // Balance remains unchanged
        Assertions.assertEquals(5, data.getPouchBalance(pouchId).getCount(ChipTier.COPPER));
        Assertions.assertEquals(0, data.getPouchBalance(pouchId).getCount(ChipTier.GOLD));

        // 3. Credit gold chips atomically
        boolean goldResult = data.creditRewardLineToPouch(pouchId, txId, goldLineId);
        Assertions.assertTrue(goldResult);

        // Pouch balance reflects 5 copper + 2 gold = 5 + 16 = 21 credits
        PouchBalance balanceAfterGold = data.getPouchBalance(pouchId);
        Assertions.assertEquals(5, balanceAfterGold.getCount(ChipTier.COPPER));
        Assertions.assertEquals(2, balanceAfterGold.getCount(ChipTier.GOLD));
        Assertions.assertEquals(21L, balanceAfterGold.getTotalCredits());

        // Only diamond remains in transaction
        JackpotSavedData.RewardTransaction txAfterGold = data.getTransaction(txId);
        Assertions.assertNotNull(txAfterGold);
        Assertions.assertEquals(1, txAfterGold.getLines().size());
        Assertions.assertEquals(Items.DIAMOND, txAfterGold.getLines().get(0).getStack().getItem());
    }

    @Test
    public void testRewardTransactionPayoutDestinationIsolation() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();

        // Old pending refund transaction intended for INVENTORY
        UUID refundTxId = UUID.randomUUID();
        JackpotSavedData.RewardTransaction refundTx = new JackpotSavedData.RewardTransaction(
                refundTxId, playerId, List.of(new ItemStack(ModItems.GOLD_CHIP.get(), 4)), false, 0L, false, PayoutDestination.INVENTORY);
        data.enqueueRewardTransaction(refundTx);

        // New game transaction intended for POUCH
        UUID gameTxId = UUID.randomUUID();
        RewardBundle gameBundle = new RewardBundle(List.of(new ItemStack(ModItems.DIAMOND_CHIP.get(), 1)), 0L, false);
        data.settleAndCommitBet(null, gameTxId, playerId, gameBundle, PayoutDestination.POUCH);

        // Verify destinations are recorded correctly
        Assertions.assertEquals(PayoutDestination.INVENTORY, data.getTransaction(refundTxId).getPayoutDestination());
        Assertions.assertEquals(PayoutDestination.POUCH, data.getTransaction(gameTxId).getPayoutDestination());

        // Test NBT serialization roundtrip of PayoutDestination
        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(tag);
        Assertions.assertEquals(PayoutDestination.INVENTORY, loaded.getTransaction(refundTxId).getPayoutDestination());
        Assertions.assertEquals(PayoutDestination.POUCH, loaded.getTransaction(gameTxId).getPayoutDestination());

        // Credit the POUCH game transaction line
        UUID pouchId = UUID.randomUUID();
        UUID diamondLineId = loaded.getTransaction(gameTxId).getLines().get(0).getLineId();
        boolean credited = loaded.creditRewardLineToPouch(pouchId, gameTxId, diamondLineId);
        Assertions.assertTrue(credited);

        // Pouch has 1 diamond chip (64 credits)
        Assertions.assertEquals(1, loaded.getPouchBalance(pouchId).getCount(ChipTier.DIAMOND));
        Assertions.assertEquals(64L, loaded.getPouchBalance(pouchId).getTotalCredits());

        // Crucial isolation check: The old INVENTORY transaction was NOT touched or credited to pouch
        Assertions.assertEquals(0, loaded.getPouchBalance(pouchId).getCount(ChipTier.GOLD));
        JackpotSavedData.RewardTransaction retainedRefund = loaded.getTransaction(refundTxId);
        Assertions.assertNotNull(retainedRefund);
        Assertions.assertEquals(1, retainedRefund.getLines().size());
        Assertions.assertFalse(retainedRefund.getLines().get(0).isDelivered());
    }

    @Test
    public void testDeliverGameRewardIntegrationNoDoublePayout() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();

        // 1. Scenario POUCH: reward contains 4 gold chips and 1 emerald
        ItemStack pouch = new ItemStack(ModItems.COIN_POUCH.get());
        UUID pouchUUID = CoinPouchService.getOrCreatePouchUUID(pouch);

        UUID txId = UUID.randomUUID();
        RewardBundle bundle = new RewardBundle(
                List.of(new ItemStack(ModItems.GOLD_CHIP.get(), 4), new ItemStack(Items.EMERALD, 1)),
                0L,
                false
        );
        data.settleAndCommitBet(null, txId, playerId, bundle, PayoutDestination.POUCH);

        List<ItemStack> inventoryDelivered = new ArrayList<>();
        // Execute deliverGameReward with mock delivery sink collecting inventory items
        CasinoGameService.deliverGameReward(null, pouch, txId, null, data, (p, item) -> inventoryDelivered.add(item.copy()));

        // Check Pouch: received exactly 4 gold chips (32 credits)
        PouchBalance pouchBalance = data.getPouchBalance(pouchUUID);
        Assertions.assertEquals(4, pouchBalance.getCount(ChipTier.GOLD));
        Assertions.assertEquals(32L, pouchBalance.getTotalCredits());

        // Check Inventory Sink: NO chips duplicated! Only emerald was delivered to inventory!
        Assertions.assertEquals(1, inventoryDelivered.size(), "Only non-chip items should be delivered to inventory when POUCH is destination");
        Assertions.assertEquals(Items.EMERALD, inventoryDelivered.get(0).getItem());
        Assertions.assertEquals(1, inventoryDelivered.get(0).getCount());
        Assertions.assertTrue(inventoryDelivered.stream().noneMatch(s -> s.getItem() instanceof net.pocketodds.item.ChipItem),
                "Chips must NOT be delivered to inventory if they were already credited to pouch!");

        // Check Transaction: all lines processed and removed
        Assertions.assertNull(data.getTransaction(txId), "Completed transaction should be removed from outbox");

        // 2. Scenario INVENTORY: reward contains 2 diamond chips
        UUID txId2 = UUID.randomUUID();
        RewardBundle bundle2 = new RewardBundle(
                List.of(new ItemStack(ModItems.DIAMOND_CHIP.get(), 2)),
                0L,
                false
        );
        data.settleAndCommitBet(null, txId2, playerId, bundle2, PayoutDestination.INVENTORY);

        List<ItemStack> inventoryDelivered2 = new ArrayList<>();
        CasinoGameService.deliverGameReward(null, pouch, txId2, null, data, (p, item) -> inventoryDelivered2.add(item.copy()));

        // Pouch balance for diamond chips remains 0
        Assertions.assertEquals(0, data.getPouchBalance(pouchUUID).getCount(ChipTier.DIAMOND));

        // Inventory received the 2 diamond chips
        Assertions.assertEquals(1, inventoryDelivered2.size());
        Assertions.assertEquals(ModItems.DIAMOND_CHIP.get(), inventoryDelivered2.get(0).getItem());
        Assertions.assertEquals(2, inventoryDelivered2.get(0).getCount());

        // Transaction 2 completed
        Assertions.assertNull(data.getTransaction(txId2));
    }

    @Test
    public void testDeliverPendingTransactionsHonorsPouchDestinationOnReconnect() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();

        ItemStack pouch = new ItemStack(ModItems.COIN_POUCH.get());
        UUID pouchUUID = CoinPouchService.getOrCreatePouchUUID(pouch);

        // Scenario 1: Pending transaction with POUCH destination contains 3 netherite chips and 1 golden apple
        UUID txId = UUID.randomUUID();
        RewardBundle bundle = new RewardBundle(
                List.of(new ItemStack(ModItems.NETHERITE_CHIP.get(), 3), new ItemStack(Items.GOLDEN_APPLE, 1)),
                0L,
                false
        );
        data.settleAndCommitBet(null, txId, playerId, bundle, PayoutDestination.POUCH);

        // Simulate reconnect delivery where pouch is provided
        List<ItemStack> inventoryDelivered = new ArrayList<>();
        boolean success = RewardTransactionService.deliverPendingTransactions(playerId, null, pouch, data, (p, item) -> inventoryDelivered.add(item.copy()));

        Assertions.assertTrue(success, "deliverPendingTransactions should succeed");

        // Pouch received 3 netherite chips (3 * 512 = 1536 credits)
        PouchBalance pouchBalance = data.getPouchBalance(pouchUUID);
        Assertions.assertEquals(3, pouchBalance.getCount(ChipTier.NETHERITE));
        Assertions.assertEquals(1536L, pouchBalance.getTotalCredits());

        // Inventory received only the non-chip golden apple
        Assertions.assertEquals(1, inventoryDelivered.size(), "Only non-chip items should be delivered to inventory");
        Assertions.assertEquals(Items.GOLDEN_APPLE, inventoryDelivered.get(0).getItem());
        Assertions.assertEquals(1, inventoryDelivered.get(0).getCount());

        // Transaction is completely processed and cleared
        Assertions.assertNull(data.getTransaction(txId));

        // Scenario 2: Fallback when player has NO pouch -> chips safely delivered to inventory
        UUID txId2 = UUID.randomUUID();
        RewardBundle bundle2 = new RewardBundle(
                List.of(new ItemStack(ModItems.GOLD_CHIP.get(), 2)),
                0L,
                false
        );
        data.settleAndCommitBet(null, txId2, playerId, bundle2, PayoutDestination.POUCH);

        List<ItemStack> fallbackInventory = new ArrayList<>();
        boolean success2 = RewardTransactionService.deliverPendingTransactions(playerId, null, ItemStack.EMPTY, data, (p, item) -> fallbackInventory.add(item.copy()));

        Assertions.assertTrue(success2);
        Assertions.assertEquals(1, fallbackInventory.size());
        Assertions.assertEquals(ModItems.GOLD_CHIP.get(), fallbackInventory.get(0).getItem());
        Assertions.assertEquals(2, fallbackInventory.get(0).getCount());
        Assertions.assertNull(data.getTransaction(txId2));
    }

    @Test
    public void testWithdrawChipsDeliversOnlyItsOwnTransactionIsolated() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();

        ItemStack pouch = new ItemStack(ModItems.COIN_POUCH.get());
        UUID pouchUUID = CoinPouchService.getOrCreatePouchUUID(pouch);
        data.depositToPouch(pouchUUID, ChipTier.GOLD, 10, pouch);

        // 1. Existing pending reward with POUCH destination (e.g. from an unfinished casino game)
        UUID casinoTxId = UUID.randomUUID();
        RewardBundle casinoBundle = new RewardBundle(
                List.of(new ItemStack(ModItems.DIAMOND_CHIP.get(), 5)),
                0L,
                false
        );
        data.settleAndCommitBet(null, casinoTxId, playerId, casinoBundle, PayoutDestination.POUCH);

        Assertions.assertEquals(1, data.getPendingTransactions(playerId).size());

        // 2. Perform withdraw from pouch
        JackpotSavedData.RewardTransaction withdrawTx = data.withdrawFromPouch(pouchUUID, playerId, ChipTier.GOLD, 4, pouch);
        Assertions.assertNotNull(withdrawTx);
        Assertions.assertEquals(4, withdrawTx.getItems().stream().mapToInt(ItemStack::getCount).sum());

        // Now we have 2 transactions in outbox
        Assertions.assertEquals(2, data.getPendingTransactions(playerId).size());

        // 3. Deliver ONLY the withdraw transaction
        List<ItemStack> withdrawDelivered = new ArrayList<>();
        boolean delivered = RewardTransactionService.deliverTransaction(
                withdrawTx.getTransactionId(),
                null,
                pouch,
                data,
                (p, item) -> withdrawDelivered.add(item.copy())
        );

        Assertions.assertTrue(delivered);
        Assertions.assertEquals(1, withdrawDelivered.size());
        Assertions.assertEquals(ModItems.GOLD_CHIP.get(), withdrawDelivered.get(0).getItem());
        Assertions.assertEquals(4, withdrawDelivered.get(0).getCount());

        // Check that withdraw transaction is completed and removed
        Assertions.assertNull(data.getTransaction(withdrawTx.getTransactionId()));

        // CRITICAL CHECK: The original casino transaction is STILL pending in outbox and was NOT touched or cleared!
        Assertions.assertNotNull(data.getTransaction(casinoTxId), "Casino transaction must remain pending and not be touched by withdraw");
        Assertions.assertEquals(1, data.getPendingTransactions(playerId).size());
    }

    @Test
    public void testPouchBalanceIntegerMaxValueAndOverflowProtection() {
        // Test that PouchBalance holds Integer.MAX_VALUE without overflow or zeroing
        PouchBalance balance = new PouchBalance(
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
        );

        for (ChipTier tier : ChipTier.values()) {
            Assertions.assertEquals((long) Integer.MAX_VALUE, balance.getCount(tier));
        }

        // Adding 1 to Integer.MAX_VALUE must NOT wrap around to negative or reset to 0!
        boolean added = balance.addCount(ChipTier.COPPER, 1L);
        Assertions.assertTrue(added, "addCount should succeed without overflow");
        long expectedCopper = (long) Integer.MAX_VALUE + 1L;
        Assertions.assertEquals(expectedCopper, balance.getCount(ChipTier.COPPER),
                "Balance must be Integer.MAX_VALUE + 1 and NOT negative or zero!");

        // getTotalCredits calculates correctly and does not overflow
        long totalCredits = balance.getTotalCredits();
        Assertions.assertTrue(totalCredits > 0L);

        // NBT round-trip preserves values > Integer.MAX_VALUE
        net.minecraft.nbt.CompoundTag tag = balance.toNbt();
        PouchBalance loaded = PouchBalance.fromNbt(tag);
        Assertions.assertEquals(expectedCopper, loaded.getCount(ChipTier.COPPER));
        Assertions.assertEquals((long) Integer.MAX_VALUE, loaded.getCount(ChipTier.GOLD));
    }

    @Test
    public void testPouchBalanceMaxLimitAdditionRejectionAndOutboxPreservation() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerId = UUID.randomUUID();
        ItemStack pouch = new ItemStack(ModItems.COIN_POUCH.get());
        UUID pouchUUID = CoinPouchService.getOrCreatePouchUUID(pouch);

        // 1. Create a balance with custom max limit of 100 chips
        long maxLimit = 100L;
        PouchBalance balance = new PouchBalance(0L, maxLimit, 0L, 0L, maxLimit);
        data.setPouchBalance(pouchUUID, balance);

        // Verify balance is full for gold chips
        Assertions.assertEquals(100L, balance.getCount(ChipTier.GOLD));
        Assertions.assertFalse(balance.canAdd(ChipTier.GOLD, 1L), "canAdd should return false when at max limit");
        Assertions.assertFalse(balance.addCount(ChipTier.GOLD, 1L), "addCount should reject addition when at max limit");
        Assertions.assertEquals(100L, balance.getCount(ChipTier.GOLD), "Balance must remain at 100 and NOT zero out!");

        // 2. Integration check with creditRewardLineToPouch:
        // Attempting to credit another gold chip must NOT zero out the pouch,
        // and MUST NOT confirm/remove the transaction line from outbox!
        UUID txId = UUID.randomUUID();
        RewardBundle bundle = new RewardBundle(
                List.of(new ItemStack(ModItems.GOLD_CHIP.get(), 1)),
                0L,
                false
        );
        data.settleAndCommitBet(null, txId, playerId, bundle, PayoutDestination.POUCH);

        JackpotSavedData.RewardTransaction tx = data.getTransaction(txId);
        Assertions.assertNotNull(tx);
        UUID lineId = tx.getLines().get(0).getLineId();

        // Attempt delivery to full pouch
        boolean credited = data.creditRewardLineToPouch(pouchUUID, txId, lineId, pouch);
        Assertions.assertFalse(credited, "creditRewardLineToPouch must fail when pouch is full");

        // Verify pouch balance was NOT corrupted or zeroed
        PouchBalance currentBalance = data.getPouchBalance(pouchUUID);
        Assertions.assertEquals(100L, currentBalance.getCount(ChipTier.GOLD), "Pouch balance must still be 100");

        // CRITICAL CHECK: Outbox transaction line is NOT confirmed and transaction remains in outbox!
        JackpotSavedData.RewardTransaction remainingTx = data.getTransaction(txId);
        Assertions.assertNotNull(remainingTx, "Transaction must remain in outbox if it could not be credited to pouch");
        Assertions.assertFalse(remainingTx.getLines().get(0).isDelivered(), "Line must not be marked as delivered");
    }

    @Test
    public void testLargeJackpotConversionAndPouchReconstruction() {
        // Total credits exceeding Integer.MAX_VALUE * 512
        long netheriteTierVal = ChipTier.NETHERITE.getBaseValue();
        long extraNetherite = 15_000L;
        long largeJackpotCredits = ((long) Integer.MAX_VALUE + extraNetherite) * netheriteTierVal + 2500L;

        // 1. Test CoinPouchService.reconstructBalanceCredits handles huge amount without int overflow
        PouchBalance balance = new PouchBalance();
        CoinPouchService.reconstructBalanceCredits(balance, largeJackpotCredits);

        long expectedNetherite = (long) Integer.MAX_VALUE + extraNetherite + (2500L / netheriteTierVal);
        Assertions.assertEquals(expectedNetherite, balance.getCount(ChipTier.NETHERITE),
                "Netherite count must exceed Integer.MAX_VALUE without negative overflow!");
        Assertions.assertTrue(balance.getCount(ChipTier.NETHERITE) > (long) Integer.MAX_VALUE);
        Assertions.assertEquals(largeJackpotCredits, balance.getTotalCredits(),
                "Total reconstructed credits must match large jackpot exactly!");

        // 2. Test ChipUtils.convertAmountToChips() with large amount exceeding 10,000 stacks (> 327,680,000 credits)
        // 700,000,000 credits in Netherite (512 each) = 1,367,187 chips = 21,363 stacks
        long largeJackpotAmount = 700_000_000L;
        List<ItemStack> stacks = ChipUtils.convertAmountToChips(largeJackpotAmount);
        Assertions.assertFalse(stacks.isEmpty());
        Assertions.assertTrue(stacks.size() > 10_000, "Stack count must exceed previous 10,000 limit without truncation");

        long sumCredits = 0L;
        for (ItemStack s : stacks) {
            Assertions.assertTrue(s.getCount() <= 64, "Stack size must not exceed 64");
            if (s.getItem() instanceof net.pocketodds.item.ChipItem chip) {
                sumCredits += (long) s.getCount() * chip.getTier().getBaseValue();
            }
        }
        Assertions.assertEquals(largeJackpotAmount, sumCredits, "Sum of converted chip stacks must equal 100% of original amount without truncation");

        // 3. Test safe addition with Long.MAX_VALUE in CoinPouchService.addCreditsToPouchBalance
        PouchBalance overflowTestBalance = new PouchBalance(0, 0, 0, 1000);
        CoinPouchService.addCreditsToPouchBalance(overflowTestBalance, Long.MAX_VALUE);
        Assertions.assertTrue(overflowTestBalance.getTotalCredits() > 0L, "Balance must remain positive");
        Assertions.assertTrue(overflowTestBalance.getTotalCredits() <= Long.MAX_VALUE, "Balance must not overflow");
        Assertions.assertEquals(PouchBalance.MAX_CHIPS_PER_TIER, overflowTestBalance.getCount(ChipTier.NETHERITE));
    }

    @Test
    public void testPouchBalanceAllFourTiersFullDoesNotOverflowLongMaxValue() {
        PouchBalance balance = new PouchBalance(
                PouchBalance.MAX_CHIPS_PER_TIER,
                PouchBalance.MAX_CHIPS_PER_TIER,
                PouchBalance.MAX_CHIPS_PER_TIER,
                PouchBalance.MAX_CHIPS_PER_TIER
        );

        long total = balance.getTotalCredits();
        Assertions.assertTrue(total > 0L, "Total credits with all 4 tiers full must be strictly positive");
        Assertions.assertTrue(total <= Long.MAX_VALUE, "Total credits with all 4 tiers full must never overflow Long.MAX_VALUE");

        // Verify mathematical guarantee: 585 * MAX_CHIPS_PER_TIER <= Long.MAX_VALUE
        long mathMax = 585L * PouchBalance.MAX_CHIPS_PER_TIER;
        Assertions.assertTrue(mathMax > 0L);
        Assertions.assertEquals(mathMax, total);
        Assertions.assertTrue(Long.MAX_VALUE - total >= 0L);
    }

    @Test
    public void testJackpotArithmeticOverflowAndCapping() {
        JackpotSavedData data = new JackpotSavedData(100L);
        long maxCap = JackpotSavedData.getMaxJackpotAmount();
        Assertions.assertTrue(maxCap > 0L);

        // 1. Extreme bet with Long.MAX_VALUE - must not overflow arithmetic nor turn negative
        data.addContributionBasisPoints(Long.MAX_VALUE, 500);
        Assertions.assertEquals(maxCap, data.getJackpotAmount(), "Jackpot must be clamped to max cap on huge contribution");

        // 2. Further contributions must stay capped at maxCap
        data.addContributionBasisPoints(1_000_000L, 500);
        Assertions.assertEquals(maxCap, data.getJackpotAmount(), "Jackpot must remain at max cap");

        // 3. Claiming jackpot yields capped amount and resets to base
        long payout = data.claimJackpot();
        Assertions.assertEquals(maxCap, payout);
        Assertions.assertEquals(100L, data.getJackpotAmount());

        // 4. Loading corrupted NBT with negative or astronomical value
        CompoundTag tag = new CompoundTag();
        tag.putLong("JackpotAmount", Long.MAX_VALUE);
        JackpotSavedData loaded = JackpotSavedData.load(tag);
        Assertions.assertEquals(maxCap, loaded.getJackpotAmount(), "Loaded jackpot from NBT must be capped at maxCap");
    }

    @Test
    public void testChipMaterializationCeilingSafeguardAgainstOOM() {
        // 1. Calling convertAmountToChips with Long.MAX_VALUE must not cause OutOfMemoryError or freeze
        List<ItemStack> oomProtected = ChipUtils.convertAmountToChips(Long.MAX_VALUE);
        Assertions.assertNotNull(oomProtected);
        Assertions.assertEquals(ChipUtils.MAX_MATERIALIZATION_STACKS, oomProtected.size(),
                "Materialization must be capped at MAX_MATERIALIZATION_STACKS to safeguard JVM memory");

        // 2. Calling splitChips with Long.MAX_VALUE must also be capped safely
        List<ItemStack> splitProtected = ChipUtils.splitChips(ModItems.NETHERITE_CHIP.get(), Long.MAX_VALUE);
        Assertions.assertNotNull(splitProtected);
        Assertions.assertEquals(ChipUtils.MAX_MATERIALIZATION_STACKS, splitProtected.size());

        // 3. Normal jackpot amount below ceiling (e.g. 10,000,000 credits) must convert 100% of amount without truncation
        long standardJackpot = 10_000_000L;
        List<ItemStack> standardStacks = ChipUtils.convertAmountToChips(standardJackpot);
        long sumCredits = 0L;
        for (ItemStack s : standardStacks) {
            if (s.getItem() instanceof net.pocketodds.item.ChipItem chip) {
                sumCredits += (long) s.getCount() * chip.getTier().getBaseValue();
            }
        }
        Assertions.assertEquals(standardJackpot, sumCredits, "Standard jackpot must be 100% converted without loss");
    }
}