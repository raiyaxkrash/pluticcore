package net.pocketodds.gui.casino;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.c2s.BuyShopOfferC2SPacket;
import net.pocketodds.network.s2c.SyncShopCatalogS2CPacket;
import net.pocketodds.shop.ShopCategory;
import net.pocketodds.shop.ShopLimitPeriod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShopScreenTab {
    private final PocketCasinoScreen screen;
    private int playerTokens = 0;
    private final List<SyncShopCatalogS2CPacket.ClientShopEntry> entries = new ArrayList<>();

    private ShopCategory selectedCategoryFilter = null; // null = All
    private int currentPage = 0;
    private static final int ITEMS_PER_PAGE = 4;

    public ShopScreenTab(PocketCasinoScreen screen) {
        this.screen = screen;
    }

    public void updateCatalog(int tokens, List<SyncShopCatalogS2CPacket.ClientShopEntry> newEntries) {
        this.playerTokens = tokens;
        this.entries.clear();
        if (newEntries != null) {
            this.entries.addAll(newEntries);
        }
        this.currentPage = 0;
    }

    public int getPlayerTokens() {
        return playerTokens;
    }

    public List<SyncShopCatalogS2CPacket.ClientShopEntry> getFilteredEntries() {
        if (selectedCategoryFilter == null) {
            return entries;
        }
        return entries.stream()
                .filter(e -> e.getCategoryOrdinal() == selectedCategoryFilter.ordinal())
                .toList();
    }

    public void addWidgets(int leftPos, int topPos, java.util.function.Consumer<Button> widgetAdder) {
        List<SyncShopCatalogS2CPacket.ClientShopEntry> filtered = getFilteredEntries();
        int maxPages = Math.max(1, (filtered.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        if (currentPage >= maxPages) currentPage = maxPages - 1;
        if (currentPage < 0) currentPage = 0;

        // Prev page button
        widgetAdder.accept(Button.builder(Component.literal("◀"), btn -> {
            if (currentPage > 0) {
                currentPage--;
                screen.rebuildWidgetsForCurrentCategory();
            }
        }).bounds(leftPos + 10, topPos + 120, 20, 14).build());

        // Next page button
        widgetAdder.accept(Button.builder(Component.literal("▶"), btn -> {
            if (currentPage < maxPages - 1) {
                currentPage++;
                screen.rebuildWidgetsForCurrentCategory();
            }
        }).bounds(leftPos + 190, topPos + 120, 20, 14).build());

        // Category filter toggle button
        String catLabel = (selectedCategoryFilter == null) ? "Кат: Все" : "Кат: " + selectedCategoryFilter.name();
        if (catLabel.length() > 14) catLabel = catLabel.substring(0, 14);
        widgetAdder.accept(Button.builder(Component.literal(catLabel), btn -> {
            toggleNextCategory();
            currentPage = 0;
            screen.rebuildWidgetsForCurrentCategory();
        }).bounds(leftPos + 34, topPos + 120, 76, 14).build());

        // Render Buy buttons for items on current page (up to 4 items in 2x2 grid)
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filtered.size());

        for (int i = startIndex; i < endIndex; i++) {
            int slotIdx = i - startIndex;
            SyncShopCatalogS2CPacket.ClientShopEntry entry = filtered.get(i);

            int col = slotIdx % 2;
            int row = slotIdx / 2;
            int cardX = leftPos + 12 + col * 98;
            int cardY = topPos + 38 + row * 40;

            int btnX = cardX + 50;
            int btnY = cardY + 22;

            boolean canBuy = entry.isAvailable();
            String btnText = canBuy ? "Купить" : (entry.getRemainingLimit() == 0 ? "Лимит" : "Жетоны");

            Button buyBtn = Button.builder(Component.literal(btnText), btn -> {
                ModMessages.sendToServer(new BuyShopOfferC2SPacket(screen.getMenu().containerId, entry.getOfferId(), UUID.randomUUID()));
            }).bounds(btnX, btnY, 44, 14).build();

            buyBtn.active = canBuy;
            widgetAdder.accept(buyBtn);
        }
    }

    private void toggleNextCategory() {
        if (selectedCategoryFilter == null) {
            selectedCategoryFilter = ShopCategory.RESOURCES;
        } else {
            int nextOrd = selectedCategoryFilter.ordinal() + 1;
            if (nextOrd >= ShopCategory.values().length) {
                selectedCategoryFilter = null; // Back to All
            } else {
                selectedCategoryFilter = ShopCategory.values()[nextOrd];
            }
        }
    }

    public void render(GuiGraphics guiGraphics, int leftPos, int topPos, int mouseX, int mouseY, float partialTick) {
        // Render Header Token Balance
        String tokenStr = "§dЖетоны: " + playerTokens + " шт.§r";
        guiGraphics.drawString(Minecraft.getInstance().font, tokenStr, leftPos + 12, topPos + 24, 0xFFFFFF, false);

        List<SyncShopCatalogS2CPacket.ClientShopEntry> filtered = getFilteredEntries();
        int maxPages = Math.max(1, (filtered.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);

        // Render Page indicator
        String pageStr = (currentPage + 1) + "/" + maxPages;
        guiGraphics.drawString(Minecraft.getInstance().font, pageStr, leftPos + 115, topPos + 123, 0xCCCCCC, false);

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filtered.size());

        for (int i = startIndex; i < endIndex; i++) {
            int slotIdx = i - startIndex;
            SyncShopCatalogS2CPacket.ClientShopEntry entry = filtered.get(i);

            int col = slotIdx % 2;
            int row = slotIdx / 2;
            int cardX = leftPos + 12 + col * 98;
            int cardY = topPos + 38 + row * 40;

            renderOfferCard(guiGraphics, cardX, cardY, entry);
        }
    }

    private void renderOfferCard(GuiGraphics guiGraphics, int x, int y, SyncShopCatalogS2CPacket.ClientShopEntry entry) {
        // Card border & background
        guiGraphics.fill(x, y, x + 96, y + 38, 0xFF2A2631);
        guiGraphics.fill(x + 1, y + 1, x + 95, y + 37, 0xFF18171B);

        // Item icon slot
        guiGraphics.fill(x + 3, y + 3, x + 23, y + 23, 0xFF2F2B37);
        ItemStack stack = entry.getRewardStack();
        if (!stack.isEmpty()) {
            guiGraphics.renderItem(stack, x + 4, y + 4);
            guiGraphics.renderItemDecorations(Minecraft.getInstance().font, stack, x + 4, y + 4);
        }

        // Title
        String title = stack.isEmpty() ? entry.getOfferId() : stack.getHoverName().getString();
        if (title.length() > 11) {
            title = title.substring(0, 10) + "…";
        }
        guiGraphics.drawString(Minecraft.getInstance().font, title, x + 26, y + 4, 0xFFD4AF37, false);

        // Price
        String priceStr = "§d" + entry.getPrice() + " Жет.";
        guiGraphics.drawString(Minecraft.getInstance().font, priceStr, x + 26, y + 15, 0xFFFFFF, false);

        // Limit Badge
        String limitStr;
        if (entry.getRemainingLimit() < 0) {
            limitStr = "§7∞";
        } else {
            ShopLimitPeriod period = ShopLimitPeriod.fromOrdinal(entry.getLimitPeriodOrdinal());
            String perSuffix = switch (period) {
                case DAILY -> "/д";
                case WEEKLY -> "/н";
                case PERMANENT -> " шт";
                case UNLIMITED -> "";
            };
            limitStr = (entry.getRemainingLimit() > 0 ? "§a" : "§c") + entry.getRemainingLimit() + perSuffix;
        }
        guiGraphics.drawString(Minecraft.getInstance().font, limitStr, x + 4, y + 26, 0xAAAAAA, false);
    }

    public void renderTooltips(GuiGraphics guiGraphics, int leftPos, int topPos, int mouseX, int mouseY) {
        List<SyncShopCatalogS2CPacket.ClientShopEntry> filtered = getFilteredEntries();
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filtered.size());

        for (int i = startIndex; i < endIndex; i++) {
            int slotIdx = i - startIndex;
            SyncShopCatalogS2CPacket.ClientShopEntry entry = filtered.get(i);

            int col = slotIdx % 2;
            int row = slotIdx / 2;
            int itemX = leftPos + 12 + col * 98 + 4;
            int itemY = topPos + 38 + row * 40 + 4;

            if (mouseX >= itemX && mouseX <= itemX + 16 && mouseY >= itemY && mouseY <= itemY + 16) {
                ItemStack stack = entry.getRewardStack();
                if (!stack.isEmpty()) {
                    guiGraphics.renderTooltip(Minecraft.getInstance().font, stack, mouseX, mouseY);
                }
            }
        }
    }
}
