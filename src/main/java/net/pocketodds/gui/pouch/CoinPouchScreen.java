package net.pocketodds.gui.pouch;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.pocketodds.item.ChipTier;
import net.pocketodds.network.ModMessages;
import net.pocketodds.network.c2s.CoinPouchActionC2SPacket;
import net.pocketodds.service.CoinPouchService;

import java.util.UUID;

public class CoinPouchScreen extends AbstractContainerScreen<CoinPouchMenu> {
    private static final ResourceLocation POUCH_BG = new ResourceLocation("pocketodds", "textures/gui/pouch_bg.png");

    private long copperCount = 0L;
    private long goldCount = 0L;
    private long diamondCount = 0L;
    private long netheriteCount = 0L;
    private long totalCredits = 0L;

    public CoinPouchScreen(CoinPouchMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
        readInitialData();
    }

    private void readInitialData() {
        ItemStack pouch = menu.getPouchStack();
        if (pouch != null && !pouch.isEmpty()) {
            this.copperCount = CoinPouchService.getChipCount(pouch, ChipTier.COPPER);
            this.goldCount = CoinPouchService.getChipCount(pouch, ChipTier.GOLD);
            this.diamondCount = CoinPouchService.getChipCount(pouch, ChipTier.DIAMOND);
            this.netheriteCount = CoinPouchService.getChipCount(pouch, ChipTier.NETHERITE);
            this.totalCredits = CoinPouchService.getTotalCredits(pouch);
        }
    }

    public void updatePouchData(UUID pouchUUID, long copper, long gold, long diamond, long netherite, long totalCredits) {
        this.copperCount = copper;
        this.goldCount = gold;
        this.diamondCount = diamond;
        this.netheriteCount = netherite;
        this.totalCredits = totalCredits;
    }

    @Override
    protected void init() {
        super.init();

        int x = this.leftPos;
        int y = this.topPos;

        // Row 1: Copper
        addTierButtons(x + 98, y + 17, ChipTier.COPPER);
        // Row 2: Gold
        addTierButtons(x + 98, y + 31, ChipTier.GOLD);
        // Row 3: Diamond
        addTierButtons(x + 98, y + 45, ChipTier.DIAMOND);
        // Row 4: Netherite
        addTierButtons(x + 98, y + 59, ChipTier.NETHERITE);

        // Global buttons: Deposit All & Withdraw All
        this.addRenderableWidget(Button.builder(Component.translatable("pocketodds.gui.pouch.deposit_all"), btn -> {
            ModMessages.sendToServer(new CoinPouchActionC2SPacket(this.menu.containerId, CoinPouchActionC2SPacket.ACTION_DEPOSIT_ALL, 0, 0));
        }).bounds(x + 7, y + 68, 42, 13).build());

        this.addRenderableWidget(Button.builder(Component.translatable("pocketodds.gui.pouch.withdraw_all"), btn -> {
            ModMessages.sendToServer(new CoinPouchActionC2SPacket(this.menu.containerId, CoinPouchActionC2SPacket.ACTION_WITHDRAW_ALL, 0, 0));
        }).bounds(x + 51, y + 68, 44, 13).build());
    }

    private void addTierButtons(int startX, int startY, ChipTier tier) {
        int tOrdinal = tier.ordinal();
        // Deposit 1
        this.addRenderableWidget(Button.builder(Component.literal("+1"), btn -> {
            ModMessages.sendToServer(new CoinPouchActionC2SPacket(this.menu.containerId, CoinPouchActionC2SPacket.ACTION_DEPOSIT_TIER, tOrdinal, 1));
        }).bounds(startX, startY, 17, 12).build());

        // Deposit 8
        this.addRenderableWidget(Button.builder(Component.literal("+8"), btn -> {
            ModMessages.sendToServer(new CoinPouchActionC2SPacket(this.menu.containerId, CoinPouchActionC2SPacket.ACTION_DEPOSIT_TIER, tOrdinal, 8));
        }).bounds(startX + 18, startY, 17, 12).build());

        // Withdraw 1
        this.addRenderableWidget(Button.builder(Component.literal("-1"), btn -> {
            ModMessages.sendToServer(new CoinPouchActionC2SPacket(this.menu.containerId, CoinPouchActionC2SPacket.ACTION_WITHDRAW_TIER, tOrdinal, 1));
        }).bounds(startX + 37, startY, 17, 12).build());

        // Withdraw 8
        this.addRenderableWidget(Button.builder(Component.literal("-8"), btn -> {
            ModMessages.sendToServer(new CoinPouchActionC2SPacket(this.menu.containerId, CoinPouchActionC2SPacket.ACTION_WITHDRAW_TIER, tOrdinal, 8));
        }).bounds(startX + 55, startY, 17, 12).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        int x = this.leftPos;
        int y = this.topPos;

        // Blit full pouch GUI texture (176x166)
        guiGraphics.blit(POUCH_BG, x, y, 0, 0, this.imageWidth, this.imageHeight, 176, 166);

        // Player inventory slot boxes background
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                int sx = x + 7 + col * 18;
                int sy = y + 83 + row * 18;
                guiGraphics.fill(sx, sy, sx + 18, sy + 18, 0xFF2E2B35);
                guiGraphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF121114);
            }
        }
        for (int col = 0; col < 9; ++col) {
            int sx = x + 7 + col * 18;
            int sy = y + 141;
            guiGraphics.fill(sx, sy, sx + 18, sy + 18, 0xFF2E2B35);
            guiGraphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF121114);
        }

        // Render chips preview and counts
        renderChipRow(guiGraphics, x + 8, y + 17, ChipTier.COPPER, copperCount);
        renderChipRow(guiGraphics, x + 8, y + 31, ChipTier.GOLD, goldCount);
        renderChipRow(guiGraphics, x + 8, y + 45, ChipTier.DIAMOND, diamondCount);
        renderChipRow(guiGraphics, x + 8, y + 59, ChipTier.NETHERITE, netheriteCount);
    }

    private void renderChipRow(GuiGraphics guiGraphics, int x, int y, ChipTier tier, long count) {
        guiGraphics.renderItem(new ItemStack(tier.getItem()), x, y - 2);
        String label = tier.getColorCode() + count + "§r";
        guiGraphics.drawString(this.font, label, x + 20, y + 2, 0xFFFFFF, false);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, 8, 5, 0xFFD4AF37, false);
        String creditsStr = "§6" + totalCredits + " §eCR";
        int crWidth = this.font.width(creditsStr);
        guiGraphics.drawString(this.font, creditsStr, this.imageWidth - 8 - crWidth, 5, 0xFFFFFF, false);
        guiGraphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x888888, false);
    }
}