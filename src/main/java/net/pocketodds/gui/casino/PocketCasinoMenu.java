package net.pocketodds.gui.casino;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.item.ChipTier;
import net.pocketodds.registration.ModMenus;
import net.pocketodds.service.CasinoGameResult;

public class PocketCasinoMenu extends AbstractContainerMenu {
    public static final int BET_SLOT_INDEX = 0;
    public static final int INV_START = 1;
    public static final int INV_END = 28;
    public static final int HOTBAR_START = 28;
    public static final int HOTBAR_END = 37;

    private final Container betContainer = new SimpleContainer(1);
    private final Inventory playerInventory;

    private CasinoCategory currentCategory = CasinoCategory.SLOTS;
    private BetFundingSource betFundingSource = BetFundingSource.INVENTORY;
    private ChipTier selectedChipTier = ChipTier.COPPER;
    private int betCount = 1;
    private RouletteBetType rouletteBetType = RouletteBetType.RED;
    private PayoutDestination payoutDestination = PayoutDestination.INVENTORY;
    private CasinoGameResult lastResult = null;

    // Client-side constructor
    public PocketCasinoMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(containerId, playerInventory, extraData != null ? CasinoCategory.fromOrdinal(extraData.readInt()) : CasinoCategory.SLOTS);
    }

    // Server-side constructor
    public PocketCasinoMenu(int containerId, Inventory playerInventory, CasinoCategory initialCategory) {
        super(ModMenus.POCKET_CASINO_MENU.get(), containerId);
        this.playerInventory = playerInventory;
        if (initialCategory != null) {
            this.currentCategory = initialCategory;
        }

        // Slot 0: Dedicated Item Bet Slot
        this.addSlot(new SlotItemBet(this, this.betContainer, 0, 14, 154));

        // Player Inventory (3 rows x 9)
        int invX = 40;
        int invY = 150;
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, invX + col * 18, invY + row * 18));
            }
        }

        // Player Hotbar (1 row x 9)
        int hotbarY = 208;
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, invX + col * 18, hotbarY));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.clearContainer(player, this.betContainer);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemstack = slotStack.copy();

            if (index == BET_SLOT_INDEX) {
                if (!this.moveItemStackTo(slotStack, INV_START, HOTBAR_END, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                Slot betSlot = this.slots.get(BET_SLOT_INDEX);
                if (betSlot.mayPlace(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, BET_SLOT_INDEX, BET_SLOT_INDEX + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index >= INV_START && index < HOTBAR_START) {
                    if (!this.moveItemStackTo(slotStack, HOTBAR_START, HOTBAR_END, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index >= HOTBAR_START && index < HOTBAR_END) {
                    if (!this.moveItemStackTo(slotStack, INV_START, HOTBAR_START, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            }

            if (slotStack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (slotStack.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, slotStack);
        }

        return itemstack;
    }

    public ItemStack getBetItem() {
        return this.betContainer.getItem(0);
    }

    public void setBetItem(ItemStack stack) {
        this.betContainer.setItem(0, stack);
    }

    public Container getBetContainer() {
        return betContainer;
    }

    public Inventory getPlayerInventory() {
        return playerInventory;
    }

    public CasinoCategory getCurrentCategory() {
        return currentCategory;
    }

    public void setCurrentCategory(CasinoCategory currentCategory) {
        this.currentCategory = currentCategory != null ? currentCategory : CasinoCategory.SLOTS;
    }

    public BetFundingSource getBetFundingSource() {
        return betFundingSource;
    }

    public void setBetFundingSource(BetFundingSource betFundingSource) {
        this.betFundingSource = betFundingSource != null ? betFundingSource : BetFundingSource.INVENTORY;
    }

    public ChipTier getSelectedChipTier() {
        return selectedChipTier;
    }

    public void setSelectedChipTier(ChipTier selectedChipTier) {
        this.selectedChipTier = selectedChipTier != null ? selectedChipTier : ChipTier.COPPER;
    }

    public int getBetCount() {
        return betCount;
    }

    public void setBetCount(int betCount) {
        this.betCount = Math.max(1, betCount);
    }

    public RouletteBetType getRouletteBetType() {
        return rouletteBetType;
    }

    public void setRouletteBetType(RouletteBetType rouletteBetType) {
        this.rouletteBetType = rouletteBetType != null ? rouletteBetType : RouletteBetType.RED;
    }

    public PayoutDestination getPayoutDestination() {
        return payoutDestination;
    }

    public void setPayoutDestination(PayoutDestination payoutDestination) {
        this.payoutDestination = payoutDestination != null ? payoutDestination : PayoutDestination.INVENTORY;
    }

    public CasinoGameResult getLastResult() {
        return lastResult;
    }

    public void setLastResult(CasinoGameResult lastResult) {
        this.lastResult = lastResult;
    }
}