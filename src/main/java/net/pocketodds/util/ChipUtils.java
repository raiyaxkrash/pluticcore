package net.pocketodds.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.item.ChipTier;
import net.pocketodds.registration.ModItems;

import java.util.ArrayList;
import java.util.List;

public class ChipUtils {

    /**
     * Splits a total count of a given item into a list of ItemStacks with max stack size of 64.
     */
    public static List<ItemStack> splitChips(Item item, int totalCount) {
        List<ItemStack> list = new ArrayList<>();
        int remaining = totalCount;
        while (remaining > 0) {
            int count = Math.min(remaining, 64);
            list.add(new ItemStack(item, count));
            remaining -= count;
        }
        return list;
    }

    /**
     * Converts a raw numeric jackpot amount into the most valuable chip tiers available,
     * splitting large quantities into valid ItemStacks (count <= 64).
     */
    public static List<ItemStack> convertAmountToChips(long amount) {
        List<ItemStack> list = new ArrayList<>();
        long remaining = amount;

        int netheriteCount = (int) (remaining / ChipTier.NETHERITE.getBaseValue());
        if (netheriteCount > 0) {
            list.addAll(splitChips(ChipTier.NETHERITE.getItem(), netheriteCount));
            remaining %= ChipTier.NETHERITE.getBaseValue();
        }

        int diamondCount = (int) (remaining / ChipTier.DIAMOND.getBaseValue());
        if (diamondCount > 0) {
            list.addAll(splitChips(ChipTier.DIAMOND.getItem(), diamondCount));
            remaining %= ChipTier.DIAMOND.getBaseValue();
        }

        int goldCount = (int) (remaining / ChipTier.GOLD.getBaseValue());
        if (goldCount > 0) {
            list.addAll(splitChips(ChipTier.GOLD.getItem(), goldCount));
            remaining %= ChipTier.GOLD.getBaseValue();
        }

        int copperCount = (int) (remaining / ChipTier.COPPER.getBaseValue());
        if (copperCount > 0) {
            list.addAll(splitChips(ChipTier.COPPER.getItem(), copperCount));
        }

        return list;
    }
}
