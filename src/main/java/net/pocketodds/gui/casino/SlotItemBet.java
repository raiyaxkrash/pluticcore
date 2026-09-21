package net.pocketodds.gui.casino;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.itembet.ItemBetConfigEntry;
import net.pocketodds.gambling.itembet.ItemBetRegistry;
import net.pocketodds.gambling.itembet.ItemBetValidator;

public class SlotItemBet extends Slot {
    private final PocketCasinoMenu menu;

    public SlotItemBet(PocketCasinoMenu menu, Container container, int slotIndex, int xPosition, int yPosition) {
        super(container, slotIndex, xPosition, yPosition);
        this.menu = menu;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (ItemBetValidator.isForbiddenContainer(stack)) return false;

        ItemBetConfigEntry entry = ItemBetRegistry.getEntry(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        if (entry == null) return false;

        if (!entry.isAllowDamaged() && ItemBetValidator.isDamaged(stack)) return false;
        if (!entry.isAllowNbt() && ItemBetValidator.hasForbiddenNbt(stack)) return false;

        GameType activeGame = getGameTypeForCategory(menu != null ? menu.getCurrentCategory() : CasinoCategory.SLOTS);
        if (activeGame != null && !entry.isGameAllowed(activeGame)) {
            return false;
        }

        return true;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    public static GameType getGameTypeForCategory(CasinoCategory category) {
        if (category == null) return GameType.SLOT;
        return switch (category) {
            case SLOTS -> GameType.SLOT;
            case ROULETTE -> GameType.ROULETTE;
            case DICE -> GameType.DICE;
            case DECK_OF_FATE -> GameType.DECK;
            case JACKPOT_INFO, PRIZE_SHOP -> null;
        };
    }
}