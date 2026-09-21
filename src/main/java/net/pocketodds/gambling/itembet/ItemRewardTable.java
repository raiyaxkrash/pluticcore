package net.pocketodds.gambling.itembet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ItemRewardTable {
    private final List<ItemRewardEntry> entries;

    public ItemRewardTable(List<ItemRewardEntry> entries) {
        this.entries = (entries != null) ? Collections.unmodifiableList(new ArrayList<>(entries)) : Collections.emptyList();
    }

    public List<ItemRewardEntry> getEntries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Rolls items from the table matching the player's bet tier, scaling quantities by the won credit budget.
     *
     * @param random Random generator
     * @param budgetCredits Total credit budget won by the player (e.g. betCredits * multiplier)
     * @param betCredits Credit value of the initial bet (used to filter eligible entries)
     * @param globalMaxCap Global cap on max stack count
     * @return List of generated ItemStacks
     */
    public List<ItemStack> rollRewards(RandomSource random, long budgetCredits, long betCredits, int globalMaxCap) {
        List<ItemStack> result = new ArrayList<>();
        if (entries.isEmpty() || budgetCredits <= 0L) {
            return result;
        }

        // Filter entries valid for this bet's credit range
        List<ItemRewardEntry> eligible = new ArrayList<>();
        int totalWeight = 0;
        for (ItemRewardEntry entry : entries) {
            if (entry.matchesBetCredits(betCredits)) {
                eligible.add(entry);
                totalWeight += entry.getWeight();
            }
        }

        if (eligible.isEmpty() || totalWeight <= 0) {
            return result;
        }

        // Pick one reward weighted by probability
        int roll = random.nextInt(totalWeight);
        int current = 0;
        ItemRewardEntry selected = eligible.get(0);
        for (ItemRewardEntry entry : eligible) {
            current += entry.getWeight();
            if (roll < current) {
                selected = entry;
                break;
            }
        }

        Item item = resolveItem(selected);
        if (item == null || item == Items.AIR) {
            return result;
        }

        long unitVal = Math.max(1L, selected.getCreditValue());
        double exactCount = (double) budgetCredits / (double) unitVal;
        int baseCount = (int) Math.floor(exactCount);
        double fractionalPart = exactCount - baseCount;
        int count = baseCount + (random.nextDouble() < fractionalPart ? 1 : 0);

        int effectiveCap = Math.min(selected.getMaxCap(), globalMaxCap > 0 ? globalMaxCap : Integer.MAX_VALUE);
        int finalCount = Math.min(count, effectiveCap);

        if (finalCount > 0) {
            int maxStack = item.getMaxStackSize(new ItemStack(item));
            int remaining = finalCount;
            while (remaining > 0) {
                int take = Math.min(remaining, maxStack);
                result.add(new ItemStack(item, take));
                remaining -= take;
            }
        }

        return result;
    }

    /**
     * Backward-compatible overload calculating credit budget from betCredits * budgetFraction.
     */
    public List<ItemStack> rollRewards(RandomSource random, long betCredits, int globalMaxCap, double budgetFraction) {
        double rawBudget = betCredits * budgetFraction;
        long base = (long) Math.floor(rawBudget);
        long budgetCredits = base + (random.nextDouble() < (rawBudget - base) ? 1L : 0L);
        return rollRewards(random, budgetCredits, betCredits, globalMaxCap);
    }

    private static Item resolveItem(ItemRewardEntry entry) {
        if (ForgeRegistries.ITEMS != null && ForgeRegistries.ITEMS.containsKey(entry.getItemId())) {
            return ForgeRegistries.ITEMS.getValue(entry.getItemId());
        }
        return BuiltInRegistries.ITEM.get(entry.getItemId());
    }
}
