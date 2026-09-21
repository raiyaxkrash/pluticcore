package net.pocketodds.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.item.ChipTier;
import net.pocketodds.registration.ModItems;

public class InventoryUtils {

    /**
     * Counts how many chips of a specific tier the player has in their entire inventory.
     */
    public static int countChips(Player player, ChipTier tier) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == tier.getItem()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Removes the specified number of chips of the given tier from the player's inventory.
     * Returns true if the full amount was removed, false if not enough.
     */
    public static boolean removeChips(Player player, ChipTier tier, int amountToRemove) {
        if (countChips(player, tier) < amountToRemove) {
            return false;
        }

        int remaining = amountToRemove;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == tier.getItem()) {
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
                if (remaining <= 0) {
                    break;
                }
            }
        }
        player.getInventory().setChanged();
        return true;
    }

    /**
     * Safely gives an item to the player. If inventory is full or partially full,
     * remaining items are cleanly dropped at the player's feet without duping.
     */
    public static void giveOrDrop(Player player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        // Try adding directly to inventory
        boolean added = player.getInventory().add(stack);

        // If not all items fit, drop the remainder
        if (!stack.isEmpty()) {
            ItemEntity entity = player.drop(stack.copy(), false);
            if (entity != null) {
                entity.setNoPickUpDelay();
                if (player instanceof ServerPlayer serverPlayer) {
                    entity.setTarget(serverPlayer.getUUID());
                }
            }
            stack.setCount(0);
        }
        player.getInventory().setChanged();
    }

    /**
     * Checks if player has an Insurance item in inventory.
     */
    public static boolean hasInsurance(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == ModItems.INSURANCE.get()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Consumes one Insurance item from inventory. Returns true if consumed.
     */
    public static boolean consumeInsurance(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == ModItems.INSURANCE.get()) {
                stack.shrink(1);
                player.getInventory().setChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if player has a Joker item in inventory.
     */
    public static boolean hasJoker(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == ModItems.JOKER.get()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Consumes one Joker item from inventory. Returns true if consumed.
     */
    public static boolean consumeJoker(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == ModItems.JOKER.get()) {
                stack.shrink(1);
                player.getInventory().setChanged();
                return true;
            }
        }
        return false;
    }
}
