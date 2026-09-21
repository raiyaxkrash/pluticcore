package net.pocketodds.gui.casino;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.client.PocketOddsClientConfig;
import net.pocketodds.client.anim.DeckCardRenderer;
import net.pocketodds.client.anim.DiceRollRenderer;
import net.pocketodds.client.anim.RouletteWheelRenderer;
import net.pocketodds.client.anim.SlotMachineRenderer;
import net.pocketodds.gambling.slot.SlotSymbol;
import net.pocketodds.item.ChipTier;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.c2s.*;
import net.pocketodds.network.c2s.RequestCasinoSyncC2SPacket;
import net.pocketodds.network.s2c.CasinoResultSyncS2CPacket;
import net.pocketodds.network.s2c.SyncShopCatalogS2CPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PocketCasinoScreen extends AbstractContainerScreen<PocketCasinoMenu> {
    private static final ResourceLocation CASINO_BG = new ResourceLocation("pocketodds", "textures/gui/casino_main.png");

    private static final int WIDTH = 220;
    private static final int HEIGHT = 236;

    private long jackpotPool = 0L;
    private long pouchCredits = 0L;
    private long invCredits = 0L;
    private int currentStreak = 0;
    private int currentPotUnits = 0;
    private int[] rouletteHistory = new int[0];

    // Modular renderers
    private final SlotMachineRenderer slotRenderer = new SlotMachineRenderer();
    private final RouletteWheelRenderer rouletteRenderer = new RouletteWheelRenderer();
    private final DiceRollRenderer diceRenderer = new DiceRollRenderer();
    private final DeckCardRenderer deckRenderer = new DeckCardRenderer();
    private final ShopScreenTab shopTab = new ShopScreenTab(this);

    // Reward display states
    private final List<ItemStack> lastRewardItems = new ArrayList<>();
    private int rewardDisplayTick = 0;

    // Game display values
    private SlotSymbol[] displayedSymbols = new SlotSymbol[]{SlotSymbol.CHERRY, SlotSymbol.CHERRY, SlotSymbol.CHERRY};
    private int displayedRouletteNumber = 0;
    private int displayedDice1 = 1;
    private int displayedDice2 = 1;
    private Component statusMessage = Component.empty();
    private int statusColor = 0xFFFFFF;

    public PocketCasinoScreen(PocketCasinoMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
        this.inventoryLabelX = 40;
        this.inventoryLabelY = 140;
    }

    public ShopScreenTab getShopTab() {
        return shopTab;
    }

    public void updateShopCatalog(int playerTokens, List<SyncShopCatalogS2CPacket.ClientShopEntry> entries) {
        this.shopTab.updateCatalog(playerTokens, entries);
        if (menu.getCurrentCategory() == CasinoCategory.PRIZE_SHOP) {
            rebuildWidgetsForCurrentCategory();
        }
    }

    public void rebuildWidgetsForCurrentCategory() {
        rebuildCategoryWidgets();
    }

    public void skipCurrentAnimation() {
        slotRenderer.skipAnimation();
        rouletteRenderer.skipAnimation();
        diceRenderer.skipAnimation();
        deckRenderer.skipAnimation();
    }

    public boolean isAnyAnimating() {
        return slotRenderer.isSpinning() || rouletteRenderer.isSpinning() || diceRenderer.isRolling() || deckRenderer.isDealing();
    }

    public void updateCasinoState(long jackpotPool, long pouchCredits, long invCredits, int streak, int potUnits, int[] recentRouletteHistory) {
        this.jackpotPool = jackpotPool;
        this.pouchCredits = pouchCredits;
        this.invCredits = invCredits;
        this.currentStreak = streak;
        this.currentPotUnits = potUnits;
        if (recentRouletteHistory != null) {
            this.rouletteHistory = recentRouletteHistory;
        }
    }

    public void handleGameResult(CasinoResultSyncS2CPacket result) {
        if (!result.isSuccess()) {
            this.statusMessage = Component.translatable(result.getMessageKey());
            this.statusColor = 0xFF5555;
            this.lastRewardItems.clear();
            if (Minecraft.getInstance().player != null) {
                float vol = PocketOddsClientConfig.getSoundVolume();
                if (vol > 0.01F) {
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1.0F, vol));
                }
            }
            return;
        }

        this.lastRewardItems.clear();
        this.lastRewardItems.addAll(result.getRewardItems());

        int remTicks = result.getRemainingAnimTicks();
        if (PocketOddsClientConfig.CLIENT.autoSkipReplay.get() && remTicks <= 0) {
            remTicks = 0;
        }

        switch (result.getGameType()) {
            case SLOT -> {
                int[] ords = result.getSlotSymbolOrdinals();
                SlotSymbol[] finalSyms = new SlotSymbol[ords.length];
                for (int i = 0; i < ords.length; i++) {
                    finalSyms[i] = SlotSymbol.values()[ords[i]];
                }
                this.displayedSymbols = finalSyms;
                boolean isJokerRescue = (result.getMessageKey() != null && result.getMessageKey().contains("joker"));
                boolean isSkulls = result.isCurse() || (result.getMessageKey() != null && result.getMessageKey().contains("skull"))
                        || (finalSyms.length >= 3 && finalSyms[0] == SlotSymbol.SKULL && finalSyms[1] == SlotSymbol.SKULL && finalSyms[2] == SlotSymbol.SKULL);
                slotRenderer.startSpin(finalSyms, remTicks, result.isJackpot(), isJokerRescue, isSkulls, result.getWonAmount());

                if (result.isJackpot()) {
                    this.statusMessage = Component.translatable("pocketodds.gui.status.jackpot_won", result.getWonAmount());
                    this.statusColor = 0xFFD700;
                } else if (result.getWonAmount() > 0) {
                    if (menu.getBetFundingSource() == BetFundingSource.ITEM_SLOT && !lastRewardItems.isEmpty()) {
                        if (lastRewardItems.size() == 1) {
                            ItemStack first = lastRewardItems.get(0);
                            this.statusMessage = Component.translatable("pocketodds.gui.status.win_item", first.getHoverName(), first.getCount());
                        } else {
                            this.statusMessage = Component.translatable("pocketodds.gui.status.deck_cashout_multi", lastRewardItems.size());
                        }
                    } else {
                        this.statusMessage = Component.translatable("pocketodds.gui.status.win", result.getWonAmount());
                    }
                    this.statusColor = 0x55FF55;
                } else if (result.isInsured()) {
                    this.statusMessage = Component.translatable("pocketodds.gui.status.insured_loss");
                    this.statusColor = 0x55FFFF;
                } else {
                    this.statusMessage = Component.translatable("pocketodds.gui.status.loss");
                    this.statusColor = 0xAAAAAA;
                }
            }
            case ROULETTE -> {
                this.displayedRouletteNumber = result.getRouletteNumber();
                rouletteRenderer.startSpin(result.getRouletteNumber(), remTicks, result.getWonAmount(), this.rouletteHistory);

                if (result.getWonAmount() > 0) {
                    if (menu.getBetFundingSource() == BetFundingSource.ITEM_SLOT && !lastRewardItems.isEmpty()) {
                        if (lastRewardItems.size() == 1) {
                            ItemStack first = lastRewardItems.get(0);
                            this.statusMessage = Component.translatable("pocketodds.gui.status.win_item", first.getHoverName(), first.getCount());
                        } else {
                            this.statusMessage = Component.translatable("pocketodds.gui.status.deck_cashout_multi", lastRewardItems.size());
                        }
                    } else {
                        this.statusMessage = Component.translatable("pocketodds.gui.status.roulette_win", result.getRouletteNumber(), result.getWonAmount());
                    }
                    this.statusColor = 0x55FF55;
                } else {
                    this.statusMessage = Component.translatable("pocketodds.gui.status.roulette_loss", result.getRouletteNumber());
                    this.statusColor = 0xAAAAAA;
                }
            }
            case DICE -> {
                this.displayedDice1 = result.getDice1();
                this.displayedDice2 = result.getDice2();
                diceRenderer.startRoll(result.getDice1(), result.getDice2(), remTicks, result.getWonAmount());

                if (result.isJackpot()) {
                    this.statusMessage = Component.translatable("pocketodds.gui.status.jackpot_won", result.getWonAmount());
                    this.statusColor = 0xFFD700;
                } else if (result.getWonAmount() > 0) {
                    if (menu.getBetFundingSource() == BetFundingSource.ITEM_SLOT && !lastRewardItems.isEmpty()) {
                        if (lastRewardItems.size() == 1) {
                            ItemStack first = lastRewardItems.get(0);
                            this.statusMessage = Component.translatable("pocketodds.gui.status.win_item", first.getHoverName(), first.getCount());
                        } else {
                            this.statusMessage = Component.translatable("pocketodds.gui.status.deck_cashout_multi", lastRewardItems.size());
                        }
                    } else {
                        this.statusMessage = Component.translatable("pocketodds.gui.status.dice_win", (result.getDice1() + result.getDice2()), result.getWonAmount());
                    }
                    this.statusColor = 0x55FF55;
                } else {
                    this.statusMessage = Component.translatable("pocketodds.gui.status.dice_loss", (result.getDice1() + result.getDice2()));
                    this.statusColor = 0xAAAAAA;
                }
            }
            case DECK -> {
                this.currentStreak = result.getStreak();
                this.currentPotUnits = result.getPotUnits();
                deckRenderer.startDeal(result.getStreak(), result.getPotUnits(), result.getMultiplier(), result.isCurse(), result.getWonAmount(), remTicks);

                if (result.isCurse()) {
                    this.statusMessage = Component.translatable("pocketodds.gui.status.deck_curse");
                    this.statusColor = 0xFF5555;
                } else if (result.getWonAmount() > 0) {
                    if (!lastRewardItems.isEmpty()) {
                        if (lastRewardItems.size() == 1) {
                            ItemStack first = lastRewardItems.get(0);
                            this.statusMessage = Component.translatable("pocketodds.gui.status.deck_cashout_item", first.getHoverName(), first.getCount());
                        } else {
                            this.statusMessage = Component.translatable("pocketodds.gui.status.deck_cashout_multi", lastRewardItems.size());
                        }
                    } else {
                        this.statusMessage = Component.translatable("pocketodds.gui.status.deck_cashout", result.getWonAmount());
                    }
                    this.statusColor = 0x55FF55;
                } else {
                    this.statusMessage = Component.translatable("pocketodds.gui.status.deck_draw", result.getStreak(), String.format("%.2f", result.getMultiplier()));
                    this.statusColor = 0xFFFF55;
                }
            }
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        rewardDisplayTick++;
        slotRenderer.tick();
        rouletteRenderer.tick();
        diceRenderer.tick();
        deckRenderer.tick();
    }

    @Override
    protected void init() {
        super.init();
        rebuildCategoryWidgets();
        ModMessages.sendToServer(new RequestCasinoSyncC2SPacket(this.menu.containerId));
    }

    private void rebuildCategoryWidgets() {
        this.clearWidgets();
        int x = this.leftPos;
        int y = this.topPos;

        // Top Category Navigation Tabs (6 tabs, 33 width each, 2 gap)
        CasinoCategory[] categories = CasinoCategory.values();
        int tabW = 33;
        int tabGap = 2;
        int startTabX = x + 6;
        for (int i = 0; i < categories.length; i++) {
            CasinoCategory cat = categories[i];
            int tabX = startTabX + i * (tabW + tabGap);
            int tabY = y + 17;
            boolean isSelected = (menu.getCurrentCategory() == cat);
            String titleText = switch (cat) {
                case SLOTS -> "Слот";
                case ROULETTE -> "Рул.";
                case DICE -> "Кости";
                case DECK_OF_FATE -> "Колода";
                case PRIZE_SHOP -> "Магаз.";
                case JACKPOT_INFO -> "Фонд";
            };
            Component tabTitle = Component.literal(isSelected ? "§6" + titleText : titleText);
            this.addRenderableWidget(Button.builder(tabTitle, btn -> {
                menu.setCurrentCategory(cat);
                ModMessages.sendToServer(new SelectCasinoCategoryC2SPacket(menu.containerId, cat.ordinal()));
                rebuildCategoryWidgets();
            }).bounds(tabX, tabY, tabW, 14).build());
        }

        // If in PRIZE_SHOP, render shop widgets instead of betting controls
        if (menu.getCurrentCategory() == CasinoCategory.PRIZE_SHOP) {
            shopTab.addWidgets(x, y, this::addRenderableWidget);
            return;
        }

        // Betting control row 1: Funding Source & Payout Destination
        int row1Y = y + 104;
        Component fundingText = Component.literal(getFundingSourceShort(menu.getBetFundingSource()));
        this.addRenderableWidget(Button.builder(fundingText, btn -> {
            BetFundingSource next = getNextFundingSource(menu.getBetFundingSource());
            menu.setBetFundingSource(next);
            syncBetSelection();
            rebuildCategoryWidgets();
        }).bounds(x + 7, row1Y, 70, 14).build());

        Component payoutText = Component.literal(menu.getPayoutDestination() == PayoutDestination.INVENTORY ? "Вып: Инв" : "Вып: Мешок");
        this.addRenderableWidget(Button.builder(payoutText, btn -> {
            PayoutDestination next = menu.getPayoutDestination() == PayoutDestination.INVENTORY ? PayoutDestination.POUCH : PayoutDestination.INVENTORY;
            menu.setPayoutDestination(next);
            syncBetSelection();
            rebuildCategoryWidgets();
        }).bounds(x + 79, row1Y, 60, 14).build());

        // Betting control row 2: Chip Tier and Bet Count (if not ITEM_SLOT)
        int row2Y = y + 120;
        if (menu.getBetFundingSource() != BetFundingSource.ITEM_SLOT) {
            Component tierText = Component.literal(menu.getSelectedChipTier().getColorCode() + menu.getSelectedChipTier().getId().toUpperCase());
            this.addRenderableWidget(Button.builder(tierText, btn -> {
                menu.setSelectedChipTier(menu.getSelectedChipTier().next());
                syncBetSelection();
                rebuildCategoryWidgets();
            }).bounds(x + 7, row2Y, 40, 14).build());

            // Bet Count controls: -1, count, +1, +8
            this.addRenderableWidget(Button.builder(Component.literal("-1"), btn -> {
                if (menu.getBetCount() > 1) {
                    menu.setBetCount(menu.getBetCount() - 1);
                    syncBetSelection();
                    rebuildCategoryWidgets();
                }
            }).bounds(x + 49, row2Y, 16, 14).build());

            this.addRenderableWidget(Button.builder(Component.literal("+1"), btn -> {
                if (menu.getBetCount() < 64) {
                    menu.setBetCount(menu.getBetCount() + 1);
                    syncBetSelection();
                    rebuildCategoryWidgets();
                }
            }).bounds(x + 67, row2Y, 16, 14).build());

            this.addRenderableWidget(Button.builder(Component.literal("+8"), btn -> {
                menu.setBetCount(Math.min(64, menu.getBetCount() + 8));
                syncBetSelection();
                rebuildCategoryWidgets();
            }).bounds(x + 85, row2Y, 18, 14).build());
        }

        // Game specific Action Buttons & Skip buttons
        int actionX = x + 142;
        int actionY = y + 104;

        switch (menu.getCurrentCategory()) {
            case SLOTS -> {
                this.addRenderableWidget(Button.builder(Component.translatable("pocketodds.gui.action.spin"), btn -> {
                    if (!isAnyAnimating()) {
                        ModMessages.sendToServer(new StartSlotSpinC2SPacket(menu.containerId, UUID.randomUUID()));
                    }
                }).bounds(actionX, actionY, 48, 30).build());

                this.addRenderableWidget(Button.builder(Component.literal("⏭"), btn -> {
                    skipCurrentAnimation();
                }).bounds(actionX + 50, actionY, 21, 30).build());
            }
            case ROULETTE -> {
                this.addRenderableWidget(Button.builder(Component.literal("§cКрасное"), btn -> {
                    menu.setRouletteBetType(RouletteBetType.RED);
                    syncBetSelection();
                }).bounds(x + 12, y + 80, 50, 16).build());

                this.addRenderableWidget(Button.builder(Component.literal("§8Чёрное"), btn -> {
                    menu.setRouletteBetType(RouletteBetType.BLACK);
                    syncBetSelection();
                }).bounds(x + 66, y + 80, 50, 16).build());

                this.addRenderableWidget(Button.builder(Component.literal("§aЗеро"), btn -> {
                    menu.setRouletteBetType(RouletteBetType.ZERO);
                    syncBetSelection();
                }).bounds(x + 120, y + 80, 44, 16).build());

                this.addRenderableWidget(Button.builder(Component.literal("⏭"), btn -> {
                    skipCurrentAnimation();
                }).bounds(actionX, actionY, 21, 30).build());

                this.addRenderableWidget(Button.builder(Component.translatable("pocketodds.gui.action.spin"), btn -> {
                    if (!isAnyAnimating()) {
                        ModMessages.sendToServer(new StartRouletteSpinC2SPacket(menu.containerId, menu.getRouletteBetType().ordinal(), UUID.randomUUID()));
                    }
                }).bounds(actionX + 23, actionY, 48, 30).build());
            }
            case DICE -> {
                this.addRenderableWidget(Button.builder(Component.translatable("pocketodds.gui.action.roll"), btn -> {
                    if (!isAnyAnimating()) {
                        ModMessages.sendToServer(new RollDiceC2SPacket(menu.containerId, UUID.randomUUID()));
                    }
                }).bounds(actionX, actionY, 48, 30).build());

                this.addRenderableWidget(Button.builder(Component.literal("⏭"), btn -> {
                    skipCurrentAnimation();
                }).bounds(actionX + 50, actionY, 21, 30).build());
            }
            case DECK_OF_FATE -> {
                this.addRenderableWidget(Button.builder(Component.translatable("pocketodds.gui.action.draw"), btn -> {
                    if (!isAnyAnimating()) {
                        ModMessages.sendToServer(new DrawDeckCardC2SPacket(menu.containerId, UUID.randomUUID()));
                    }
                }).bounds(actionX, actionY, 48, 14).build());

                this.addRenderableWidget(Button.builder(Component.literal("⏭"), btn -> {
                    skipCurrentAnimation();
                }).bounds(actionX + 50, actionY, 21, 14).build());

                this.addRenderableWidget(Button.builder(Component.translatable("pocketodds.gui.action.cashout"), btn -> {
                    if (!isAnyAnimating()) {
                        ModMessages.sendToServer(new CashOutDeckC2SPacket(menu.containerId, UUID.randomUUID()));
                    }
                }).bounds(actionX, actionY + 16, 71, 14).build());
            }
            case JACKPOT_INFO -> {
                // Info only tab
            }
            case PRIZE_SHOP -> {
                // Handled above
            }
        }
    }

    private String getFundingSourceShort(BetFundingSource src) {
        return switch (src) {
            case INVENTORY -> "Инвентарь";
            case POUCH_ONLY -> "Мешочек";
            case POUCH_THEN_INVENTORY -> "Мешок+Инв";
            case INVENTORY_THEN_POUCH -> "Инв+Мешок";
            case ITEM_SLOT -> "Предмет";
        };
    }

    private BetFundingSource getNextFundingSource(BetFundingSource cur) {
        return switch (cur) {
            case INVENTORY -> BetFundingSource.POUCH_ONLY;
            case POUCH_ONLY -> BetFundingSource.POUCH_THEN_INVENTORY;
            case POUCH_THEN_INVENTORY -> BetFundingSource.ITEM_SLOT;
            case ITEM_SLOT -> BetFundingSource.INVENTORY;
            default -> BetFundingSource.INVENTORY;
        };
    }

    private void syncBetSelection() {
        ModMessages.sendToServer(new UpdateBetSelectionC2SPacket(
                menu.containerId,
                menu.getBetFundingSource().ordinal(),
                menu.getSelectedChipTier().ordinal(),
                menu.getBetCount(),
                menu.getRouletteBetType().ordinal(),
                menu.getPayoutDestination().ordinal()
        ));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        if (menu.getCurrentCategory() == CasinoCategory.PRIZE_SHOP) {
            shopTab.renderTooltips(guiGraphics, this.leftPos, this.topPos, mouseX, mouseY);
        } else {
            renderRewardTooltips(guiGraphics, mouseX, mouseY);
        }
    }

    private void renderRewardTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (lastRewardItems.isEmpty()) return;
        int x = this.leftPos;
        int y = this.topPos;
        if (mouseX >= x + 13 && mouseX <= x + 31) {
            int total = lastRewardItems.size();
            int pages = (total + 1) / 2;
            int page = (rewardDisplayTick / 40) % Math.max(1, pages);
            int idx1 = page * 2;
            int idx2 = page * 2 + 1;

            if (mouseY >= y + 186 && mouseY <= y + 204 && idx1 < total) {
                guiGraphics.renderTooltip(this.font, lastRewardItems.get(idx1), mouseX, mouseY);
            } else if (mouseY >= y + 207 && mouseY <= y + 225 && idx2 < total) {
                guiGraphics.renderTooltip(this.font, lastRewardItems.get(idx2), mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        int x = this.leftPos;
        int y = this.topPos;

        // Blit full custom texture (220x236)
        guiGraphics.blit(CASINO_BG, x, y, 0, 0, this.imageWidth, this.imageHeight, 256, 256);

        if (menu.getCurrentCategory() != CasinoCategory.PRIZE_SHOP) {
            // Item Bet Slot Box (x: 14, y: 154)
            guiGraphics.fill(x + 13, y + 153, x + 31, y + 171, 0xFFC69C3D);
            guiGraphics.fill(x + 14, y + 154, x + 30, y + 170, 0xFF0E0D10);

            // Reward Slot Boxes (x: 14, y: 186 and y: 207)
            if (!lastRewardItems.isEmpty()) {
                guiGraphics.fill(x + 13, y + 186, x + 31, y + 204, 0xFF55FF55);
                guiGraphics.fill(x + 14, y + 187, x + 30, y + 203, 0xFF0E0D10);
                guiGraphics.fill(x + 13, y + 207, x + 31, y + 225, 0xFF55FF55);
                guiGraphics.fill(x + 14, y + 208, x + 30, y + 224, 0xFF0E0D10);
            } else {
                guiGraphics.fill(x + 13, y + 186, x + 31, y + 204, 0xFF2E2B35);
                guiGraphics.fill(x + 14, y + 187, x + 30, y + 203, 0xFF121114);
                guiGraphics.fill(x + 13, y + 207, x + 31, y + 225, 0xFF2E2B35);
                guiGraphics.fill(x + 14, y + 208, x + 30, y + 224, 0xFF121114);
            }

            // Render reward items
            if (!lastRewardItems.isEmpty()) {
                int total = lastRewardItems.size();
                int pages = (total + 1) / 2;
                int page = (rewardDisplayTick / 40) % Math.max(1, pages);
                int idx1 = page * 2;
                int idx2 = page * 2 + 1;

                if (idx1 < total) {
                    ItemStack item1 = lastRewardItems.get(idx1);
                    guiGraphics.renderItem(item1, x + 14, y + 187);
                    guiGraphics.renderItemDecorations(this.font, item1, x + 14, y + 187);
                }
                if (idx2 < total) {
                    ItemStack item2 = lastRewardItems.get(idx2);
                    guiGraphics.renderItem(item2, x + 14, y + 208);
                    guiGraphics.renderItemDecorations(this.font, item2, x + 14, y + 208);
                }
            }

            // Player inventory slot boxes (x: 40, y: 150)
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    int sx = x + 40 + col * 18;
                    int sy = y + 150 + row * 18;
                    guiGraphics.fill(sx, sy, sx + 18, sy + 18, 0xFF2E2B35);
                    guiGraphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF121114);
                }
            }
            for (int col = 0; col < 9; ++col) {
                int sx = x + 40 + col * 18;
                int sy = y + 208;
                guiGraphics.fill(sx, sy, sx + 18, sy + 18, 0xFF2E2B35);
                guiGraphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF121114);
            }
        }

        // Render Active Game Content
        renderGameDisplay(guiGraphics, x, y, mouseX, mouseY, partialTick);
    }

    private void renderGameDisplay(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTick) {
        switch (menu.getCurrentCategory()) {
            case SLOTS -> slotRenderer.render(guiGraphics, x, y, partialTick);
            case ROULETTE -> rouletteRenderer.render(guiGraphics, x, y, partialTick);
            case DICE -> diceRenderer.render(guiGraphics, x, y, partialTick);
            case DECK_OF_FATE -> deckRenderer.render(guiGraphics, x, y, partialTick);
            case PRIZE_SHOP -> shopTab.render(guiGraphics, x, y, mouseX, mouseY, partialTick);
            case JACKPOT_INFO -> {
                guiGraphics.drawString(this.font, Component.literal("★ ДЖЕКПОТ КАЗИНО ★"), x + 55, y + 42, 0xFFD700, false);
                guiGraphics.drawString(this.font, Component.literal("Текущий фонд: §6" + jackpotPool + " §eCR"), x + 35, y + 58, 0xFFFFFF, false);
                guiGraphics.drawString(this.font, Component.literal("5% от всех ставок пополняют фонд!"), x + 25, y + 74, 0xAAAAAA, false);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Header
        guiGraphics.drawString(this.font, this.title, 8, 4, 0xFFD4AF37, false);

        // Header balance display (Pouch and Inv)
        String pouchStr = "§6" + pouchCredits + " §eCR";
        guiGraphics.drawString(this.font, pouchStr, this.imageWidth - 8 - this.font.width(pouchStr), 4, 0xFFFFFF, false);

        if (menu.getCurrentCategory() == CasinoCategory.PRIZE_SHOP) {
            guiGraphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x888888, false);
            return;
        }

        // Status message below game area
        if (statusMessage != null && !statusMessage.getString().isEmpty()) {
            guiGraphics.drawString(this.font, statusMessage, 10, 89, statusColor, false);
        }

        // Bet count indicator
        if (menu.getBetFundingSource() != BetFundingSource.ITEM_SLOT) {
            String betLabel = "x" + menu.getBetCount();
            guiGraphics.drawString(this.font, betLabel, 106, 123, 0xFFFFFF, false);
        }

        // Inventory label
        guiGraphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x888888, false);
        guiGraphics.drawString(this.font, Component.literal("Ставка"), 8, 142, 0x888888, false);

        // Reward label
        if (!lastRewardItems.isEmpty()) {
            String countSuffix = lastRewardItems.size() > 2 ? " (" + lastRewardItems.size() + ")" : "";
            guiGraphics.drawString(this.font, Component.literal(Component.translatable("pocketodds.gui.label.rewards").getString() + countSuffix), 8, 175, 0xFFD4AF37, false);
        } else {
            guiGraphics.drawString(this.font, Component.translatable("pocketodds.gui.label.rewards"), 8, 175, 0x555555, false);
        }
    }
}