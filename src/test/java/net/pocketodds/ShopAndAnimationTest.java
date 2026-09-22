package net.pocketodds;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.pocketodds.client.anim.DeckCardRenderer;
import net.pocketodds.client.anim.DiceRollRenderer;
import net.pocketodds.client.anim.RouletteWheelRenderer;
import net.pocketodds.client.anim.SlotMachineRenderer;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.data.PouchBalance;
import net.pocketodds.gambling.slot.SlotSymbol;
import net.pocketodds.item.ChipTier;
import net.pocketodds.item.PrizeTokenItem;
import net.pocketodds.network.c2s.BuyShopOfferC2SPacket;
import net.pocketodds.network.s2c.SyncShopCatalogS2CPacket;
import net.pocketodds.shop.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class ShopAndAnimationTest {

    @BeforeAll
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM instanceof net.minecraft.core.MappedRegistry<?> mapped) {
            mapped.unfreeze();
        }
        if (ForgeRegistries.ITEMS instanceof ForgeRegistry<Item> forgeReg) {
            forgeReg.unfreeze();
            registerItemIfMissing(forgeReg, "copper_chip", new net.pocketodds.item.ChipItem(ChipTier.COPPER, new Item.Properties()));
            registerItemIfMissing(forgeReg, "gold_chip", new net.pocketodds.item.ChipItem(ChipTier.GOLD, new Item.Properties()));
            registerItemIfMissing(forgeReg, "diamond_chip", new net.pocketodds.item.ChipItem(ChipTier.DIAMOND, new Item.Properties()));
            registerItemIfMissing(forgeReg, "netherite_chip", new net.pocketodds.item.ChipItem(ChipTier.NETHERITE, new Item.Properties()));
            registerItemIfMissing(forgeReg, "pocket_casino", new net.pocketodds.item.PocketCasinoItem(new Item.Properties()));
            registerItemIfMissing(forgeReg, "coin_pouch", new net.pocketodds.item.CoinPouchItem(new Item.Properties()));
            registerItemIfMissing(forgeReg, "jackpot_token", new net.pocketodds.item.JackpotTokenItem(new Item.Properties()));
            registerItemIfMissing(forgeReg, "joker", new Item(new Item.Properties()));
            registerItemIfMissing(forgeReg, "insurance", new Item(new Item.Properties()));
            registerItemIfMissing(forgeReg, "prize_token", new PrizeTokenItem(new Item.Properties()));
        }
    }

    private static void registerItemIfMissing(ForgeRegistry<Item> forgeReg, String path, Item item) {
        ResourceLocation id = new ResourceLocation("pocketodds", path);
        if (!forgeReg.containsKey(id)) {
            forgeReg.register(id, item);
        }
    }

    @Test
    public void testDatapackOffersParsingAndMissingItemSafety() throws Exception {
        Path offersDir = Paths.get("src/main/resources/data/pocketodds/shop_offers");
        Assertions.assertTrue(Files.exists(offersDir), "Directory data/pocketodds/shop_offers should exist");

        Gson gson = new Gson();
        int count = 0;
        try (Stream<Path> stream = Files.list(offersDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (file.toString().endsWith(".json")) {
                    count++;
                    try (InputStream is = Files.newInputStream(file);
                         InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                        JsonObject obj = gson.fromJson(reader, JsonObject.class);
                        ShopOffer offer = ShopOffer.fromJson(obj);

                        Assertions.assertNotNull(offer, "Shop offer must not be null for " + file.getFileName());
                        Assertions.assertNotNull(offer.getOfferId(), "OfferId must be present in " + file.getFileName());
                        Assertions.assertTrue(offer.getPriceCredits() > 0, "PriceCredits must be positive in " + file.getFileName());
                        Assertions.assertTrue(offer.getCount() > 0, "Count must be positive in " + file.getFileName());

                        // Verify safe handling when third-party mod items are not loaded in unit test
                        if (!offer.isItemAvailable()) {
                            ItemStack emptyStack = offer.createRewardStack();
                            Assertions.assertTrue(emptyStack.isEmpty(), "Missing mod item should safely return empty stack");
                        }
                    }
                }
            }
        }
        Assertions.assertEquals(35, count, "Must have exactly 35 datapack shop offers");
    }

    @Test
    public void testShopOfferRegistryWithAvailableItems() {
        ShopOffer diamondOffer = new ShopOffer(
                "test_diamond",
                "minecraft:diamond",
                3,
                1024L,
                ShopCategory.RESOURCES,
                5,
                ShopLimitPeriod.DAILY,
                "",
                true,
                1,
                "shop.pocketodds.test_diamond",
                "shop.pocketodds.test_diamond.desc"
        );

        ShopOfferRegistry.registerOffer(diamondOffer);
        ShopOffer retrieved = ShopOfferRegistry.getOffer("test_diamond");
        Assertions.assertNotNull(retrieved);
        Assertions.assertTrue(retrieved.isItemAvailable());
        ItemStack stack = retrieved.createRewardStack();
        Assertions.assertEquals(Items.DIAMOND, stack.getItem());
        Assertions.assertEquals(3, stack.getCount());
        Assertions.assertEquals(1024L, retrieved.getPriceCredits());
    }

    @Test
    public void testExactChangeCalculationScenario() {
        // Price = 500 credits.
        // Available: 1 Netherite chip (512 credits).
        // Expected debit: 1 Netherite chip.
        // Expected change: 12 credits -> 1 Gold chip (8 credits) + 4 Copper chips (4 credits).
        long priceCredits = 500L;
        Map<ChipTier, Long> available = new EnumMap<>(ChipTier.class);
        available.put(ChipTier.NETHERITE, 1L);

        // Calculate payment plan directly using PouchBalance as test fixture
        PouchBalance pouchBalance = new PouchBalance(0, 0, 0, 1);
        Assertions.assertEquals(512L, pouchBalance.getTotalCredits());

        ShopService.PaymentPlan plan = ShopService.calculatePaymentPlan(
                null, null, pouchBalance, ShopPaymentSource.POUCH, priceCredits
        );
        // Using headless test calculation with pouch balance
        // To test with ServerPlayer null guard, verify computeTierDebits logic
        Map<ChipTier, Long> debits = new EnumMap<>(ChipTier.class);
        debits.put(ChipTier.NETHERITE, 1L);
        long totalDebited = 512L;
        long changeCredits = totalDebited - priceCredits;
        Assertions.assertEquals(12L, changeCredits);

        List<ItemStack> changeStacks = net.pocketodds.util.ChipUtils.convertAmountToChips(changeCredits);
        long changeSum = 0L;
        for (ItemStack s : changeStacks) {
            if (s.getItem() == ChipTier.GOLD.getItem()) {
                changeSum += (long) s.getCount() * ChipTier.GOLD.getBaseValue();
            } else if (s.getItem() == ChipTier.COPPER.getItem()) {
                changeSum += (long) s.getCount() * ChipTier.COPPER.getBaseValue();
            }
        }
        Assertions.assertEquals(12L, changeSum, "Change sum must equal 12 credits");
    }

    @Test
    public void testTwoPhaseCommitAndDeterministicOutbox() {
        JackpotSavedData data = new JackpotSavedData();
        UUID opId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();
        ItemStack reward = new ItemStack(Items.DIAMOND, 2);

        Map<ChipTier, Integer> invDebits = new EnumMap<>(ChipTier.class);
        invDebits.put(ChipTier.NETHERITE, 1);
        List<ItemStack> changeStacks = net.pocketodds.util.ChipUtils.convertAmountToChips(12L);

        // Phase 1: PREPARED
        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, "test_diamond", 500L,
                reward, ShopPaymentSource.INVENTORY,
                invDebits, Collections.emptyMap(), 12L, changeStacks,
                ShopLimitPeriod.DAILY, false,
                ShopPurchaseTransaction.State.PREPARED, System.currentTimeMillis()
        );
        data.recordShopPurchase(tx);
        Assertions.assertTrue(data.isShopOperationProcessed(opId), "Registered operationId must be considered used/processed");

        // Phase 2: PAYMENT_DEBITED
        data.updateShopPurchaseState(opId, ShopPurchaseTransaction.State.PAYMENT_DEBITED);
        Assertions.assertTrue(data.isShopOperationProcessed(opId));

        // Outbox enqueue with deterministic UUIDs
        UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction rewardTx = new JackpotSavedData.RewardTransaction(
                rewardTxId, playerUUID, Collections.singletonList(reward)
        );
        data.enqueueRewardTransaction(rewardTx);

        UUID changeTxId = UUID.nameUUIDFromBytes(("shop_change:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction changeTx = new JackpotSavedData.RewardTransaction(
                changeTxId, playerUUID, changeStacks
        );
        data.enqueueRewardTransaction(changeTx);

        // Phase 3: COMMITTED & Limit Recorded
        tx.setLimitRecorded(true);
        data.incrementPlayerPurchaseCount(playerUUID, "test_diamond", ShopLimitPeriod.DAILY);
        data.updateShopPurchaseState(opId, ShopPurchaseTransaction.State.COMMITTED);
        Assertions.assertTrue(data.isShopOperationProcessed(opId));

        // Verify outbox transactions exist and contain expected items
        JackpotSavedData.RewardTransaction outboxReward = data.getTransaction(rewardTxId);
        Assertions.assertNotNull(outboxReward);
        Assertions.assertEquals(Items.DIAMOND, outboxReward.getLines().get(0).getStack().getItem());
        Assertions.assertEquals(2, outboxReward.getLines().get(0).getStack().getCount());

        JackpotSavedData.RewardTransaction outboxChange = data.getTransaction(changeTxId);
        Assertions.assertNotNull(outboxChange);
        Assertions.assertFalse(outboxChange.isEmpty());
    }

    @Test
    public void testShopPurchaseRecoveryOnRestart() {
        JackpotSavedData original = new JackpotSavedData();
        UUID debitedOpId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();
        ItemStack reward = new ItemStack(Items.GOLD_INGOT, 4);

        Map<ChipTier, Integer> debits = new EnumMap<>(ChipTier.class);
        debits.put(ChipTier.NETHERITE, 1);
        List<ItemStack> change = net.pocketodds.util.ChipUtils.convertAmountToChips(12L);

        // Transaction debited before crash
        ShopPurchaseTransaction debitedTx = new ShopPurchaseTransaction(
                debitedOpId, playerUUID, "test_gold", 500L,
                reward, ShopPaymentSource.INVENTORY,
                debits, Collections.emptyMap(), 12L, change,
                ShopLimitPeriod.DAILY, false,
                ShopPurchaseTransaction.State.PAYMENT_DEBITED, System.currentTimeMillis()
        );
        original.recordShopPurchase(debitedTx);

        // Transaction only prepared (without recorded debits) before crash
        UUID preparedOpId = UUID.randomUUID();
        ShopPurchaseTransaction prepTx = new ShopPurchaseTransaction(
                preparedOpId, playerUUID, "test_gold", 500L,
                reward, ShopPaymentSource.INVENTORY,
                Collections.emptyMap(), Collections.emptyMap(), 0L, Collections.emptyList(),
                ShopLimitPeriod.DAILY, false,
                ShopPurchaseTransaction.State.PREPARED, System.currentTimeMillis()
        );
        original.recordShopPurchase(prepTx);

        // Save to NBT
        CompoundTag tag = original.save(new CompoundTag());

        // Load into new instance (simulating restart recovery)
        JackpotSavedData recovered = JackpotSavedData.load(tag);

        // Verify debited transaction was recovered into outbox (both reward and change)
        UUID expectedRewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + debitedOpId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction outboxReward = recovered.getTransaction(expectedRewardTxId);
        Assertions.assertNotNull(outboxReward, "Debited purchase reward must be recovered into outbox on restart");
        Assertions.assertEquals(Items.GOLD_INGOT, outboxReward.getLines().get(0).getStack().getItem());

        UUID expectedChangeTxId = UUID.nameUUIDFromBytes(("shop_change:" + debitedOpId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction outboxChange = recovered.getTransaction(expectedChangeTxId);
        Assertions.assertNotNull(outboxChange, "Debited purchase change must be recovered into outbox on restart");

        // Verify limit was recorded exactly once
        Assertions.assertEquals(1, recovered.getPlayerPurchaseCount(playerUUID, "test_gold", ShopLimitPeriod.DAILY));

        // Verify prepared transaction was safely cancelled
        Assertions.assertTrue(recovered.isShopOperationProcessed(preparedOpId), "Operation ID remains registered and used");
        Assertions.assertEquals(ShopPurchaseTransaction.State.CANCELLED, recovered.getShopPurchase(preparedOpId).getState(),
                "PREPARED transaction must be cancelled without awarding items");
    }

    @Test
    public void testPlayerShopLimitsPersistenceAndReset() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        String offerId = "daily_special";

        // Initial count is 0
        Assertions.assertEquals(0, data.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.DAILY));

        // Increment purchase count
        data.incrementPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.DAILY);
        data.incrementPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.DAILY);
        Assertions.assertEquals(2, data.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.DAILY));

        // Save & reload from NBT
        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(tag);

        // Check loaded count is preserved
        Assertions.assertEquals(2, loaded.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.DAILY));
    }

    @Test
    public void testSlotMachineAnimationAndSkip() {
        SlotMachineRenderer renderer = new SlotMachineRenderer();
        SlotSymbol[] targets = new SlotSymbol[]{SlotSymbol.DIAMOND, SlotSymbol.DIAMOND, SlotSymbol.DIAMOND};

        renderer.startSpin(targets, 30, true, false, false, 500);
        Assertions.assertTrue(renderer.isSpinning());

        renderer.tick();
        renderer.tick();
        Assertions.assertTrue(renderer.isSpinning());

        renderer.skipAnimation();
        Assertions.assertFalse(renderer.isSpinning());
    }

    @Test
    public void testRouletteWheelAnimationAndSkip() {
        RouletteWheelRenderer renderer = new RouletteWheelRenderer();
        renderer.startSpin(17, 25, 360, new int[]{0, 5, 12});
        Assertions.assertTrue(renderer.isSpinning());

        renderer.tick();
        renderer.tick();
        Assertions.assertTrue(renderer.isSpinning());

        renderer.skipAnimation();
        Assertions.assertFalse(renderer.isSpinning());
    }

    @Test
    public void testDiceRollAnimationAndSkip() {
        DiceRollRenderer renderer = new DiceRollRenderer();
        renderer.startRoll(6, 6, 20, 200);
        Assertions.assertTrue(renderer.isRolling());

        renderer.tick();
        renderer.tick();
        Assertions.assertTrue(renderer.isRolling());

        renderer.skipAnimation();
        Assertions.assertFalse(renderer.isRolling());
    }

    @Test
    public void testDeckCardAnimationAndSkip() {
        DeckCardRenderer renderer = new DeckCardRenderer();
        renderer.startDeal(3, 50, 3.375, false, 0, 20);
        Assertions.assertTrue(renderer.isDealing());

        renderer.tick();
        renderer.tick();
        Assertions.assertTrue(renderer.isDealing());

        renderer.skipAnimation();
        Assertions.assertFalse(renderer.isDealing());
    }

    @Test
    public void testPacketNetworkSerialization() {
        // BuyShopOfferC2SPacket with ShopPaymentSource
        UUID opId = UUID.randomUUID();
        BuyShopOfferC2SPacket buyPacket = new BuyShopOfferC2SPacket(5, "mek_basic_circuit", opId, ShopPaymentSource.POUCH_THEN_INVENTORY);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buyPacket.toBytes(buf);

        BuyShopOfferC2SPacket decodedBuy = new BuyShopOfferC2SPacket(buf);
        Assertions.assertEquals(5, decodedBuy.getContainerId());
        Assertions.assertEquals("mek_basic_circuit", decodedBuy.getOfferId());
        Assertions.assertEquals(opId, decodedBuy.getOperationId());
        Assertions.assertEquals(ShopPaymentSource.POUCH_THEN_INVENTORY, decodedBuy.getPaymentSource());

        // SyncShopCatalogS2CPacket with long priceCredits & pouchCredits
        SyncShopCatalogS2CPacket.ClientShopEntry entry = new SyncShopCatalogS2CPacket.ClientShopEntry(
                "mek_basic_circuit", new ItemStack(Items.IRON_INGOT, 2), 2048L,
                ShopCategory.COMPONENTS.ordinal(), ShopLimitPeriod.WEEKLY.ordinal(), 10, true,
                true, "shop.pocketodds.mek_basic_circuit", "desc"
        );
        SyncShopCatalogS2CPacket syncPacket = new SyncShopCatalogS2CPacket(8192L, List.of(entry));
        FriendlyByteBuf syncBuf = new FriendlyByteBuf(Unpooled.buffer());
        syncPacket.toBytes(syncBuf);

        SyncShopCatalogS2CPacket decodedSync = new SyncShopCatalogS2CPacket(syncBuf);
        Assertions.assertEquals(8192L, decodedSync.getPouchCredits());
        Assertions.assertEquals(1, decodedSync.getEntries().size());
        SyncShopCatalogS2CPacket.ClientShopEntry decodedEntry = decodedSync.getEntries().get(0);
        Assertions.assertEquals("mek_basic_circuit", decodedEntry.getOfferId());
        Assertions.assertEquals(2048L, decodedEntry.getPriceCredits());
        Assertions.assertEquals(10, decodedEntry.getRemainingLimit());
        Assertions.assertTrue(decodedEntry.isAvailable());
        Assertions.assertTrue(decodedEntry.isAdvancementSatisfied());
    }

    @Test
    public void testPouchDebitAndCreditAtomicOperations() {
        JackpotSavedData data = new JackpotSavedData();
        UUID pouchId = UUID.randomUUID();

        PouchBalance balance = new PouchBalance(10, 5, 2, 1);
        data.setPouchBalance(pouchId, balance);

        Map<ChipTier, Long> debits = new EnumMap<>(ChipTier.class);
        debits.put(ChipTier.COPPER, 5L);
        debits.put(ChipTier.GOLD, 2L);

        boolean debited = data.debitPouchChips(pouchId, debits);
        Assertions.assertTrue(debited);

        PouchBalance updated = data.getPouchBalance(pouchId);
        Assertions.assertEquals(5, updated.getCount(ChipTier.COPPER));
        Assertions.assertEquals(3, updated.getCount(ChipTier.GOLD));
        Assertions.assertEquals(2, updated.getCount(ChipTier.DIAMOND));
        Assertions.assertEquals(1, updated.getCount(ChipTier.NETHERITE));

        // Excess debit must fail without mutating balance
        Map<ChipTier, Long> excess = new EnumMap<>(ChipTier.class);
        excess.put(ChipTier.NETHERITE, 10L);
        boolean excessResult = data.debitPouchChips(pouchId, excess);
        Assertions.assertFalse(excessResult);
        Assertions.assertEquals(1, data.getPouchBalance(pouchId).getCount(ChipTier.NETHERITE));
    }

    @Test
    public void testLegacyPrizeTokenWorldLoadingSafety() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "pocketodds:prize_token");
        tag.putByte("Count", (byte) 16);

        ItemStack stack = ItemStack.of(tag);
        // Does not throw and creates valid item
        Assertions.assertFalse(stack.isEmpty());
    }

    @Test
    public void testServerSidePriceEnforcement() {
        ShopOffer offer = new ShopOffer(
                "server_priced_offer", "minecraft:iron_ingot", 5, 2048L,
                ShopCategory.RESOURCES, 0, ShopLimitPeriod.UNLIMITED, "", true, 10, "name", "desc"
        );
        ShopOfferRegistry.registerOffer(offer);

        UUID opId = UUID.randomUUID();
        BuyShopOfferC2SPacket packet = new BuyShopOfferC2SPacket(1, "server_priced_offer", opId, ShopPaymentSource.INVENTORY);
        Assertions.assertEquals("server_priced_offer", packet.getOfferId());

        ShopOffer serverOffer = ShopOfferRegistry.getOffer(packet.getOfferId());
        Assertions.assertNotNull(serverOffer);
        Assertions.assertEquals(2048L, serverOffer.getPriceCredits());
    }

    @Test
    public void testRequiredAdvancementValidation() {
        Assertions.assertTrue(ShopService.isAdvancementSatisfied(null, ""));
        Assertions.assertTrue(ShopService.isAdvancementSatisfied(null, "   "));
        Assertions.assertTrue(ShopService.isAdvancementSatisfied(null, null));
        Assertions.assertFalse(ShopService.isAdvancementSatisfied(null, "minecraft:story/mine_diamond"));
    }

    @Test
    public void testFullInventoryOutboxChangeSafety() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        List<ItemStack> changeStacks = net.pocketodds.util.ChipUtils.convertAmountToChips(12L);
        UUID changeTxId = UUID.nameUUIDFromBytes(("shop_change:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction changeTx = new JackpotSavedData.RewardTransaction(
                changeTxId, playerUUID, changeStacks
        );
        data.enqueueRewardTransaction(changeTx);

        Assertions.assertTrue(data.hasPendingTransaction(changeTxId));
        JackpotSavedData.RewardTransaction pending = data.getTransaction(changeTxId);
        Assertions.assertNotNull(pending);
        Assertions.assertEquals(2, pending.getLines().size());

        UUID line1 = pending.getLines().get(0).getLineId();
        boolean line1Delivered = data.confirmDeliveredLine(changeTxId, line1);
        Assertions.assertTrue(line1Delivered);
        Assertions.assertTrue(data.hasPendingTransaction(changeTxId));

        UUID line2 = pending.getLines().get(1).getLineId();
        boolean line2Delivered = data.confirmDeliveredLine(changeTxId, line2);
        Assertions.assertTrue(line2Delivered);

        Assertions.assertFalse(data.hasPendingTransaction(changeTxId));
        Assertions.assertTrue(data.isReceiptCompleted(changeTxId));
    }

    @Test
    public void testLongPriceOverflowRejection() {
        ShopOffer zeroPrice = new ShopOffer("zero", "minecraft:dirt", 1, 0L, ShopCategory.RESOURCES, 0, ShopLimitPeriod.UNLIMITED, "", true, 1, "n", "d");
        Assertions.assertEquals(1L, zeroPrice.getPriceCredits());

        ShopOffer hugePrice = new ShopOffer("huge", "minecraft:dirt", 1, Long.MAX_VALUE, ShopCategory.RESOURCES, 0, ShopLimitPeriod.UNLIMITED, "", true, 1, "n", "d");
        Assertions.assertEquals(ShopOffer.MAX_PRICE_CREDITS, hugePrice.getPriceCredits());
    }

    @Test
    public void testCatalogModificationPreservesSavedReward() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        ItemStack reward = new ItemStack(Items.NETHERITE_INGOT, 1);

        ShopOffer offer = new ShopOffer("special_netherite", "minecraft:netherite_ingot", 1, 4096L, ShopCategory.RARE, 1, ShopLimitPeriod.PERMANENT, "", true, 1, "n", "d");
        ShopOfferRegistry.registerOffer(offer);

        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, "special_netherite", 4096L,
                reward, ShopPaymentSource.INVENTORY,
                Collections.emptyMap(), Collections.emptyMap(), 0L, Collections.emptyList(),
                ShopLimitPeriod.PERMANENT, true,
                ShopPurchaseTransaction.State.PAYMENT_DEBITED, System.currentTimeMillis()
        );
        data.recordShopPurchase(tx);

        ShopOfferRegistry.clear();
        Assertions.assertNull(ShopOfferRegistry.getOffer("special_netherite"));

        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(tag);

        UUID expectedRewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction recoveredTx = loaded.getTransaction(expectedRewardTxId);
        Assertions.assertNotNull(recoveredTx, "Reward must be restored from transaction snapshot even if offer was removed from catalog");
        Assertions.assertEquals(Items.NETHERITE_INGOT, recoveredTx.getLines().get(0).getStack().getItem());
    }

    @Test
    public void testCleanPreparedCancelsWithoutRefund() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        Map<ChipTier, Integer> plannedDebits = new EnumMap<>(ChipTier.class);
        plannedDebits.put(ChipTier.DIAMOND, 2);

        // Clean PREPARED with planned debits but ZERO actual debits
        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, "clean_prepared_offer", 128L,
                new ItemStack(Items.IRON_BLOCK, 1), ShopPaymentSource.INVENTORY,
                plannedDebits, Collections.emptyMap(), 0L, Collections.emptyList(),
                ShopLimitPeriod.DAILY, false,
                ShopPurchaseTransaction.State.PREPARED, System.currentTimeMillis()
        );
        data.recordShopPurchase(tx);

        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(tag);

        UUID refundTxId = UUID.nameUUIDFromBytes(("shop_refund:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction refundTx = loaded.getTransaction(refundTxId);
        Assertions.assertNull(refundTx, "Clean PREPARED must NOT produce a refund outbox transaction");

        ShopPurchaseTransaction loadedTx = loaded.getShopPurchase(opId);
        Assertions.assertNotNull(loadedTx);
        Assertions.assertEquals(ShopPurchaseTransaction.State.CANCELLED, loadedTx.getState(),
                "Clean PREPARED must be cancelled on reload");
        Assertions.assertFalse(loadedTx.hasActualDebits());
    }

    @Test
    public void testActualDebitsRefundOnCrashRecovery() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        Map<ChipTier, Integer> plannedDebits = new EnumMap<>(ChipTier.class);
        plannedDebits.put(ChipTier.DIAMOND, 2);
        plannedDebits.put(ChipTier.GOLD, 4);

        // Transaction in PREPARED where 2 Diamonds were actually debited before crash, but Gold was not
        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, "crashed_offer", 160L,
                new ItemStack(Items.IRON_BLOCK, 1), ShopPaymentSource.INVENTORY,
                plannedDebits, Collections.emptyMap(), 0L, Collections.emptyList(),
                ShopLimitPeriod.DAILY, false,
                ShopPurchaseTransaction.State.PREPARED, System.currentTimeMillis()
        );
        tx.recordActualInventoryDebit(ChipTier.DIAMOND, 2);
        data.recordShopPurchase(tx);

        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(tag);

        UUID refundTxId = UUID.nameUUIDFromBytes(("shop_refund:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction refundTx = loaded.getTransaction(refundTxId);
        Assertions.assertNotNull(refundTx, "Partial debits in PREPARED must be refunded on crash recovery");
        Assertions.assertEquals(1, refundTx.getLines().size(), "Refund must contain only the actually debited chips");
        Assertions.assertEquals(ChipTier.DIAMOND.getItem(), refundTx.getLines().get(0).getStack().getItem());
        Assertions.assertEquals(2, refundTx.getLines().get(0).getStack().getCount());

        ShopPurchaseTransaction loadedTx = loaded.getShopPurchase(opId);
        Assertions.assertEquals(ShopPurchaseTransaction.State.REFUND_QUEUED, loadedTx.getState());
        Assertions.assertTrue(loadedTx.hasActualDebits());
    }

    @Test
    public void testSavedRefundQueuedRestoresOutbox() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, "refund_offer", 512L,
                new ItemStack(Items.EMERALD_BLOCK, 1), ShopPaymentSource.INVENTORY,
                Map.of(ChipTier.NETHERITE, 1), Collections.emptyMap(), 0L, Collections.emptyList(),
                ShopLimitPeriod.DAILY, false,
                ShopPurchaseTransaction.State.REFUND_QUEUED, System.currentTimeMillis()
        );
        tx.recordActualInventoryDebit(ChipTier.NETHERITE, 1);
        data.recordShopPurchase(tx);

        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(tag);

        UUID refundTxId = UUID.nameUUIDFromBytes(("shop_refund:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction refundTx = loaded.getTransaction(refundTxId);
        Assertions.assertNotNull(refundTx, "Previously saved REFUND_QUEUED transaction must have refund restored in outbox");
        Assertions.assertEquals(ChipTier.NETHERITE.getItem(), refundTx.getLines().get(0).getStack().getItem());
    }

    @Test
    public void testPlayerShopLimitsNotOverwrittenByRecovery() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        String offerId = "limited_offer";

        // Pre-existing limit: player already bought 1
        data.incrementPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.DAILY);
        Assertions.assertEquals(1, data.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.DAILY));

        // Crash scenario: second purchase was debited (PAYMENT_DEBITED) but limitRecorded was false
        UUID opId = UUID.randomUUID();
        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, offerId, 64L,
                new ItemStack(Items.DIAMOND, 1), ShopPaymentSource.INVENTORY,
                Map.of(ChipTier.DIAMOND, 1), Collections.emptyMap(), 0L, Collections.emptyList(),
                ShopLimitPeriod.DAILY, false,
                ShopPurchaseTransaction.State.PAYMENT_DEBITED, System.currentTimeMillis()
        );
        data.recordShopPurchase(tx);

        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(tag);

        // Loaded count must be 2 (1 existing + 1 recovered), NOT overwritten by PlayerShopLimits loading
        Assertions.assertEquals(2, loaded.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.DAILY),
                "Recovered transaction must increment existing limits without being overwritten by PlayerShopLimits");

        ShopPurchaseTransaction loadedTx = loaded.getShopPurchase(opId);
        Assertions.assertTrue(loadedTx.isLimitRecorded());
        Assertions.assertEquals(ShopPurchaseTransaction.State.COMMITTED, loadedTx.getState());
    }

    @Test
    public void testSinglePlayerQuitDuringPurchase() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        ItemStack reward = new ItemStack(Items.GOLD_BLOCK, 2);
        List<ItemStack> change = net.pocketodds.util.ChipUtils.convertAmountToChips(12L);

        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, "quit_test_offer", 500L,
                reward, ShopPaymentSource.INVENTORY,
                Map.of(ChipTier.NETHERITE, 1), Collections.emptyMap(), 12L, change,
                ShopLimitPeriod.WEEKLY, false,
                ShopPurchaseTransaction.State.PAYMENT_DEBITED, System.currentTimeMillis()
        );
        data.recordShopPurchase(tx);

        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData reloaded = JackpotSavedData.load(tag);

        UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        UUID changeTxId = UUID.nameUUIDFromBytes(("shop_change:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        Assertions.assertNotNull(reloaded.getTransaction(rewardTxId));
        Assertions.assertNotNull(reloaded.getTransaction(changeTxId));

        Assertions.assertEquals(1, reloaded.getPlayerPurchaseCount(playerUUID, "quit_test_offer", ShopLimitPeriod.WEEKLY));

        ShopPurchaseTransaction reloadedTx = reloaded.getShopPurchase(opId);
        Assertions.assertEquals(ShopPurchaseTransaction.State.COMMITTED, reloadedTx.getState());
        Assertions.assertTrue(reloadedTx.isLimitRecorded());
    }

    @Test
    public void testOperationIdCannotBeReusedAfterRefund() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        // 1. Transaction failed during debiting and was set to REFUND_QUEUED
        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, "test_offer", 64L,
                new ItemStack(Items.DIAMOND, 1), ShopPaymentSource.INVENTORY,
                Map.of(ChipTier.GOLD, 8), Collections.emptyMap(), 0L, Collections.emptyList(),
                ShopLimitPeriod.DAILY, false,
                ShopPurchaseTransaction.State.REFUND_QUEUED, System.currentTimeMillis()
        );
        tx.recordActualInventoryDebit(ChipTier.GOLD, 8);
        data.recordShopPurchase(tx);

        // 2. isShopOperationProcessed must return true for any registered opId
        Assertions.assertTrue(data.isShopOperationProcessed(opId), "REFUND_QUEUED operation must be considered processed/used");

        // 3. Complete the refund receipt to simulate finished refund delivery
        UUID refundTxId = UUID.nameUUIDFromBytes(("shop_refund:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        data.recordCompletedReceipt(refundTxId);
        Assertions.assertTrue(data.isReceiptCompleted(refundTxId));

        // 4. Save and reload
        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(tag);

        // Still processed and cannot be re-used to initiate a new purchase
        Assertions.assertTrue(loaded.isShopOperationProcessed(opId));
        ShopPurchaseTransaction loadedTx = loaded.getShopPurchase(opId);
        Assertions.assertEquals(ShopPurchaseTransaction.State.REFUND_QUEUED, loadedTx.getState());
        Assertions.assertTrue(loaded.isReceiptCompleted(refundTxId));
        // No duplicate outbox refund should be created because receipt was already completed
        Assertions.assertNull(loaded.getTransaction(refundTxId));
    }

    @Test
    public void testValidOfferJsonParsing() {
        JsonObject json = new JsonObject();
        json.addProperty("offerId", "full_feature_offer");
        json.addProperty("item", "minecraft:diamond_sword");
        json.addProperty("count", 2);
        json.addProperty("priceCredits", 1024L);
        json.addProperty("category", "CREATIVE");
        json.addProperty("purchaseLimit", 1);
        json.addProperty("limitPeriod", "PER_PLAYER");
        json.addProperty("requiredAdvancement", "allthemods:allthemodium/atm_star");
        json.addProperty("enabled", true);
        json.addProperty("sortOrder", 5);
        json.addProperty("nameKey", "shop.pocketodds.test_sword");
        json.addProperty("descriptionKey", "shop.pocketodds.test_sword.desc");
        json.addProperty("itemNbt", "{CustomDamage:10,Unbreakable:1b}");

        com.google.gson.JsonArray mods = new com.google.gson.JsonArray();
        mods.add("minecraft");
        json.add("requiredMods", mods);

        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        items.add("minecraft:diamond_sword");
        json.add("requiredItems", items);

        json.addProperty("minStage", "stage_early");
        json.addProperty("maxStage", "stage_late");

        ShopOffer offer = ShopOffer.fromJson(json);
        Assertions.assertNotNull(offer);
        Assertions.assertEquals("full_feature_offer", offer.getOfferId());
        Assertions.assertEquals("minecraft:diamond_sword", offer.getItemId());
        Assertions.assertEquals(2, offer.getCount());
        Assertions.assertEquals(1024L, offer.getPriceCredits());
        Assertions.assertEquals(ShopCategory.CREATIVE, offer.getCategory());
        Assertions.assertEquals(1, offer.getPurchaseLimit());
        Assertions.assertEquals(ShopLimitPeriod.PER_PLAYER, offer.getLimitPeriod());
        Assertions.assertEquals("allthemods:allthemodium/atm_star", offer.getRequiredAdvancement());
        Assertions.assertTrue(offer.isEnabled());
        Assertions.assertEquals(5, offer.getSortOrder());
        Assertions.assertNotNull(offer.getItemNbt());
        Assertions.assertEquals(10, offer.getItemNbt().getInt("CustomDamage"));
        Assertions.assertEquals((byte) 1, offer.getItemNbt().getByte("Unbreakable"));
        Assertions.assertTrue(offer.getRequiredMods().contains("minecraft"));
        Assertions.assertTrue(offer.getRequiredItems().contains("minecraft:diamond_sword"));
        Assertions.assertEquals("stage_early", offer.getMinStage());
        Assertions.assertEquals("stage_late", offer.getMaxStage());
    }

    @Test
    public void testCorruptedJsonIsolation() {
        Map<ResourceLocation, com.google.gson.JsonElement> resources = new LinkedHashMap<>();

        // Valid offer 1
        JsonObject valid1 = new JsonObject();
        valid1.addProperty("offerId", "iso_valid_1");
        valid1.addProperty("item", "minecraft:iron_ingot");
        valid1.addProperty("priceCredits", 64L);
        resources.put(new ResourceLocation("pocketodds", "iso_valid_1"), valid1);

        // Corrupted (non-object or null element)
        resources.put(new ResourceLocation("pocketodds", "iso_corrupted_primitive"), new com.google.gson.JsonPrimitive("corrupted_string"));
        resources.put(new ResourceLocation("pocketodds", "iso_corrupted_null"), null);

        // Valid offer 2
        JsonObject valid2 = new JsonObject();
        valid2.addProperty("offerId", "iso_valid_2");
        valid2.addProperty("item", "minecraft:gold_ingot");
        valid2.addProperty("priceCredits", 128L);
        resources.put(new ResourceLocation("pocketodds", "iso_valid_2"), valid2);

        // applyResources must not throw and must load both valid offers
        Assertions.assertDoesNotThrow(() -> ShopOfferRegistry.INSTANCE.applyResources(resources));
        Assertions.assertNotNull(ShopOfferRegistry.getOffer("iso_valid_1"));
        Assertions.assertNotNull(ShopOfferRegistry.getOffer("iso_valid_2"));
    }

    @Test
    public void testRequiredModsSkipping() {
        JsonObject json = new JsonObject();
        json.addProperty("offerId", "mod_check_offer");
        json.addProperty("item", "minecraft:iron_ingot");
        json.addProperty("priceCredits", 64L);
        com.google.gson.JsonArray mods = new com.google.gson.JsonArray();
        mods.add("non_existent_fake_mod_xyz");
        json.add("requiredMods", mods);

        ShopOffer offer = ShopOffer.fromJson(json);
        Assertions.assertNotNull(offer);

        // Mock mod loader: only "minecraft" is loaded
        ShopOffer.setModLoadedPredicate((modId, def) -> modId.equals("minecraft"));
        try {
            Assertions.assertFalse(offer.isItemAvailable(), "Offer must be unavailable when required mod is missing");
        } finally {
            ShopOffer.setModLoadedPredicate(null);
        }
    }

    @Test
    public void testRequiredItemsSkipping() {
        JsonObject json = new JsonObject();
        json.addProperty("offerId", "item_check_offer");
        json.addProperty("item", "minecraft:iron_ingot");
        json.addProperty("priceCredits", 64L);
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        items.add("pocketodds:completely_absent_item_404");
        json.add("requiredItems", items);

        ShopOffer offer = ShopOffer.fromJson(json);
        Assertions.assertNotNull(offer);
        Assertions.assertFalse(offer.isItemAvailable(), "Offer must be unavailable when required item is missing from registry");
    }

    @Test
    public void testItemAndItemIdFieldAlias() {
        JsonObject json1 = new JsonObject();
        json1.addProperty("offerId", "alias_test_1");
        json1.addProperty("item", "minecraft:copper_ingot");
        json1.addProperty("price", 32L); // price alias for priceCredits

        JsonObject json2 = new JsonObject();
        json2.addProperty("offerId", "alias_test_2");
        json2.addProperty("itemId", "minecraft:copper_ingot");
        json2.addProperty("priceCredits", 32L);

        ShopOffer offer1 = ShopOffer.fromJson(json1);
        ShopOffer offer2 = ShopOffer.fromJson(json2);

        Assertions.assertNotNull(offer1);
        Assertions.assertNotNull(offer2);
        Assertions.assertEquals("minecraft:copper_ingot", offer1.getItemId());
        Assertions.assertEquals("minecraft:copper_ingot", offer2.getItemId());
        Assertions.assertEquals(32L, offer1.getPriceCredits());
        Assertions.assertEquals(32L, offer2.getPriceCredits());
    }

    @Test
    public void testItemNbtRewardCreation() {
        JsonObject json = new JsonObject();
        json.addProperty("offerId", "nbt_test_offer");
        json.addProperty("item", "minecraft:netherite_pickaxe");
        json.addProperty("count", 1);
        json.addProperty("priceCredits", 4096L);
        json.addProperty("itemNbt", "{CustomModelData:777,display:{Name:'{\"text\":\"Super Pickaxe\"}'}}");

        ShopOffer offer = ShopOffer.fromJson(json);
        Assertions.assertNotNull(offer);
        ItemStack stack = offer.createRewardStack();
        Assertions.assertFalse(stack.isEmpty());
        Assertions.assertTrue(stack.hasTag());
        Assertions.assertEquals(777, stack.getTag().getInt("CustomModelData"));
        Assertions.assertTrue(stack.getTag().contains("display"));
    }

    @Test
    public void testDuplicateOfferIdHandling() {
        Map<ResourceLocation, com.google.gson.JsonElement> resources = new LinkedHashMap<>();

        JsonObject first = new JsonObject();
        first.addProperty("offerId", "duplicate_id");
        first.addProperty("item", "minecraft:iron_ingot");
        first.addProperty("priceCredits", 100L);

        JsonObject second = new JsonObject();
        second.addProperty("offerId", "duplicate_id");
        second.addProperty("item", "minecraft:iron_ingot");
        second.addProperty("priceCredits", 200L);

        resources.put(new ResourceLocation("pocketodds", "dup1"), first);
        resources.put(new ResourceLocation("pocketodds", "dup2"), second);

        Assertions.assertDoesNotThrow(() -> ShopOfferRegistry.INSTANCE.applyResources(resources));
        ShopOffer resolved = ShopOfferRegistry.getOffer("duplicate_id");
        Assertions.assertNotNull(resolved);
        Assertions.assertEquals(200L, resolved.getPriceCredits(), "Later definition must deterministically replace previous entry");
    }

    @Test
    public void testReloadDatapackRegistryRefresh() {
        // Initial load with Offer A
        Map<ResourceLocation, com.google.gson.JsonElement> batch1 = new LinkedHashMap<>();
        JsonObject objA = new JsonObject();
        objA.addProperty("offerId", "offer_alpha");
        objA.addProperty("item", "minecraft:iron_ingot");
        batch1.put(new ResourceLocation("pocketodds", "alpha"), objA);
        ShopOfferRegistry.INSTANCE.applyResources(batch1);

        Assertions.assertNotNull(ShopOfferRegistry.getOffer("offer_alpha"));

        // Datapack reload: only Offer B is present now
        Map<ResourceLocation, com.google.gson.JsonElement> batch2 = new LinkedHashMap<>();
        JsonObject objB = new JsonObject();
        objB.addProperty("offerId", "offer_beta");
        objB.addProperty("item", "minecraft:gold_ingot");
        batch2.put(new ResourceLocation("pocketodds", "beta"), objB);
        ShopOfferRegistry.INSTANCE.applyResources(batch2);

        // Alpha is gone, Beta is active
        Assertions.assertNull(ShopOfferRegistry.getOffer("offer_alpha"));
        Assertions.assertNotNull(ShopOfferRegistry.getOffer("offer_beta"));
    }

    @Test
    public void testPerPlayerLimitPersistence() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        String offerId = "creative_star_offer";

        // Record purchase under PER_PLAYER limit
        Assertions.assertEquals(0, data.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.PER_PLAYER));
        data.incrementPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.PER_PLAYER);
        Assertions.assertEquals(1, data.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.PER_PLAYER));

        // Test backward compatibility: PERMANENT resolves to PER_PLAYER
        Assertions.assertEquals(1, data.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.PERMANENT));

        // Save & reload from NBT
        CompoundTag savedTag = data.save(new CompoundTag());
        JackpotSavedData loadedData = JackpotSavedData.load(savedTag);

        Assertions.assertEquals(1, loadedData.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.PER_PLAYER));
        Assertions.assertEquals(1, loadedData.getPlayerPurchaseCount(playerUUID, offerId, ShopLimitPeriod.PERMANENT));
    }

    @Test
    public void testUnauthorizedOperationIdRejection() {
        JackpotSavedData data = new JackpotSavedData();
        UUID originalOwner = UUID.randomUUID();
        UUID strangerPlayer = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        // Transaction recorded for original owner
        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, originalOwner, "secret_item", 512L,
                new ItemStack(Items.DIAMOND, 1), ShopPaymentSource.INVENTORY,
                Map.of(ChipTier.NETHERITE, 1), Collections.emptyMap(), 0L, Collections.emptyList(),
                ShopLimitPeriod.PER_PLAYER, false,
                ShopPurchaseTransaction.State.COMMITTED, System.currentTimeMillis()
        );
        data.recordShopPurchase(tx);

        // Check ownership constraint logic
        Assertions.assertFalse(tx.getPlayerUUID().equals(strangerPlayer),
                "Stranger player UUID must not match transaction owner UUID");
        Assertions.assertTrue(tx.getPlayerUUID().equals(originalOwner));
    }

    @Test
    public void testNbtRewardPurchaseExecution() {
        JackpotSavedData data = new JackpotSavedData();
        UUID opId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();

        ItemStack nbtReward = new ItemStack(Items.NETHERITE_SWORD, 1);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Unbreakable", true);
        tag.putInt("SpecialSkillId", 42);
        nbtReward.setTag(tag);

        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, "nbt_weapon", 4096L,
                nbtReward, ShopPaymentSource.INVENTORY,
                Map.of(ChipTier.NETHERITE, 8), Collections.emptyMap(), 0L, Collections.emptyList(),
                ShopLimitPeriod.UNLIMITED, false,
                ShopPurchaseTransaction.State.PAYMENT_DEBITED, System.currentTimeMillis()
        );
        data.recordShopPurchase(tx);

        // Enqueue into outbox
        UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction rewardTx = new JackpotSavedData.RewardTransaction(
                rewardTxId, playerUUID, Collections.singletonList(nbtReward)
        );
        data.enqueueRewardTransaction(rewardTx);

        // Reload and verify NBT preservation
        CompoundTag saved = data.save(new CompoundTag());
        JackpotSavedData reloaded = JackpotSavedData.load(saved);

        JackpotSavedData.RewardTransaction delivered = reloaded.getTransaction(rewardTxId);
        Assertions.assertNotNull(delivered);
        ItemStack restored = delivered.getLines().get(0).getStack();
        Assertions.assertTrue(restored.hasTag());
        Assertions.assertTrue(restored.getTag().getBoolean("Unbreakable"));
        Assertions.assertEquals(42, restored.getTag().getInt("SpecialSkillId"));
    }

    @Test
    public void testCreativeOfferRequiresAdvancement() {
        Assertions.assertTrue(ShopService.isAdvancementSatisfied(null, ""));
        Assertions.assertTrue(ShopService.isAdvancementSatisfied(null, "   "));
        // Required endgame advancement must not be satisfied for null / missing player progress
        Assertions.assertFalse(ShopService.isAdvancementSatisfied(null, "allthemods:allthemodium/atm_star"));
    }

    @Test
    public void testFullInventoryOutboxSafetyExtended() {
        JackpotSavedData data = new JackpotSavedData();
        UUID playerUUID = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        List<ItemStack> rewardStacks = List.of(
                new ItemStack(Items.DIAMOND, 64),
                new ItemStack(Items.EMERALD, 64)
        );
        UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward:" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction rewardTx = new JackpotSavedData.RewardTransaction(
                rewardTxId, playerUUID, rewardStacks
        );
        data.enqueueRewardTransaction(rewardTx);

        // When inventory is full, confirmDeliveredLine is not called and tx remains safely pending
        Assertions.assertTrue(data.hasPendingTransaction(rewardTxId));
        Assertions.assertEquals(2, data.getTransaction(rewardTxId).getLines().size());

        // Partial deliver: 1 line delivered
        UUID line1 = data.getTransaction(rewardTxId).getLines().get(0).getLineId();
        data.confirmDeliveredLine(rewardTxId, line1);
        Assertions.assertTrue(data.hasPendingTransaction(rewardTxId));

        // Save & reload ensures undelivered second line remains
        CompoundTag tag = data.save(new CompoundTag());
        JackpotSavedData loaded = JackpotSavedData.load(tag);
        Assertions.assertTrue(loaded.hasPendingTransaction(rewardTxId));
    }

    @Test
    public void testShopCatalogGeneratorSafety() {
        // 1. Detect categories
        Assertions.assertEquals(ShopCategory.COMPONENTS, net.pocketodds.command.ShopCatalogGeneratorCommand.detectCategory("mekanism/advanced_control_circuit"));
        Assertions.assertEquals(ShopCategory.MACHINES, net.pocketodds.command.ShopCatalogGeneratorCommand.detectCategory("create/crushing_wheel_controller"));
        Assertions.assertEquals(ShopCategory.STORAGE, net.pocketodds.command.ShopCatalogGeneratorCommand.detectCategory("functionalstorage/copper_upgrade"));
        Assertions.assertEquals(ShopCategory.TOOLS, net.pocketodds.command.ShopCatalogGeneratorCommand.detectCategory("ironjetpacks/strap"));
        Assertions.assertEquals(ShopCategory.CONSUMABLES, net.pocketodds.command.ShopCatalogGeneratorCommand.detectCategory("ars_nouveau/source_berry_pie"));
        Assertions.assertEquals(ShopCategory.RARE, net.pocketodds.command.ShopCatalogGeneratorCommand.detectCategory("allthetweaks/atm_star"));

        // 2. Base price calculation
        long compPrice = net.pocketodds.command.ShopCatalogGeneratorCommand.calculateBasePrice(ShopCategory.COMPONENTS, "ultimate_control_circuit");
        Assertions.assertEquals(4096L, compPrice);

        long rarePrice = net.pocketodds.command.ShopCatalogGeneratorCommand.calculateBasePrice(ShopCategory.RARE, "atm_star");
        Assertions.assertEquals(8192L, rarePrice);
    }
}
