package net.pocketodds.service;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.data.PouchBalance;
import net.pocketodds.gambling.core.RewardTransactionService;
import net.pocketodds.gui.casino.BetFundingSource;
import net.pocketodds.item.ChipTier;
import net.pocketodds.registration.ModItems;
import net.pocketodds.util.ChipUtils;
import net.pocketodds.util.InventoryUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CoinPouchService {
    // Lock to prevent two players or GUIs from modifying the same pouch UUID simultaneously
    private static final Map<UUID, UUID> activePouchLocks = new ConcurrentHashMap<>();

    public static UUID getOrCreatePouchUUID(ItemStack pouch) {
        if (pouch == null || pouch.isEmpty()) {
            return UUID.randomUUID();
        }
        CompoundTag tag = pouch.getOrCreateTag();
        if (!tag.hasUUID("PouchUUID")) {
            UUID id = UUID.randomUUID();
            tag.putUUID("PouchUUID", id);
            return id;
        }
        return tag.getUUID("PouchUUID");
    }

    public static UUID getPouchUUID(ItemStack pouch) {
        if (pouch == null || pouch.isEmpty()) {
            return null;
        }
        CompoundTag tag = pouch.getTag();
        if (tag != null && tag.hasUUID("PouchUUID")) {
            return tag.getUUID("PouchUUID");
        }
        return null;
    }

    public static boolean acquirePouchLock(UUID pouchUUID, UUID playerUUID) {
        if (pouchUUID == null || playerUUID == null) return false;
        UUID existing = activePouchLocks.putIfAbsent(pouchUUID, playerUUID);
        return existing == null || existing.equals(playerUUID);
    }

    public static void releasePouchLock(UUID pouchUUID, UUID playerUUID) {
        if (pouchUUID == null || playerUUID == null) return;
        activePouchLocks.remove(pouchUUID, playerUUID);
    }

    public static boolean isPouchLockedByOther(UUID pouchUUID, UUID playerUUID) {
        if (pouchUUID == null) return false;
        UUID current = activePouchLocks.get(pouchUUID);
        return current != null && !current.equals(playerUUID);
    }

    public static void syncStackNbt(ItemStack pouch, PouchBalance balance) {
        if (pouch == null || pouch.isEmpty() || balance == null) return;
        setChipCount(pouch, ChipTier.COPPER, balance.getCount(ChipTier.COPPER));
        setChipCount(pouch, ChipTier.GOLD, balance.getCount(ChipTier.GOLD));
        setChipCount(pouch, ChipTier.DIAMOND, balance.getCount(ChipTier.DIAMOND));
        setChipCount(pouch, ChipTier.NETHERITE, balance.getCount(ChipTier.NETHERITE));
    }

    public static long getChipCountFromNbt(ItemStack pouch, ChipTier tier) {
        if (pouch == null || pouch.isEmpty() || tier == null) return 0L;
        CompoundTag tag = pouch.getTag();
        if (tag == null) return 0L;
        String key = getTierTagKey(tier);
        return tag.contains(key, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? Math.max(0L, tag.getLong(key)) : 0L;
    }

    public static long getChipCount(ItemStack pouch, ChipTier tier) {
        return getChipCountFromNbt(pouch, tier);
    }

    public static void setChipCount(ItemStack pouch, ChipTier tier, long count) {
        if (pouch == null || pouch.isEmpty() || tier == null) return;
        CompoundTag tag = pouch.getOrCreateTag();
        tag.putLong(getTierTagKey(tier), Math.max(0L, Math.min(net.pocketodds.data.PouchBalance.MAX_CHIPS_PER_TIER, count)));
    }

    public static void addChips(ItemStack pouch, ChipTier tier, int count) {
        addChips(pouch, tier, (long) count);
    }

    public static void addChips(ItemStack pouch, ChipTier tier, long count) {
        if (pouch == null || tier == null || count <= 0) return;
        long current = getChipCount(pouch, tier);
        long newCount = (net.pocketodds.data.PouchBalance.MAX_CHIPS_PER_TIER - current < count)
                ? net.pocketodds.data.PouchBalance.MAX_CHIPS_PER_TIER
                : current + count;
        setChipCount(pouch, tier, newCount);
    }

    public static int removeChips(ItemStack pouch, ChipTier tier, int count) {
        return (int) removeChips(pouch, tier, (long) count);
    }

    public static long removeChips(ItemStack pouch, ChipTier tier, long count) {
        if (pouch == null || tier == null || count <= 0) return 0L;
        long current = getChipCount(pouch, tier);
        long take = Math.min(count, current);
        setChipCount(pouch, tier, current - take);
        return take;
    }

    public static boolean debitCreditsFromPouchOnly(ItemStack pouch, long requiredCredits) {
        if (pouch == null || pouch.isEmpty() || requiredCredits <= 0) return false;
        long pouchCredits = getTotalCredits(pouch);
        if (pouchCredits < requiredCredits) return false;
        deductCreditsFromPouch(pouch, requiredCredits);
        return true;
    }

    public static long getTotalCredits(ItemStack pouch) {
        if (pouch == null || pouch.isEmpty()) return 0L;
        long total = 0L;
        for (ChipTier tier : ChipTier.values()) {
            long count = getChipCount(pouch, tier);
            long product = count * tier.getBaseValue();
            if (Long.MAX_VALUE - total < product) {
                return Long.MAX_VALUE;
            }
            total += product;
        }
        return total;
    }

    public static String getTierTagKey(ChipTier tier) {
        return switch (tier) {
            case COPPER -> "CopperChips";
            case GOLD -> "GoldChips";
            case DIAMOND -> "DiamondChips";
            case NETHERITE -> "NetheriteChips";
        };
    }

    public static int depositChips(ServerPlayer player, ItemStack pouch, ChipTier tier, int requestedCount) {
        if (player == null || pouch == null || pouch.isEmpty() || tier == null || requestedCount <= 0) {
            return 0;
        }
        int available = InventoryUtils.countChips(player, tier);
        int take = Math.min(requestedCount, available);
        if (take <= 0) return 0;

        UUID pouchUUID = getOrCreatePouchUUID(pouch);
        JackpotSavedData data = JackpotSavedData.get(player.serverLevel());

        net.pocketodds.gambling.core.PouchDepositEscrow escrow = data.preparePouchDeposit(player.getUUID(), pouchUUID, tier, take, available);
        boolean removed = false;
        try {
            removed = InventoryUtils.removeChips(player, tier, take);
        } catch (Exception e) {
            data.abortPouchDeposit(escrow.getDepositId());
            throw e;
        }
        if (removed) {
            boolean debited = data.markDepositDebited(escrow.getDepositId());
            if (!debited) {
                // Transition to DEBITED failed; return removed chips safely to player
                InventoryUtils.giveItemSafely(player, new ItemStack(tier.getItem(), take));
                data.abortPouchDeposit(escrow.getDepositId());
                return 0;
            }
            boolean committed = data.commitPouchDeposit(escrow.getDepositId());
            if (!committed) {
                // Commit failed; return removed chips safely to player
                InventoryUtils.giveItemSafely(player, new ItemStack(tier.getItem(), take));
                data.abortPouchDeposit(escrow.getDepositId());
                return 0;
            }
            PouchBalance balance = data.getPouchBalance(pouchUUID);
            syncStackNbt(pouch, balance);
            return take;
        } else {
            data.abortPouchDeposit(escrow.getDepositId());
            return 0;
        }
    }

    public static int depositAllChips(ServerPlayer player, ItemStack pouch) {
        if (player == null || pouch == null || pouch.isEmpty()) return 0;
        int totalDeposited = 0;
        for (ChipTier tier : ChipTier.values()) {
            int available = InventoryUtils.countChips(player, tier);
            if (available > 0) {
                totalDeposited += depositChips(player, pouch, tier, available);
            }
        }
        return totalDeposited;
    }

    public static int withdrawChips(ServerPlayer player, ItemStack pouch, ChipTier tier, int requestedCount) {
        if (player == null || pouch == null || pouch.isEmpty() || tier == null || requestedCount <= 0) {
            return 0;
        }
        UUID pouchUUID = getOrCreatePouchUUID(pouch);
        JackpotSavedData data = JackpotSavedData.get(player.serverLevel());

        JackpotSavedData.RewardTransaction tx = data.withdrawFromPouch(pouchUUID, player.getUUID(), tier, requestedCount, pouch);
        if (tx != null && !tx.isEmpty()) {
            PouchBalance balance = data.getPouchBalance(pouchUUID);
            syncStackNbt(pouch, balance);
            int takenCount = tx.getItems().stream().mapToInt(ItemStack::getCount).sum();
            RewardTransactionService.deliverTransaction(tx.getTransactionId(), player, pouch, data, InventoryUtils::giveOrDrop);
            return takenCount;
        }
        return 0;
    }

    public static int withdrawAllChips(ServerPlayer player, ItemStack pouch) {
        if (player == null || pouch == null || pouch.isEmpty()) return 0;
        int totalWithdrawn = 0;
        for (ChipTier tier : ChipTier.values()) {
            totalWithdrawn += withdrawChips(player, pouch, tier, Integer.MAX_VALUE);
        }
        return totalWithdrawn;
    }

    public static long getInventoryChipCredits(ServerPlayer player) {
        if (player == null) return 0L;
        long total = 0L;
        for (ChipTier tier : ChipTier.values()) {
            int count = InventoryUtils.countChips(player, tier);
            total += (long) count * tier.getBaseValue();
        }
        return total;
    }

    public static ItemStack findFirstPouch(ServerPlayer player) {
        if (player == null) return ItemStack.EMPTY;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == ModItems.COIN_POUCH.get()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Atomically debits credits from Pouch and/or Inventory according to funding source.
     * Overload for backward compatibility / tests without JackpotSavedData.
     */
    public static synchronized boolean debitCredits(ServerPlayer player, ItemStack pouch, long requiredCredits, BetFundingSource source) {
        if (player != null && player.getServer() != null) {
            return debitCredits(player, pouch, requiredCredits, source, JackpotSavedData.get(player.serverLevel()));
        }
        return debitCreditsLocal(player, pouch, requiredCredits, source);
    }

    /**
     * Atomically debits credits from Pouch and/or Inventory according to funding source using authoritative JackpotSavedData.
     * If total available is less than required, no balance is changed and returns false.
     */
    public static synchronized boolean debitCredits(ServerPlayer player, ItemStack pouch, long requiredCredits, BetFundingSource source, JackpotSavedData jackpotData) {
        if (player == null || requiredCredits <= 0) return false;
        if (jackpotData == null) {
            return debitCreditsLocal(player, pouch, requiredCredits, source);
        }

        UUID pouchUUID = (pouch != null && !pouch.isEmpty()) ? getOrCreatePouchUUID(pouch) : null;
        PouchBalance balance = pouchUUID != null ? jackpotData.getOrCreatePouchBalance(pouchUUID, pouch) : null;

        long pouchCredits = balance != null ? balance.getTotalCredits() : 0L;
        long invCredits = getInventoryChipCredits(player);

        switch (source) {
            case POUCH_ONLY: {
                if (balance == null || pouchCredits < requiredCredits) return false;
                boolean ok = jackpotData.debitCreditsFromPouch(pouchUUID, requiredCredits);
                if (ok) {
                    syncStackNbt(pouch, jackpotData.getPouchBalance(pouchUUID));
                }
                return ok;
            }
            case INVENTORY: {
                if (invCredits < requiredCredits) return false;
                return deductCreditsFromInventory(player, requiredCredits);
            }
            case POUCH_THEN_INVENTORY: {
                if (pouchCredits + invCredits < requiredCredits) return false;
                long fromPouch = Math.min(pouchCredits, requiredCredits);
                long fromInv = requiredCredits - fromPouch;
                if (fromPouch > 0 && balance != null) {
                    boolean ok = jackpotData.debitCreditsFromPouch(pouchUUID, fromPouch);
                    if (!ok) return false;
                    syncStackNbt(pouch, jackpotData.getPouchBalance(pouchUUID));
                }
                if (fromInv > 0) {
                    boolean ok = deductCreditsFromInventory(player, fromInv);
                    if (!ok) {
                        // Rollback pouch deduction if inventory deduction failed
                        if (balance != null) {
                            jackpotData.addCreditsToPouch(pouchUUID, fromPouch);
                            syncStackNbt(pouch, jackpotData.getPouchBalance(pouchUUID));
                        }
                        return false;
                    }
                }
                return true;
            }
            case INVENTORY_THEN_POUCH: {
                if (invCredits + pouchCredits < requiredCredits) return false;
                long fromInv = Math.min(invCredits, requiredCredits);
                long fromPouch = requiredCredits - fromInv;
                if (fromInv > 0) {
                    boolean ok = deductCreditsFromInventory(player, fromInv);
                    if (!ok) return false;
                }
                if (fromPouch > 0 && balance != null) {
                    boolean ok = jackpotData.debitCreditsFromPouch(pouchUUID, fromPouch);
                    if (!ok) {
                        // Rollback inventory deduction if pouch debit failed
                        List<ItemStack> refund = ChipUtils.convertAmountToChips(fromInv);
                        for (ItemStack s : refund) {
                            InventoryUtils.giveOrDrop(player, s);
                        }
                        return false;
                    }
                    syncStackNbt(pouch, jackpotData.getPouchBalance(pouchUUID));
                }
                return true;
            }
            default:
                return false;
        }
    }

    private static synchronized boolean debitCreditsLocal(ServerPlayer player, ItemStack pouch, long requiredCredits, BetFundingSource source) {
        if (player == null || requiredCredits <= 0) return false;

        long pouchCredits = (pouch != null && !pouch.isEmpty()) ? getTotalCredits(pouch) : 0L;
        long invCredits = getInventoryChipCredits(player);

        switch (source) {
            case POUCH_ONLY: {
                if (pouchCredits < requiredCredits) return false;
                deductCreditsFromPouch(pouch, requiredCredits);
                return true;
            }
            case INVENTORY: {
                if (invCredits < requiredCredits) return false;
                return deductCreditsFromInventory(player, requiredCredits);
            }
            case POUCH_THEN_INVENTORY: {
                if (pouchCredits + invCredits < requiredCredits) return false;
                long fromPouch = Math.min(pouchCredits, requiredCredits);
                long fromInv = requiredCredits - fromPouch;
                if (fromPouch > 0) {
                    deductCreditsFromPouch(pouch, fromPouch);
                }
                if (fromInv > 0) {
                    boolean ok = deductCreditsFromInventory(player, fromInv);
                    if (!ok) {
                        addCreditsToPouch(pouch, fromPouch);
                        return false;
                    }
                }
                return true;
            }
            case INVENTORY_THEN_POUCH: {
                if (invCredits + pouchCredits < requiredCredits) return false;
                long fromInv = Math.min(invCredits, requiredCredits);
                long fromPouch = requiredCredits - fromInv;
                if (fromInv > 0) {
                    boolean ok = deductCreditsFromInventory(player, fromInv);
                    if (!ok) return false;
                }
                if (fromPouch > 0) {
                    deductCreditsFromPouch(pouch, fromPouch);
                }
                return true;
            }
            default:
                return false;
        }
    }

    public static void deductCreditsFromPouchBalance(PouchBalance balance, long credits) {
        if (balance == null) return;
        long current = balance.getTotalCredits();
        if (credits >= current) {
            for (ChipTier tier : ChipTier.values()) {
                balance.setCount(tier, 0);
            }
            return;
        }
        long remaining = current - credits;
        reconstructBalanceCredits(balance, remaining);
    }

    public static void addCreditsToPouchBalance(PouchBalance balance, long credits) {
        if (balance == null || credits <= 0) return;
        long current = balance.getTotalCredits();
        long newTotal = (Long.MAX_VALUE - current < credits) ? Long.MAX_VALUE : current + credits;
        reconstructBalanceCredits(balance, newTotal);
    }

    public static void reconstructBalanceCredits(PouchBalance balance, long totalCredits) {
        if (balance == null) return;
        long netherite = totalCredits / ChipTier.NETHERITE.getBaseValue();
        long rem1 = totalCredits % ChipTier.NETHERITE.getBaseValue();

        long diamond = rem1 / ChipTier.DIAMOND.getBaseValue();
        long rem2 = rem1 % ChipTier.DIAMOND.getBaseValue();

        long gold = rem2 / ChipTier.GOLD.getBaseValue();
        long copper = rem2 % ChipTier.GOLD.getBaseValue();

        balance.setCount(ChipTier.NETHERITE, netherite);
        balance.setCount(ChipTier.DIAMOND, diamond);
        balance.setCount(ChipTier.GOLD, gold);
        balance.setCount(ChipTier.COPPER, copper);
    }

    public static void deductCreditsFromPouch(ItemStack pouch, long credits) {
        long current = getTotalCredits(pouch);
        if (credits >= current) {
            for (ChipTier tier : ChipTier.values()) {
                setChipCount(pouch, tier, 0L);
            }
            return;
        }
        long remaining = current - credits;
        reconstructPouchCredits(pouch, remaining);
    }

    public static void addCreditsToPouch(ItemStack pouch, long credits) {
        if (credits <= 0) return;
        long current = getTotalCredits(pouch);
        long newTotal = (Long.MAX_VALUE - current < credits) ? Long.MAX_VALUE : current + credits;
        reconstructPouchCredits(pouch, newTotal);
    }

    public static void reconstructPouchCredits(ItemStack pouch, long totalCredits) {
        if (pouch == null || pouch.isEmpty()) return;
        long netherite = totalCredits / ChipTier.NETHERITE.getBaseValue();
        long rem1 = totalCredits % ChipTier.NETHERITE.getBaseValue();

        long diamond = rem1 / ChipTier.DIAMOND.getBaseValue();
        long rem2 = rem1 % ChipTier.DIAMOND.getBaseValue();

        long gold = rem2 / ChipTier.GOLD.getBaseValue();
        long copper = rem2 % ChipTier.GOLD.getBaseValue();

        setChipCount(pouch, ChipTier.NETHERITE, netherite);
        setChipCount(pouch, ChipTier.DIAMOND, diamond);
        setChipCount(pouch, ChipTier.GOLD, gold);
        setChipCount(pouch, ChipTier.COPPER, copper);
    }

    private static boolean deductCreditsFromInventory(ServerPlayer player, long requiredCredits) {
        long available = getInventoryChipCredits(player);
        if (available < requiredCredits) return false;

        Map<ChipTier, Integer> toRemove = new HashMap<>();
        long remaining = requiredCredits;

        ChipTier[] tiersDescending = new ChipTier[]{
                ChipTier.NETHERITE, ChipTier.DIAMOND, ChipTier.GOLD, ChipTier.COPPER
        };

        for (ChipTier tier : tiersDescending) {
            int countInInv = InventoryUtils.countChips(player, tier);
            int need = (int) (remaining / tier.getBaseValue());
            int take = Math.min(need, countInInv);
            if (take > 0) {
                toRemove.put(tier, take);
                remaining -= (long) take * tier.getBaseValue();
            }
        }

        if (remaining > 0) {
            for (int i = tiersDescending.length - 1; i >= 0; i--) {
                ChipTier tier = tiersDescending[i];
                int inInv = InventoryUtils.countChips(player, tier) - toRemove.getOrDefault(tier, 0);
                if (inInv > 0 && tier.getBaseValue() >= remaining) {
                    toRemove.put(tier, toRemove.getOrDefault(tier, 0) + 1);
                    long change = tier.getBaseValue() - remaining;
                    remaining = 0;
                    if (change > 0) {
                        List<ItemStack> changeStacks = ChipUtils.convertAmountToChips(change);
                        for (ItemStack s : changeStacks) {
                            InventoryUtils.giveOrDrop(player, s);
                        }
                    }
                    break;
                }
            }
        }

        if (remaining > 0) {
            return false;
        }

        for (Map.Entry<ChipTier, Integer> e : toRemove.entrySet()) {
            InventoryUtils.removeChips(player, e.getKey(), e.getValue());
        }
        return true;
    }
}