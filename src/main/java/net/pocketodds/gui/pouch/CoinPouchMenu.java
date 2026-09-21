package net.pocketodds.gui.pouch;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.item.ChipItem;
import net.pocketodds.item.CoinPouchItem;
import net.pocketodds.registration.ModMenus;
import net.pocketodds.service.CoinPouchService;

import java.util.UUID;

public class CoinPouchMenu extends AbstractContainerMenu {
    private final Inventory playerInventory;
    private final int pouchSlotIndex;
    private final UUID pouchUUID;

    // Client-side constructor
    public CoinPouchMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(containerId, playerInventory, extraData != null ? extraData.readInt() : -1);
    }

    // Server-side constructor
    public CoinPouchMenu(int containerId, Inventory playerInventory, int pouchSlotIndex) {
        super(ModMenus.COIN_POUCH_MENU.get(), containerId);
        this.playerInventory = playerInventory;
        this.pouchSlotIndex = pouchSlotIndex;

        ItemStack pouch = getPouchStack();
        this.pouchUUID = CoinPouchService.getOrCreatePouchUUID(pouch);

        // Player Inventory (3 rows x 9)
        int invX = 8;
        int invY = 84;
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                int slotIndex = col + row * 9 + 9;
                this.addSlot(new Slot(playerInventory, slotIndex, invX + col * 18, invY + row * 18) {
                    @Override
                    public boolean mayPickup(Player player) {
                        return slotIndex != pouchSlotIndex;
                    }

                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return !(stack.getItem() instanceof CoinPouchItem);
                    }
                });
            }
        }

        // Player Hotbar (1 row x 9)
        int hotbarY = 142;
        for (int col = 0; col < 9; ++col) {
            int slotIndex = col;
            this.addSlot(new Slot(playerInventory, slotIndex, invX + col * 18, hotbarY) {
                @Override
                public boolean mayPickup(Player player) {
                    return slotIndex != pouchSlotIndex;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return !(stack.getItem() instanceof CoinPouchItem);
                }
            });
        }
    }

    public ItemStack getPouchStack() {
        if (pouchSlotIndex >= 0 && pouchSlotIndex < playerInventory.getContainerSize()) {
            return playerInventory.getItem(pouchSlotIndex);
        }
        return CoinPouchService.findFirstPouch(playerInventory.player instanceof ServerPlayer sp ? sp : null);
    }

    public int getPouchSlotIndex() {
        return pouchSlotIndex;
    }

    public UUID getPouchUUID() {
        return pouchUUID;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player == null || !player.isAlive()) {
            return false;
        }
        ItemStack pouch = getPouchStack();
        if (pouch == null || pouch.isEmpty() || !(pouch.getItem() instanceof CoinPouchItem)) {
            return false;
        }
        if (pouchUUID != null) {
            UUID currentUUID = CoinPouchService.getPouchUUID(pouch);
            return pouchUUID.equals(currentUUID);
        }
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (pouchUUID != null && player != null) {
            CoinPouchService.releasePouchLock(pouchUUID, player.getUUID());
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        if (stack.getItem() instanceof CoinPouchItem) {
            return ItemStack.EMPTY;
        }
        if (stack.getItem() instanceof ChipItem chipItem) {
            ItemStack pouch = getPouchStack();
            if (!pouch.isEmpty() && player instanceof ServerPlayer serverPlayer) {
                int deposited = CoinPouchService.depositChips(serverPlayer, pouch, chipItem.getTier(), stack.getCount());
                if (deposited > 0) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return ItemStack.EMPTY;
    }
}