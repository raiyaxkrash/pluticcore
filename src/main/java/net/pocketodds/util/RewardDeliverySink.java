package net.pocketodds.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Functional interface for delivering reward item stacks to a player.
 * Allows decoupling and mocking in unit tests without modifying production state.
 */
@FunctionalInterface
public interface RewardDeliverySink {
    void deliver(ServerPlayer player, ItemStack stack);
}
