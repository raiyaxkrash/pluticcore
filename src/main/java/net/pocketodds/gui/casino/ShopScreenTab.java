package net.pocketodds.gui.casino;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.item.ChipTier;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.c2s.BuyShopOfferC2SPacket;
import net.pocketodds.network.s2c.SyncShopCatalogS2CPacket;
import net.pocketodds.shop.ShopCategory;
import net.pocketodds.shop.ShopLimitPeriod;
import net.pocketodds.shop.ShopOffer;
import net.pocketodds.shop.ShopPaymentSource;
import net.pocketodds.util.InventoryUtils;

import java.text.NumberFormat;
import java.util.*;

public class ShopScreenTab {
    public enum SortMode {
        DEFAULT("shop.pocketodds.sort.default"),
        PRICE_ASC("shop.pocketodds.sort.price_asc"),
        PRICE_DESC("shop.pocketodds.sort.price_desc"),
        NAME_ASC("shop.pocketodds.sort.name_asc");

        private final String translationKey;

        SortMode(String translationKey) {
            this.translationKey = translationKey;
        }

        public Component getLabel() {
            return Component.translatable(translationKey);
        }
    }

    private final PocketCasinoScreen screen;
    private long pouchCredits = 0L;
    private final List<SyncShopCatalogS2CPacket.ClientShopEntry> entries = new ArrayList<>();

    private ShopPaymentSource selectedSource = ShopPaymentSource.INVENTORY;
    private ShopCategory selectedCategoryFilter = null; // null = All
    private SortMode sortMode = SortMode.DEFAULT;
    private String searchQuery = "";
    private EditBox searchBox = null;

    private int currentPage = 0;
    private static final int ITEMS_PER_PAGE = 4;

    private String pendingConfirmOfferId = "";
    private long confirmTimestamp = 0L;

    // Creative modal confirmation state
    private SyncShopCatalogS2CPacket.ClientShopEntry confirmModalEntry = null;

    public ShopScreenTab(PocketCasinoScreen screen) {
        this.screen = screen;
    }

    public void updateCatalog(long pouchCredits, List<SyncShopCatalogS2CPacket.ClientShopEntry> newEntries) {
        this.pouchCredits = Math.max(0L, pouchCredits);
        this.entries.clear();
        if (newEntries != null) {
            this.entries.addAll(newEntries);
        }
        this.currentPage = 0;
        this.pendingConfirmOfferId = "";
        this.confirmModalEntry = null;
    }

    public void updateCatalog(int legacyTokens, List<SyncShopCatalogS2CPacket.ClientShopEntry> newEntries) {
        updateCatalog((long) legacyTokens, newEntries);
    }

    public long getPouchCredits() {
        return pouchCredits;
    }

