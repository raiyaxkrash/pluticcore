package net.pocketodds.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.item.ChipTier;

import java.util.ArrayList;
import java.util.List;

public class ChipUtils {

    /**
     * Absolute safety ceiling on the number of ItemStacks that can be materialized
     * in memory during a single conversion/split to protect against OutOfMemoryError
     * and JVM freeze when astronomical numbers (e.g. Long.MAX_VALUE) are provided.
     * 50,000 stacks of Netherite chips equals 3,200,000 chips (1.638 billion credits),
     * which exceeds the maximum configurable server jackpot cap (1.0 billion credits).
     */
    public static final int MAX_MATERIALIZATION_STACKS = 50_000;

    /**
     * Splits a total count of a given item into a list of ItemStacks with max stack size of 64.
     */
    public static List<ItemStack> splitChips(Item item, int totalCount) {
        return splitChips(item, (long) totalCount);
    }

    public static List<ItemStack> splitChips(Item item, long totalCount) {
        List<ItemStack> list = new ArrayList<>();
        appendChips(list, item, totalCount, MAX_MATERIALIZATION_STACKS);
        return list;
    }

    /**
     * Appends chips in chunks of up to 64 to the target list without downcasting totalCount to int.
     */
    public static void appendChips(List<ItemStack> list, Item item, long totalCount) {
        appendChips(list, item, totalCount, MAX_MATERIALIZATION_STACKS);
    }

    public static void appendChips(List<ItemStack> list, Item item, long totalCount, int maxStacks) {
        if (item == null || totalCount <= 0 || list == null || maxStacks <= 0) return;
        long remaining = totalCount;
        while (remaining > 0 && list.size() < maxStacks) {
            int count = (int) Math.min(remaining, 64L);
            list.add(new ItemStack(item, count));
            remaining -= count;
        }
    }

    private static final ChipTier[] TIERS_DESCENDING = new ChipTier[]{
            ChipTier.NETHERITE,
            ChipTier.DIAMOND,
            ChipTier.GOLD,
            ChipTier.COPPER
    };

    /**
     * Converts a raw numeric jackpot amount into the most valuable chip tiers available,
     * splitting large quantities into valid ItemStacks (count <= 64) in chunks without
     * downcasting total chip counts from long to int.
     */
    public static List<ItemStack> convertAmountToChips(long amount) {
        return convertAmountToChips(amount, MAX_MATERIALIZATION_STACKS);
    }

    public static List<ItemStack> convertAmountToChips(long amount, int maxStacks) {
        List<ItemStack> list = new ArrayList<>();
        if (amount <= 0 || maxStacks <= 0) return list;

        long remaining = amount;

        for (ChipTier tier : TIERS_DESCENDING) {
            long tierValue = tier.getBaseValue();
            long chipCount = remaining / tierValue;
            if (chipCount > 0) {
                appendChips(list, tier.getItem(), chipCount, maxStacks);
                remaining %= tierValue;
                if (list.size() >= maxStacks) {
                    break;
                }
            }
        }

        return list;
    }
}
