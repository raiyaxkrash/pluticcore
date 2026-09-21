package net.pocketodds.gambling.core;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Canonical container for gambling round reward outcomes.
 *
 * Contract semantics:
 * - {@link #getItems()}: Contains ALL materialized physical item stacks to be delivered to the player.
 *   Reward factories (such as SlotRewardFactory) are responsible for materializing any jackpot payouts
 *   into chip stacks inside this list.
 * - {@link #getJackpotCredits()}: Contains the credit amount of the jackpot won. Used exclusively as informational
 *   metadata (for transaction records, jackpot tokens, stats, and broadcasts). Transaction services and outboxes
 *   MUST NOT rematerialize additional chips from this field.
 * - {@link #isJackpot()}: Indicates whether the outcome was a jackpot win.
 */
public class RewardBundle {
    private final List<ItemStack> items;
    private final long jackpotCredits;
    private final boolean jackpot;

    public RewardBundle() {
        this(Collections.emptyList(), 0L, false);
    }

    public RewardBundle(List<ItemStack> items, long jackpotCredits, boolean jackpot) {
        this.items = new ArrayList<>();
        if (items != null) {
            for (ItemStack stack : items) {
                if (stack != null && !stack.isEmpty()) {
                    // Ensure split by max stack size
                    int maxStack = stack.getMaxStackSize();
                    int rem = stack.getCount();
                    while (rem > 0) {
                        int take = Math.min(rem, maxStack);
                        ItemStack part = stack.copy();
                        part.setCount(take);
                        this.items.add(part);
                        rem -= take;
                    }
                }
            }
        }
        this.jackpotCredits = jackpotCredits;
        this.jackpot = jackpot;
    }

    public List<ItemStack> getItems() {
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack s : items) {
            copy.add(s.copy());
        }
        return Collections.unmodifiableList(copy);
    }

    public long getJackpotCredits() {
        return jackpotCredits;
    }

    public boolean isJackpot() {
        return jackpot;
    }

    public boolean isEmpty() {
        return items.isEmpty() && jackpotCredits <= 0L;
    }

    public List<RewardLine> toRewardLines() {
        List<RewardLine> lines = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            lines.add(new RewardLine(stack));
        }
        return lines;
    }
}
