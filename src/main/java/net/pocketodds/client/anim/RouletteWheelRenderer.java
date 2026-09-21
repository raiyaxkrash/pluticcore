package net.pocketodds.client.anim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.pocketodds.client.PocketOddsClientConfig;

import java.util.Random;

public class RouletteWheelRenderer {
    private final Random random = new Random();
    private boolean spinning = false;
    private int totalAnimTicks = 25;
    private int remainingAnimTicks = 0;

    private int targetNumber = 0;
    private int currentDisplayNumber = 0;
    private double wheelAngle = 0.0;
    private double ballAngle = 0.0;
    private long wonAmount = 0L;
    private int[] history = new int[0];

    public void startSpin(int targetNum, int animTicks, long winAmount, int[] recentHistory) {
        this.targetNumber = targetNum;
        this.wonAmount = winAmount;
        if (recentHistory != null) {
            this.history = recentHistory;
        }

        if (!PocketOddsClientConfig.areAnimationsEnabled()) {
            this.currentDisplayNumber = targetNum;
            this.spinning = false;
            this.remainingAnimTicks = 0;
            return;
        }

        double speedMult = PocketOddsClientConfig.getAnimationSpeed();
        this.totalAnimTicks = Math.max(10, (int) Math.round((animTicks <= 0 ? 25 : animTicks) / speedMult));
        this.remainingAnimTicks = this.totalAnimTicks;
        this.spinning = true;
    }

    public void skipAnimation() {
        if (spinning) {
            this.spinning = false;
            this.remainingAnimTicks = 0;
            this.currentDisplayNumber = targetNumber;
            playFinishSound();
        }
    }

    public boolean isSpinning() {
        return spinning;
    }

    public void tick() {
        if (!spinning) return;

        remainingAnimTicks--;

        // Wheel rotates clockwise, ball counter-clockwise
        double progress = 1.0 - ((double) remainingAnimTicks / (double) totalAnimTicks);
        double speed = Math.max(0.05, 1.0 - progress);

        wheelAngle += speed * 0.4;
        ballAngle -= speed * 0.8;

        currentDisplayNumber = random.nextInt(37);

        // Sound throttled
        if (remainingAnimTicks > 0 && remainingAnimTicks % 3 == 0) {
            playBallTickSound();
        }

        if (remainingAnimTicks <= 0) {
            spinning = false;
            currentDisplayNumber = targetNumber;
            playFinishSound();
        }
    }

    public void render(GuiGraphics guiGraphics, int x, int y, float partialTick) {
        int centerX = x + 105;
        int centerY = y + 54;

        // Draw Roulette Wheel disk
        guiGraphics.fill(centerX - 30, centerY - 20, centerX + 30, centerY + 20, 0xFF2A2631);
        guiGraphics.fill(centerX - 29, centerY - 19, centerX + 29, centerY + 19, 0xFF18171B);

        // Render current number
        String colorCode = (currentDisplayNumber == 0) ? "§a" : (currentDisplayNumber % 2 == 1 ? "§c" : "§8");
        String displayStr = colorCode + "[" + currentDisplayNumber + "]§r";
        int strW = Minecraft.getInstance().font.width(displayStr);
        guiGraphics.drawString(Minecraft.getInstance().font, displayStr, centerX - strW / 2, centerY - 4, 0xFFFFFF, false);

        // Render wheel status
        if (spinning) {
            guiGraphics.drawString(Minecraft.getInstance().font, Component.literal("§eШарик вращается..."), x + 20, y + 80, 0xFFFF55, false);
        } else {
            String resText = (targetNumber == 0) ? "§a0 Зеро" : (targetNumber % 2 == 1 ? "§c" + targetNumber + " Красное" : "§8" + targetNumber + " Чёрное");
            guiGraphics.drawString(Minecraft.getInstance().font, Component.literal("Выпало: " + resText), x + 20, y + 80, 0xFFFFFF, false);
        }

        // Render history chips (last numbers)
        renderHistoryChips(guiGraphics, x + 140, y + 40);
    }

    private void renderHistoryChips(GuiGraphics guiGraphics, int startX, int startY) {
        if (history == null || history.length == 0) return;
        guiGraphics.drawString(Minecraft.getInstance().font, Component.literal("§7История:"), startX, startY - 10, 0x888888, false);

        int max = Math.min(5, history.length);
        for (int i = 0; i < max; i++) {
            int num = history[i];
            int chipColor = (num == 0) ? 0xFF2E7D32 : (num % 2 == 1 ? 0xFFC62828 : 0xFF212121);
            int cx = startX + i * 14;
            int cy = startY + 2;

            guiGraphics.fill(cx, cy, cx + 12, cy + 12, chipColor);
            String nStr = String.valueOf(num);
            int nw = Minecraft.getInstance().font.width(nStr);
            guiGraphics.drawString(Minecraft.getInstance().font, nStr, cx + 6 - nw / 2, cy + 2, 0xFFFFFF, false);
        }
    }

    private void playBallTickSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4F + random.nextFloat() * 0.4F, vol * 0.4F));
        }
    }

    private void playFinishSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            if (wonAmount > 0) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.2F, vol * 0.8F));
            } else {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_SNARE.get(), 0.9F, vol * 0.6F));
            }
        }
    }
}
