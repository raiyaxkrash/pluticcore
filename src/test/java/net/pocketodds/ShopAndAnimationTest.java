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
import net.pocketodds.gambling.slot.SlotSymbol;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
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
            if (!forgeReg.containsKey(new ResourceLocation("pocketodds", "prize_token"))) {
                forgeReg.register(new ResourceLocation("pocketodds", "prize_token"), new PrizeTokenItem(new Item.Properties()));
            }
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
                        Assertions.assertTrue(offer.getPrice() > 0, "Price must be positive in " + file.getFileName());
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
        Assertions.assertEquals(24, count, "Must have exactly 24 datapack shop offers");
    }

    @Test
    public void testShopOfferRegistryWithAvailableItems() {
        ShopOffer diamondOffer = new ShopOffer(
                "test_diamond",
                "minecraft:diamond",
                3,
                10,
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
    }

    @Test
    public void testTwoPhaseCommitAndDeterministicOutbox() {
        JackpotSavedData data = new JackpotSavedData();
        UUID opId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();
        ItemStack reward = new ItemStack(Items.DIAMOND, 2);

        // Phase 1: PREPARED
        ShopPurchaseTransaction tx = new ShopPurchaseTransaction(
                opId, playerUUID, "test_diamond", 10,
                reward, ShopPurchaseTransaction.State.PREPARED, System.currentTimeMillis()
        );
        data.recordShopPurchase(tx);
        Assertions.assertFalse(data.isShopOperationProcessed(opId));

        // Phase 2: TOKENS_DEBITED
        data.updateShopPurchaseState(opId, ShopPurchaseTransaction.State.TOKENS_DEBITED);
        Assertions.assertTrue(data.isShopOperationProcessed(opId));

        // Outbox enqueue with deterministic UUID
        UUID rewardTxId = UUID.nameUUIDFromBytes(("shop_reward_" + opId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction rewardTx = new JackpotSavedData.RewardTransaction(
                rewardTxId, playerUUID, Collections.singletonList(reward)
        );
        data.enqueueRewardTransaction(rewardTx);

        // Phase 3: COMMITTED
        data.updateShopPurchaseState(opId, ShopPurchaseTransaction.State.COMMITTED);
        Assertions.assertTrue(data.isShopOperationProcessed(opId));

        // Verify outbox transaction exists and contains items
        JackpotSavedData.RewardTransaction outboxTx = data.getTransaction(rewardTxId);
        Assertions.assertNotNull(outboxTx);
        Assertions.assertEquals(1, outboxTx.getLines().size());
        Assertions.assertEquals(Items.DIAMOND, outboxTx.getLines().get(0).getStack().getItem());
        Assertions.assertEquals(2, outboxTx.getLines().get(0).getStack().getCount());
    }

    @Test
    public void testShopPurchaseRecoveryOnRestart() {
        JackpotSavedData original = new JackpotSavedData();
        UUID debitedOpId = UUID.randomUUID();
        UUID playerUUID = UUID.randomUUID();
        ItemStack reward = new ItemStack(Items.GOLD_INGOT, 4);

        // A transaction that was debited before crash
        ShopPurchaseTransaction debitedTx = new ShopPurchaseTransaction(
                debitedOpId, playerUUID, "test_gold", 5,
                reward, ShopPurchaseTransaction.State.TOKENS_DEBITED, System.currentTimeMillis()
        );
        original.recordShopPurchase(debitedTx);

        // A transaction that was only prepared before crash (not debited)
        UUID preparedOpId = UUID.randomUUID();
        ShopPurchaseTransaction prepTx = new ShopPurchaseTransaction(
                preparedOpId, playerUUID, "test_gold", 5,
                reward, ShopPurchaseTransaction.State.PREPARED, System.currentTimeMillis()
        );
        original.recordShopPurchase(prepTx);

        // Save to NBT
        CompoundTag tag = original.save(new CompoundTag());

        // Load into new instance (simulating restart recovery)
        JackpotSavedData recovered = JackpotSavedData.load(tag);

        // Verify debited transaction was recovered and queued in outbox
        UUID expectedRewardTxId = UUID.nameUUIDFromBytes(("shop_reward_" + debitedOpId.toString()).getBytes(StandardCharsets.UTF_8));
        JackpotSavedData.RewardTransaction outboxTx = recovered.getTransaction(expectedRewardTxId);
        Assertions.assertNotNull(outboxTx, "Debited purchase must be recovered into outbox on restart");
        Assertions.assertEquals(Items.GOLD_INGOT, outboxTx.getLines().get(0).getStack().getItem());
        Assertions.assertEquals(4, outboxTx.getLines().get(0).getStack().getCount());

        // Verify prepared transaction was safely cancelled
        Assertions.assertFalse(recovered.isShopOperationProcessed(preparedOpId), "PREPARED transaction must be cancelled without awarding items");
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

        // Tick several times
        renderer.tick();
        renderer.tick();
        Assertions.assertTrue(renderer.isSpinning());

        // Skip animation
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
        // BuyShopOfferC2SPacket
        UUID opId = UUID.randomUUID();
        BuyShopOfferC2SPacket buyPacket = new BuyShopOfferC2SPacket(5, "mek_basic_circuit", opId);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buyPacket.toBytes(buf);

        BuyShopOfferC2SPacket decodedBuy = new BuyShopOfferC2SPacket(buf);
        Assertions.assertEquals(5, decodedBuy.getContainerId());
        Assertions.assertEquals("mek_basic_circuit", decodedBuy.getOfferId());
        Assertions.assertEquals(opId, decodedBuy.getOperationId());

        // SyncShopCatalogS2CPacket
        SyncShopCatalogS2CPacket.ClientShopEntry entry = new SyncShopCatalogS2CPacket.ClientShopEntry(
                "mek_basic_circuit", new ItemStack(Items.IRON_INGOT, 2), 5,
                ShopCategory.COMPONENTS.ordinal(), ShopLimitPeriod.WEEKLY.ordinal(), 10, true,
                "shop.pocketodds.mek_basic_circuit", "desc"
        );
        SyncShopCatalogS2CPacket syncPacket = new SyncShopCatalogS2CPacket(42, List.of(entry));
        FriendlyByteBuf syncBuf = new FriendlyByteBuf(Unpooled.buffer());
        syncPacket.toBytes(syncBuf);

        SyncShopCatalogS2CPacket decodedSync = new SyncShopCatalogS2CPacket(syncBuf);
        Assertions.assertEquals(42, decodedSync.getPlayerTokens());
        Assertions.assertEquals(1, decodedSync.getEntries().size());
        SyncShopCatalogS2CPacket.ClientShopEntry decodedEntry = decodedSync.getEntries().get(0);
        Assertions.assertEquals("mek_basic_circuit", decodedEntry.getOfferId());
        Assertions.assertEquals(5, decodedEntry.getPrice());
        Assertions.assertEquals(10, decodedEntry.getRemainingLimit());
        Assertions.assertTrue(decodedEntry.isAvailable());
    }
}