    public long getInventoryCredits() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return 0L;
        long total = 0L;
        for (ChipTier tier : ChipTier.values()) {
            int count = InventoryUtils.countChips(player, tier);
            total += (long) count * tier.getBaseValue();
        }
        return total;
    }

    public long getAvailableCredits(ShopPaymentSource source) {
        long inv = getInventoryCredits();
        long pouch = pouchCredits;
        return switch (source) {
            case INVENTORY -> inv;
            case POUCH -> pouch;
            case INVENTORY_THEN_POUCH, POUCH_THEN_INVENTORY -> inv + pouch;
        };
    }

    public List<SyncShopCatalogS2CPacket.ClientShopEntry> getFilteredEntries() {
        List<SyncShopCatalogS2CPacket.ClientShopEntry> result = new ArrayList<>();
        for (SyncShopCatalogS2CPacket.ClientShopEntry e : entries) {
            // Category filter
            if (selectedCategoryFilter != null && e.getCategoryOrdinal() != selectedCategoryFilter.ordinal()) {
                continue;
            }
            // Search query filter
            if (!searchQuery.isEmpty()) {
                String name = e.getRewardStack().isEmpty() ? "" : e.getRewardStack().getHoverName().getString().toLowerCase(Locale.ROOT);
                String id = e.getOfferId().toLowerCase(Locale.ROOT);
                if (!name.contains(searchQuery) && !id.contains(searchQuery)) {
                    continue;
                }
            }
            result.add(e);
        }

        // Sorting
        switch (sortMode) {
            case PRICE_ASC -> result.sort(Comparator.comparingLong(SyncShopCatalogS2CPacket.ClientShopEntry::getPriceCredits));
            case PRICE_DESC -> result.sort(Comparator.comparingLong(SyncShopCatalogS2CPacket.ClientShopEntry::getPriceCredits).reversed());
            case NAME_ASC -> result.sort((a, b) -> {
                String nameA = a.getRewardStack().isEmpty() ? a.getOfferId() : a.getRewardStack().getHoverName().getString();
                String nameB = b.getRewardStack().isEmpty() ? b.getOfferId() : b.getRewardStack().getHoverName().getString();
                return nameA.compareToIgnoreCase(nameB);
            });
            case DEFAULT -> {
                // Keep default incoming order
            }
        }

        return result;
    }

    public void addWidgets(int leftPos, int topPos, java.util.function.Consumer<AbstractWidget> widgetAdder) {
        // If modal confirmation is open, render only modal dialog buttons
        if (confirmModalEntry != null) {
            // Confirm Buy Button
            widgetAdder.accept(Button.builder(Component.translatable("shop.pocketodds.action.confirm"), btn -> {
                ModMessages.sendToServer(new BuyShopOfferC2SPacket(screen.getMenu().containerId, confirmModalEntry.getOfferId(), UUID.randomUUID(), selectedSource));
                confirmModalEntry = null;
                screen.rebuildWidgetsForCurrentCategory();
            }).bounds(leftPos + 24, topPos + 96, 80, 16).build());

            // Cancel Button
            widgetAdder.accept(Button.builder(Component.translatable("shop.pocketodds.action.cancel"), btn -> {
                confirmModalEntry = null;
                screen.rebuildWidgetsForCurrentCategory();
            }).bounds(leftPos + 116, topPos + 96, 80, 16).build());
            return;
        }

        // Payment Source Toggle Button
        Component srcName = Component.translatable("shop.pocketodds.source." + selectedSource.name().toLowerCase(Locale.ROOT));
        widgetAdder.accept(Button.builder(srcName, btn -> {
            selectedSource = ShopPaymentSource.fromOrdinal((selectedSource.ordinal() + 1) % ShopPaymentSource.values().length);
            pendingConfirmOfferId = "";
            screen.rebuildWidgetsForCurrentCategory();
        }).bounds(leftPos + 10, topPos + 20, 56, 14).build());

        // Search EditBox
        String oldQuery = (searchBox != null) ? searchBox.getValue() : searchQuery;
        searchBox = new EditBox(Minecraft.getInstance().font, leftPos + 68, topPos + 20, 74, 14, Component.translatable("shop.pocketodds.search.title"));
        searchBox.setHint(Component.translatable("shop.pocketodds.search.hint"));
        searchBox.setValue(oldQuery);
        searchBox.setResponder(text -> {
            this.searchQuery = text.trim().toLowerCase(Locale.ROOT);
            this.currentPage = 0;
            screen.rebuildWidgetsForCurrentCategory();
        });
        widgetAdder.accept(searchBox);

        List<SyncShopCatalogS2CPacket.ClientShopEntry> filtered = getFilteredEntries();
        int maxPages = Math.max(1, (filtered.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        if (currentPage >= maxPages) currentPage = maxPages - 1;
        if (currentPage < 0) currentPage = 0;

        // Prev page button
        widgetAdder.accept(Button.builder(Component.literal("◀"), btn -> {
            if (currentPage > 0) {
                currentPage--;
                pendingConfirmOfferId = "";
                screen.rebuildWidgetsForCurrentCategory();
            }
        }).bounds(leftPos + 10, topPos + 120, 18, 14).build());

        // Category filter toggle button
        Component catLabel = (selectedCategoryFilter == null)
                ? Component.translatable("shop.pocketodds.category.all_filter")
                : Component.translatable("shop.pocketodds.category.prefix", Component.translatable("shop.pocketodds.category." + selectedCategoryFilter.name().toLowerCase(Locale.ROOT)));
        widgetAdder.accept(Button.builder(catLabel, btn -> {
            toggleNextCategory();
            currentPage = 0;
            pendingConfirmOfferId = "";
            screen.rebuildWidgetsForCurrentCategory();
        }).bounds(leftPos + 30, topPos + 120, 72, 14).build());

        // Sort mode toggle button
        widgetAdder.accept(Button.builder(sortMode.getLabel(), btn -> {
            toggleNextSortMode();
            currentPage = 0;
            pendingConfirmOfferId = "";
            screen.rebuildWidgetsForCurrentCategory();
        }).bounds(leftPos + 104, topPos + 120, 60, 14).build());

        // Next page button
        widgetAdder.accept(Button.builder(Component.literal("▶"), btn -> {
            if (currentPage < maxPages - 1) {
                currentPage++;
                pendingConfirmOfferId = "";
                screen.rebuildWidgetsForCurrentCategory();
            }
        }).bounds(leftPos + 192, topPos + 120, 18, 14).build());

        // Buy buttons for items on current page (up to 4 items in 2x2 grid)
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filtered.size());
        long available = getAvailableCredits(selectedSource);
        long now = System.currentTimeMillis();

        for (int i = startIndex; i < endIndex; i++) {
            int slotIdx = i - startIndex;
            SyncShopCatalogS2CPacket.ClientShopEntry entry = filtered.get(i);

            int col = slotIdx % 2;
            int row = slotIdx / 2;
            int cardX = leftPos + 12 + col * 98;
            int cardY = topPos + 38 + row * 40;

            int btnX = cardX + 50;
            int btnY = cardY + 22;

            boolean hasLimit = entry.getRemainingLimit() != 0;
            boolean hasAdvancement = entry.isAdvancementSatisfied();
            boolean hasStage = entry.isStageSatisfied();
            boolean hasFunds = available >= entry.getPriceCredits();
            boolean canBuy = hasLimit && hasAdvancement && hasStage && hasFunds && entry.isAvailable();

            boolean isCreativeCategory = entry.getCategoryOrdinal() == ShopCategory.CREATIVE.ordinal();
            boolean isVeryExpensive = entry.getPriceCredits() >= 262144L;

            Component btnText;
            if (!hasLimit) {
                btnText = Component.translatable("shop.pocketodds.button.limit");
            } else if (!hasAdvancement) {
                btnText = Component.translatable("shop.pocketodds.button.progress");
            } else if (!hasStage) {
                btnText = Component.translatable("shop.pocketodds.button.stage");
            } else if (!hasFunds) {
                btnText = Component.translatable("shop.pocketodds.button.no_funds");
            } else if (isCreativeCategory || isVeryExpensive) {
                btnText = Component.translatable("shop.pocketodds.button.buy");
            } else if (entry.getPriceCredits() >= 4096L && pendingConfirmOfferId.equals(entry.getOfferId()) && (now - confirmTimestamp < 3000L)) {
                btnText = Component.translatable("shop.pocketodds.button.confirm_prompt");
            } else {
                btnText = Component.translatable("shop.pocketodds.button.buy");
            }

            Button buyBtn = Button.builder(btnText, btn -> {
                if (isCreativeCategory || isVeryExpensive) {
                    this.confirmModalEntry = entry;
                    screen.rebuildWidgetsForCurrentCategory();
                } else if (entry.getPriceCredits() >= 4096L && (!pendingConfirmOfferId.equals(entry.getOfferId()) || (System.currentTimeMillis() - confirmTimestamp >= 3000L))) {
                    pendingConfirmOfferId = entry.getOfferId();
                    confirmTimestamp = System.currentTimeMillis();
                    screen.rebuildWidgetsForCurrentCategory();
                } else {
                    pendingConfirmOfferId = "";
                    ModMessages.sendToServer(new BuyShopOfferC2SPacket(screen.getMenu().containerId, entry.getOfferId(), UUID.randomUUID(), selectedSource));
                }
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

    private void toggleNextSortMode() {
        int nextOrd = (sortMode.ordinal() + 1) % SortMode.values().length;
        this.sortMode = SortMode.values()[nextOrd];
    }

    public void render(GuiGraphics guiGraphics, int leftPos, int topPos, int mouseX, int mouseY, float partialTick) {
        long available = getAvailableCredits(selectedSource);
        Component balanceComp = Component.translatable("shop.pocketodds.balance.available", formatNumber(available));
        guiGraphics.drawString(Minecraft.getInstance().font, balanceComp, leftPos + 144, topPos + 23, 0xFFFFFF, false);

        if (confirmModalEntry != null) {
            renderConfirmModal(guiGraphics, leftPos, topPos);
            return;
        }

        List<SyncShopCatalogS2CPacket.ClientShopEntry> filtered = getFilteredEntries();
        int maxPages = Math.max(1, (filtered.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);

        // Page indicator
        String pageStr = (currentPage + 1) + "/" + maxPages;
        guiGraphics.drawString(Minecraft.getInstance().font, pageStr, leftPos + 168, topPos + 123, 0xCCCCCC, false);

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

    private void renderConfirmModal(GuiGraphics guiGraphics, int leftPos, int topPos) {
        int modalX = leftPos + 10;
        int modalY = topPos + 22;
        int modalW = 200;
        int modalH = 112;

        // Modal backdrop and gold border
        guiGraphics.fill(modalX - 2, modalY - 2, modalX + modalW + 2, modalY + modalH + 2, 0xFFD4AF37);
        guiGraphics.fill(modalX, modalY, modalX + modalW, modalY + modalH, 0xFA141217);

        // Title
        guiGraphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable("shop.pocketodds.modal.title"), modalX + modalW / 2, modalY + 8, 0xFFD700);

        ItemStack stack = confirmModalEntry.getRewardStack();
        String itemName = stack.isEmpty() ? confirmModalEntry.getOfferId() : stack.getHoverName().getString();
        guiGraphics.drawCenteredString(Minecraft.getInstance().font, "§f" + itemName + "§r", modalX + modalW / 2, modalY + 24, 0xFFFFFF);

        // Icon
        if (!stack.isEmpty()) {
            guiGraphics.renderItem(stack, modalX + modalW / 2 - 8, modalY + 36);
        }

        // Price and details
        Component priceComp = Component.translatable("shop.pocketodds.modal.price", formatNumber(confirmModalEntry.getPriceCredits()));
        guiGraphics.drawCenteredString(Minecraft.getInstance().font, priceComp, modalX + modalW / 2, modalY + 56, 0xFFFFFF);

        Component warningComp = (confirmModalEntry.getCategoryOrdinal() == ShopCategory.CREATIVE.ordinal())
                ? Component.translatable("shop.pocketodds.modal.warning.creative")
                : Component.translatable("shop.pocketodds.modal.warning.expensive");
        guiGraphics.drawCenteredString(Minecraft.getInstance().font, warningComp, modalX + modalW / 2, modalY + 70, 0xFFAAAA);
    }

    private void renderOfferCard(GuiGraphics guiGraphics, int x, int y, SyncShopCatalogS2CPacket.ClientShopEntry entry) {
        boolean isCreative = entry.getCategoryOrdinal() == ShopCategory.CREATIVE.ordinal();

        // Card border & background
        int borderColor = isCreative ? 0xFF8A2BE2 : 0xFF2A2631;
        int innerColor = isCreative ? 0xFF1D1226 : 0xFF18171B;
        guiGraphics.fill(x, y, x + 96, y + 38, borderColor);
        guiGraphics.fill(x + 1, y + 1, x + 95, y + 37, innerColor);

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
        int titleColor = isCreative ? 0xFFFF77FF : 0xFFD4AF37;
        guiGraphics.drawString(Minecraft.getInstance().font, title, x + 26, y + 4, titleColor, false);

        // Price in credits
        Component priceComp = Component.translatable("shop.pocketodds.credits_amount", formatNumber(entry.getPriceCredits()));
        guiGraphics.drawString(Minecraft.getInstance().font, priceComp, x + 26, y + 15, 0xFFFFFF, false);

        // Limit Badge
        String limitStr;
        if (entry.getRemainingLimit() < 0) {
            limitStr = "§7∞";
        } else {
            ShopLimitPeriod period = ShopLimitPeriod.fromOrdinal(entry.getLimitPeriodOrdinal());
            String perSuffix = switch (period) {
                case DAILY -> Component.translatable("shop.pocketodds.limit.daily_suffix").getString();
                case WEEKLY -> Component.translatable("shop.pocketodds.limit.weekly_suffix").getString();
                case PER_PLAYER, PERMANENT -> " " + Component.translatable("shop.pocketodds.limit.items_unit").getString();
                case UNLIMITED -> "";
            };
            limitStr = (entry.getRemainingLimit() > 0 ? "§a" : "§c") + entry.getRemainingLimit() + perSuffix;
        }
        guiGraphics.drawString(Minecraft.getInstance().font, limitStr, x + 4, y + 26, 0xAAAAAA, false);
    }

    public void renderTooltips(GuiGraphics guiGraphics, int leftPos, int topPos, int mouseX, int mouseY) {
        if (confirmModalEntry != null) return;

        // Tooltip on Source Button
        if (mouseX >= leftPos + 10 && mouseX <= leftPos + 66 && mouseY >= topPos + 20 && mouseY <= topPos + 34) {
            long inv = getInventoryCredits();
            long pouch = pouchCredits;
            List<Component> tooltip = List.of(
                    Component.translatable("shop.pocketodds.tooltip.source", selectedSource.name()),
                    Component.translatable("shop.pocketodds.tooltip.inv_balance", formatNumber(inv)),
                    Component.translatable("shop.pocketodds.tooltip.pouch_balance", formatNumber(pouch)),
                    Component.translatable("shop.pocketodds.tooltip.total_balance", formatNumber(inv + pouch))
            );
            guiGraphics.renderComponentTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY);
            return;
        }

        List<SyncShopCatalogS2CPacket.ClientShopEntry> filtered = getFilteredEntries();
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filtered.size());

        for (int i = startIndex; i < endIndex; i++) {
            int slotIdx = i - startIndex;
            SyncShopCatalogS2CPacket.ClientShopEntry entry = filtered.get(i);

            int col = slotIdx % 2;
            int row = slotIdx / 2;
            int cardX = leftPos + 12 + col * 98;
            int cardY = topPos + 38 + row * 40;

            // Hover over item stack
            if (mouseX >= cardX + 4 && mouseX <= cardX + 20 && mouseY >= cardY + 4 && mouseY <= cardY + 20) {
                ItemStack stack = entry.getRewardStack();
                if (!stack.isEmpty()) {
                    guiGraphics.renderTooltip(Minecraft.getInstance().font, stack, mouseX, mouseY);
                    return;
                }
            }

            // Hover over price / title
            if (mouseX >= cardX + 26 && mouseX <= cardX + 94 && mouseY >= cardY + 4 && mouseY <= cardY + 22) {
                List<Component> costTooltip = new ArrayList<>();
                costTooltip.add(Component.translatable("shop.pocketodds.modal.price", formatNumber(entry.getPriceCredits())));
                costTooltip.add(Component.translatable("shop.pocketodds.tooltip.denominations", ShopOffer.formatChipBreakdown(entry.getPriceCredits())));
                if (!entry.isAdvancementSatisfied()) {
                    costTooltip.add(Component.translatable("shop.pocketodds.tooltip.advancement_required", entry.getRequiredAdvancement()));
                }
                if (!entry.isStageSatisfied()) {
                    String req = entry.getRequiredStage();
                    if (req.startsWith("max:")) {
                        costTooltip.add(Component.translatable("shop.pocketodds.tooltip.stage_max_blocked", req.substring(4)));
                    } else {
                        costTooltip.add(Component.translatable("shop.pocketodds.tooltip.stage_min_required", req));
                    }
                }
                if (entry.getRemainingLimit() == 0) {
                    costTooltip.add(Component.translatable("shop.pocketodds.tooltip.limit_exhausted"));
                }
                if (entry.getCategoryOrdinal() == ShopCategory.CREATIVE.ordinal()) {
                    costTooltip.add(Component.translatable("shop.pocketodds.tooltip.creative_limit_info"));
                }
                guiGraphics.renderComponentTooltip(Minecraft.getInstance().font, costTooltip, mouseX, mouseY);
                return;
            }
        }
    }

    private static String formatNumber(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value).replace(',', ' ');
    }
}
