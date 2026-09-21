package net.pocketodds.client.anim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.pocketodds.client.PocketOddsClientConfig;

import java.util.Random;

public class DeckCardRenderer {
    private final Random random = new Random();
    private boolean dealing = false;
    private int totalAnimTicks = 20;
    private int remainingAnimTicks = 0;

    private int streak = 0;
    private int potUnits = 0;
    private double multiplier = 1.0;
    private boolean isCurse = false;
    private long wonAmount = 0L;

    public void startDeal(int streak, int potUnits, double multiplier, boolean isCurse, long wonAmount, int animTicks) {
        this.streak = streak;
        this.potUnits = potUnits;
        this.multiplier = multiplier;
        this.isCurse = isCurse;
        this.wonAmount = wonAmount;

        if (!PocketOddsClientConfig.areAnimationsEnabled()) {
            this.dealing = false;
            this.remainingAnimTicks = 0;
            return;
        }

        double speedMult = PocketOddsClientConfig.getAnimationSpeed();
        this.totalAnimTicks = Math.max(10, (int) Math.round((animTicks <= 0 ? 20 : animTicks) / speedMult));
        this.remainingAnimTicks = this.totalAnimTicks;
        this.dealing = true;
        playCardDrawSound();
    }

    public void skipAnimation() {
        if (dealing) {
            this.dealing = false;
            this.remainingAnimTicks = 0;
            playFinishSound();
        }
    }

    public boolean isDealing() {
        return dealing;
    }

    public void tick() {
        if (!dealing) return;
        remainingAnimTicks--;
        if (remainingAnimTicks == totalAnimTicks / 2) {
            playCardFlipSound();
        }
        if (remainingAnimTicks <= 0) {
            dealing = false;
            playFinishSound();
        }
    }

    public void render(GuiGraphics guiGraphics, int x, int y, float partialTick) {
        int cardCenterX = x + 140;
        int cardCenterY = y + 54;
        int cardW = 32;
        int cardH = 46;

        // Left info display
        guiGraphics.drawString(Minecraft.getInstance().font, Component.literal("Серия карт: §6" + streak), x + 20, y + 42, 0xFFD4AF37, false);
        guiGraphics.drawString(Minecraft.getInstance().font, Component.literal("Банк: §e" + potUnits + " ед."), x + 20, y + 56, 0xFFFFFF, false);
        guiGraphics.drawString(Minecraft.getInstance().font, Component.literal("Множитель: §b" + String.format("%.2f", multiplier) + "x"), x + 20, y + 70, 0x55FFFF, false);

        // 3D Flip Scale calculation
        double progress = dealing ? (1.0 - ((double) remainingAnimTicks / (double) totalAnimTicks)) : 1.0;
        double flipScale;
        boolean showFace;

        if (progress < 0.5) {
            flipScale = 1.0 - (progress * 2.0); // 1.0 -> 0.0
            showFace = false;
        } else {
            flipScale = (progress - 0.5) * 2.0; // 0.0 -> 1.0
            showFace = true;
        }

        int currentW = Math.max(2, (int) Math.round(cardW * flipScale));
        int cx = cardCenterX - currentW / 2;
        int cy = cardCenterY - cardH / 2;

        if (!showFace) {
            // Card Back (Ruby/Gold Pattern)
            guiGraphics.fill(cx - 1, cy - 1, cx + currentW + 1, cy + cardH + 1, 0xFFC69C3D);
            guiGraphics.fill(cx, cy, cx + currentW, cy + cardH, 0xFF6B1B29);
            guiGraphics.fill(cx + 2, cy + 2, cx + currentW - 2, cy + cardH - 2, 0xFF8B263E);
        } else {
            // Card Face (Fortune / Curse)
            int border = isCurse ? 0xFF880000 : 0xFFC69C3D;
            int bg = isCurse ? 0xFF2A1518 : 0xFFFAF8EE;
            guiGraphics.fill(cx - 1, cy - 1, cx + currentW + 1, cy + cardH + 1, border);
            guiGraphics.fill(cx, cy, cx + currentW, cy + cardH, bg);

            if (currentW >= 16) {
                if (isCurse) {
                    String skullStr = "☠";
                    int sw = Minecraft.getInstance().font.width(skullStr);
                    guiGraphics.drawString(Minecraft.getInstance().font, skullStr, cardCenterX - sw / 2, cardCenterY - 4, 0xFFFF3333, false);
                } else {
                    String starStr = "★";
                    int sw = Minecraft.getInstance().font.width(starStr);
                    guiGraphics.drawString(Minecraft.getInstance().font, starStr, cardCenterX - sw / 2, cardCenterY - 4, 0xFFD4AF37, false);
                }
            }
        }
    }

    private void playCardDrawSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.BOOK_PAGE_TURN, 1.4F + random.nextFloat() * 0.2F, vol * 0.6F));
        }
    }

    private void playCardFlipSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.UI_BUTTON_CLICK.get(), 1.6F, vol * 0.5F));
        }
    }

    private void playFinishSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            if (isCurse) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.WITHER_HURT, 1.2F, vol * 0.6F));
            } else if (wonAmount > 0) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.3F, vol * 0.8F));
            } else {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.get(), 1.2F, vol * 0.7F));
            }
        }
    }
}
