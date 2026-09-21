package net.pocketodds.client.anim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.pocketodds.client.PocketOddsClientConfig;

import java.util.Random;

public class DiceRollRenderer {
    private final Random random = new Random();
    private boolean rolling = false;
    private int totalAnimTicks = 20;
    private int remainingAnimTicks = 0;

    private int targetD1 = 1;
    private int targetD2 = 1;
    private int currentD1 = 1;
    private int currentD2 = 1;
    private boolean d1Stopped = true;
    private boolean d2Stopped = true;

    private boolean isDouble = false;
    private boolean isLuckySeven = false;
    private long wonAmount = 0L;

    public void startRoll(int d1, int d2, int animTicks, long winAmount) {
        this.targetD1 = d1;
        this.targetD2 = d2;
        this.wonAmount = winAmount;
        this.isDouble = (d1 == d2);
        this.isLuckySeven = (d1 + d2 == 7);

        if (!PocketOddsClientConfig.areAnimationsEnabled()) {
            this.currentD1 = d1;
            this.currentD2 = d2;
            this.d1Stopped = true;
            this.d2Stopped = true;
            this.rolling = false;
            this.remainingAnimTicks = 0;
            return;
        }

        double speedMult = PocketOddsClientConfig.getAnimationSpeed();
        this.totalAnimTicks = Math.max(10, (int) Math.round((animTicks <= 0 ? 20 : animTicks) / speedMult));
        this.remainingAnimTicks = this.totalAnimTicks;
        this.rolling = true;
        this.d1Stopped = false;
        this.d2Stopped = false;
    }

    public void skipAnimation() {
        if (rolling) {
            this.rolling = false;
            this.remainingAnimTicks = 0;
            this.currentD1 = targetD1;
            this.currentD2 = targetD2;
            this.d1Stopped = true;
            this.d2Stopped = true;
            playFinishSound();
        }
    }

    public boolean isRolling() {
        return rolling;
    }

    public void tick() {
        if (!rolling) return;

        remainingAnimTicks--;

        int stopD1At = (int) (totalAnimTicks * 0.35);
        if (remainingAnimTicks <= stopD1At && !d1Stopped) {
            d1Stopped = true;
            currentD1 = targetD1;
            playDieClackSound();
        } else if (!d1Stopped) {
            currentD1 = 1 + random.nextInt(6);
        }

        if (remainingAnimTicks <= 0 && !d2Stopped) {
            d2Stopped = true;
            currentD2 = targetD2;
            playDieClackSound();
        } else if (!d2Stopped) {
            currentD2 = 1 + random.nextInt(6);
        }

        if (remainingAnimTicks > 0 && remainingAnimTicks % 2 == 0) {
            playRollSound();
        }

        if (remainingAnimTicks <= 0) {
            rolling = false;
            currentD1 = targetD1;
            currentD2 = targetD2;
            playFinishSound();
        }
    }

    public void render(GuiGraphics guiGraphics, int x, int y, float partialTick) {
        int d1X = x + 55;
        int d2X = x + 120;
        int dieY = y + 42;

        // Render wobble offset if rolling
        int wobbleY1 = (!d1Stopped && rolling) ? random.nextInt(3) - 1 : 0;
        int wobbleY2 = (!d2Stopped && rolling) ? random.nextInt(3) - 1 : 0;

        renderDie(guiGraphics, d1X, dieY + wobbleY1, currentD1, d1Stopped);
        renderDie(guiGraphics, d2X, dieY + wobbleY2, currentD2, d2Stopped);

        // Sum and effects
        int sum = currentD1 + currentD2;
        String sumStr = "Сумма: §e" + sum;
        if (!rolling) {
            if (isDouble) {
                sumStr += " §6★ ДУБЛЬ! ★";
            } else if (isLuckySeven) {
                sumStr += " §d★ LUCKY 7! ★";
            }
        }
        int strW = Minecraft.getInstance().font.width(sumStr);
        guiGraphics.drawString(Minecraft.getInstance().font, Component.literal(sumStr), x + 105 - strW / 2, y + 80, 0xFFFFFF, false);
    }

    private void renderDie(GuiGraphics guiGraphics, int dx, int dy, int val, boolean stopped) {
        // Brass / Dark frame
        guiGraphics.fill(dx - 1, dy - 1, dx + 31, dy + 31, stopped ? 0xFFC69C3D : 0xFF888888);
        guiGraphics.fill(dx, dy, dx + 30, dy + 30, 0xFFF5F5F0);
        guiGraphics.fill(dx + 2, dy + 2, dx + 28, dy + 28, 0xFFFFFFFF);

        // Render dots according to dice face value (1 to 6)
        renderDots(guiGraphics, dx, dy, val);
    }

    private void renderDots(GuiGraphics guiGraphics, int dx, int dy, int val) {
        int dotColor = 0xFF18171B;
        int cx = dx + 15;
        int cy = dy + 15;
        int l = dx + 8;
        int r = dx + 22;
        int t = dy + 8;
        int b = dy + 22;

        switch (val) {
            case 1 -> drawDot(guiGraphics, cx, cy, dotColor);
            case 2 -> {
                drawDot(guiGraphics, l, t, dotColor);
                drawDot(guiGraphics, r, b, dotColor);
            }
            case 3 -> {
                drawDot(guiGraphics, l, t, dotColor);
                drawDot(guiGraphics, cx, cy, dotColor);
                drawDot(guiGraphics, r, b, dotColor);
            }
            case 4 -> {
                drawDot(guiGraphics, l, t, dotColor);
                drawDot(guiGraphics, r, t, dotColor);
                drawDot(guiGraphics, l, b, dotColor);
                drawDot(guiGraphics, r, b, dotColor);
            }
            case 5 -> {
                drawDot(guiGraphics, l, t, dotColor);
                drawDot(guiGraphics, r, t, dotColor);
                drawDot(guiGraphics, cx, cy, dotColor);
                drawDot(guiGraphics, l, b, dotColor);
                drawDot(guiGraphics, r, b, dotColor);
            }
            case 6 -> {
                drawDot(guiGraphics, l, t, dotColor);
                drawDot(guiGraphics, r, t, dotColor);
                drawDot(guiGraphics, l, cy, dotColor);
                drawDot(guiGraphics, r, cy, dotColor);
                drawDot(guiGraphics, l, b, dotColor);
                drawDot(guiGraphics, r, b, dotColor);
            }
        }
    }

    private void drawDot(GuiGraphics guiGraphics, int x, int y, int color) {
        guiGraphics.fill(x - 1, y - 1, x + 2, y + 2, color);
    }

    private void playRollSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.DISPENSER_DISPENSE, 1.6F + random.nextFloat() * 0.3F, vol * 0.3F));
        }
    }

    private void playDieClackSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.WOODEN_BUTTON_CLICK_ON, 1.3F + random.nextFloat() * 0.2F, vol * 0.6F));
        }
    }

    private void playFinishSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            if (isDouble || isLuckySeven) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.4F, vol * 0.8F));
            } else if (wonAmount > 0) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.get(), 1.2F, vol * 0.7F));
            } else {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.get(), 0.8F, vol * 0.6F));
            }
        }
    }
}
