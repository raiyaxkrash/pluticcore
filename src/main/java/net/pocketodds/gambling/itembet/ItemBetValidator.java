package net.pocketodds.gambling.itembet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.pocketodds.gambling.core.GameType;

public class ItemBetValidator {

    public enum ValidationResult {
        VALID,
        NOT_ALLOWED_ITEM,
        GAME_NOT_ALLOWED,
        CONTAINER_FORBIDDEN,
        DAMAGED_FORBIDDEN,
        NBT_OR_ENCHANTS_FORBIDDEN,
        COUNT_OUT_OF_RANGE
    }

    /**
     * Strictly checks whether an ItemStack is a container capable of holding items.
     * Container items are unconditionally prohibited for bets!
     */
    public static boolean isForbiddenContainer(ItemStack stack) {
        return isContainerItem(stack);
    }

    public static boolean isDamaged(ItemStack stack) {
        return stack != null && stack.isDamageableItem() && stack.isDamaged();
    }

    public static boolean hasForbiddenNbt(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isEnchanted() || stack.hasCustomHoverName()) return true;
        CompoundTag tag = stack.getTag();
        return tag != null && !tag.isEmpty();
    }

    public static boolean isContainerItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        // 1. Shulker boxes
        if (stack.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block instanceof ShulkerBoxBlock) {
                return true;
            }
        }

        // 2. Bundles
        if (stack.getItem() instanceof BundleItem) {
            return true;
        }

        // 3. BlockEntityTag containing "Items"
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            if (tag.contains("BlockEntityTag", Tag.TAG_COMPOUND)) {
                CompoundTag beTag = tag.getCompound("BlockEntityTag");
                if (beTag.contains("Items", Tag.TAG_LIST) || beTag.contains("Inventory")) {
                    return true;
                }
            }
            if (tag.contains("Items", Tag.TAG_LIST)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Validates an ItemStack against a registered ItemBetConfigEntry and GameType.
     */
    public static ValidationResult validate(ItemStack stack, int count, ItemBetConfigEntry entry, GameType gameType) {
        if (entry == null) {
            return ValidationResult.NOT_ALLOWED_ITEM;
        }

        if (!entry.isGameAllowed(gameType)) {
            return ValidationResult.GAME_NOT_ALLOWED;
        }

        // Unconditional ban on containers
        if (isContainerItem(stack)) {
            return ValidationResult.CONTAINER_FORBIDDEN;
        }

        // Check damage
        if (!entry.isAllowDamaged() && stack.isDamageableItem() && stack.isDamaged()) {
            return ValidationResult.DAMAGED_FORBIDDEN;
        }

        // Check NBT / enchantments / custom names
        if (!entry.isAllowNbt()) {
            if (stack.isEnchanted() || stack.hasCustomHoverName()) {
                return ValidationResult.NBT_OR_ENCHANTS_FORBIDDEN;
            }
            CompoundTag tag = stack.getTag();
            if (tag != null && !tag.isEmpty()) {
                return ValidationResult.NBT_OR_ENCHANTS_FORBIDDEN;
            }
        }

        // Check count range
        if (count < entry.getMinCount() || count > entry.getMaxCount()) {
            return ValidationResult.COUNT_OUT_OF_RANGE;
        }

        return ValidationResult.VALID;
    }
}
