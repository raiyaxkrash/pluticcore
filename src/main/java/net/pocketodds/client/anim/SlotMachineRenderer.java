package net.pocketodds.client.anim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.pocketodds.client.PocketOddsClientConfig;
import net.pocketodds.gambling.slot.SlotSymbol;

import java.util.Random;

public class SlotMachineRenderer {
    private final Random random = new Random();
    private boolean spinning = false;
    private int totalAnimTicks = 30;
    private int remainingAnimTicks = 0;

    private SlotSymbol[] targetSymbols = new SlotSymbol[]{SlotSymbol.CHERRY, SlotSymbol.CHERRY, SlotSymbol.CHERRY};
    private SlotSymbol[] currentSymbols = new SlotSymbol[]{SlotSymbol.CHERRY, SlotSymbol.CHERRY, SlotSymbol.CHERRY};
    private final boolean[] reelStopped = new boolean[]{true, true, true};

    private boolean isJackpot = false;
    private boolean isJokerRescue = false;
    private boolean isSkulls = false;
    private long wonAmount = 0L;

    private int lastSoundTick = -1;

    public void startSpin(SlotSymbol[] target, int animTicks, boolean jackpot, boolean jokerRescue, boolean skulls, long winAmount) {
        if (!PocketOddsClientConfig.areAnimationsEnabled()) {
            this.targetSymbols = target;
            this.currentSymbols = target;
            this.spinning = false;
            this.remainingAnimTicks = 0;
            this.isJackpot = jackpot;
            this.isJokerRescue = jokerRescue;
            this.isSkulls = skulls;
            this.wonAmount = winAmount;
            return;
        }

        double speedMult = PocketOddsClientConfig.getAnimationSpeed();
        this.totalAnimTicks = Math.max(10, (int) Math.round((animTicks <= 0 ? 30 : animTicks) / speedMult));
        this.remainingAnimTicks = this.totalAnimTicks;
        this.targetSymbols = target != null ? target : new SlotSymbol[]{SlotSymbol.CHERRY, SlotSymbol.CHERRY, SlotSymbol.CHERRY};
        this.spinning = true;
        this.reelStopped[0] = false;
        this.reelStopped[1] = false;
        this.reelStopped[2] = false;
        this.isJackpot = jackpot;
        this.isJokerRescue = jokerRescue;
        this.isSkulls = skulls;
        this.wonAmount = winAmount;
        this.lastSoundTick = -1;
    }

    public void skipAnimation() {
        if (spinning) {
            this.spinning = false;
            this.remainingAnimTicks = 0;
            this.currentSymbols = targetSymbols.clone();
            this.reelStopped[0] = true;
            this.reelStopped[1] = true;
            this.reelStopped[2] = true;
            playFinishSound();
        }
    }

    public boolean isSpinning() {
        return spinning;
    }

    public void tick() {
        if (!spinning) return;

        remainingAnimTicks--;

        // Staggered reel stop thresholds
        int stopReel0At = (int) (totalAnimTicks * 0.40);
        int stopReel1At = (int) (totalAnimTicks * 0.20);
        int stopReel2At = 0;

        // Reel 0
        if (remainingAnimTicks <= stopReel0At && !reelStopped[0]) {
            reelStopped[0] = true;
            currentSymbols[0] = targetSymbols[0];
            playReelStopSound();
        } else if (!reelStopped[0]) {
            currentSymbols[0] = getRandomSymbol();
        }

        // Reel 1
        if (remainingAnimTicks <= stopReel1At && !reelStopped[1]) {
            reelStopped[1] = true;
            currentSymbols[1] = targetSymbols[1];
            playReelStopSound();
        } else if (!reelStopped[1]) {
            currentSymbols[1] = getRandomSymbol();
        }

        // Reel 2
        if (remainingAnimTicks <= stopReel2At && !reelStopped[2]) {
            reelStopped[2] = true;
            currentSymbols[2] = targetSymbols[2];
            playReelStopSound();
        } else if (!reelStopped[2]) {
            currentSymbols[2] = getRandomSymbol();
        }

        // Spin sound throttled
        if (remainingAnimTicks > 0 && (totalAnimTicks - remainingAnimTicks) % 3 == 0) {
            playSpinSound();
        }

        if (remainingAnimTicks <= 0) {
            spinning = false;
            currentSymbols = targetSymbols.clone();
            playFinishSound();
        }
    }

    private SlotSymbol getRandomSymbol() {
        SlotSymbol[] syms = SlotSymbol.values();
        return syms[random.nextInt(syms.length)];
    }

    public void render(GuiGraphics guiGraphics, int x, int y, float partialTick) {
        // 3 Reels positioning
        int[] reelX = new int[]{x + 36, x + 91, x + 146};
        int reelY = y + 39;

        for (int i = 0; i < 3; i++) {
            renderReelSlot(guiGraphics, reelX[i], reelY, currentSymbols[i], reelStopped[i]);
        }

        // Highlights and Special Effects
        if (!spinning) {
            if (isJackpot) {
                renderJackpotGlow(guiGraphics, x + 30, reelY - 4, 160, 42);
            } else if (wonAmount > 0) {
                renderWinLine(guiGraphics, reelX[0] - 2, reelY - 2, 148, 38);
            }

            if (isJokerRescue) {
                renderRescueBadge(guiGraphics, x + 50, reelY + 34);
            } else if (isSkulls) {
                renderSkullCurse(guiGraphics, x + 30, reelY - 4, 160, 42);
            }
        }
    }

    private void renderReelSlot(GuiGraphics guiGraphics, int rx, int ry, SlotSymbol symbol, boolean stopped) {
        // Outer brass border
        guiGraphics.fill(rx - 1, ry - 1, rx + 35, ry + 35, stopped ? 0xFFC69C3D : 0xFF8E712B);
        // Inner dark well
        guiGraphics.fill(rx, ry, rx + 34, ry + 34, 0xFF141316);
        guiGraphics.fill(rx + 2, ry + 2, rx + 32, ry + 32, 0xFF1F1D24);

        // Render symbol centered
        if (symbol != null) {
            String symStr = symbol.getShortDisplay().getString();
            int strW = Minecraft.getInstance().font.width(symStr);
            int textColor = stopped ? 0xFFFFFF : 0xAAAAAA;
            guiGraphics.drawString(Minecraft.getInstance().font, symStr, rx + 17 - strW / 2, ry + 13, textColor, false);
        }
    }

    private void renderWinLine(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        int color = 0x88FFD700;
        guiGraphics.fill(x, y, x + w, y + 2, color);
        guiGraphics.fill(x, y + h - 2, x + w, y + h, color);
        guiGraphics.fill(x, y, x + 2, y + h, color);
        guiGraphics.fill(x + w - 2, y, x + w, y + h, color);
    }

    private void renderJackpotGlow(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        long time = System.currentTimeMillis();
        int pulse = (int) (Math.sin(time / 150.0) * 40 + 200);
        int glowColor = (pulse << 24) | 0xFFD700;
        guiGraphics.fill(x, y, x + w, y + 2, glowColor);
        guiGraphics.fill(x, y + h - 2, x + w, y + h, glowColor);
        guiGraphics.fill(x, y, x + 2, y + h, glowColor);
        guiGraphics.fill(x + w - 2, y, x + w, y + h, glowColor);
    }

    private void renderRescueBadge(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x, y, x + 110, y + 13, 0xCC1B5E20);
        guiGraphics.drawString(Minecraft.getInstance().font, Component.literal("★ ДЖОКЕР СПАСЕНИЕ! ★"), x + 6, y + 3, 0xFF55FF55, false);
    }

    private void renderSkullCurse(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        guiGraphics.fill(x, y, x + w, y + h, 0x55880000);
    }

    private void playSpinSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.NOTE_BLOCK_HAT.get(), 1.0F + random.nextFloat() * 0.3F, vol * 0.6F));
        }
    }

    private void playReelStopSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.PISTON_CONTRACT, 1.4F + random.nextFloat() * 0.2F, vol * 0.7F));
        }
    }

    private void playFinishSound() {
        float vol = PocketOddsClientConfig.getSoundVolume();
        Minecraft mc = Minecraft.getInstance();
        if (vol > 0.01F && mc != null && mc.player != null && mc.getSoundManager() != null) {
            if (isJackpot) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, vol));
            } else if (wonAmount > 0) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.2F, vol * 0.8F));
            } else {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.get(), 0.8F, vol * 0.7F));
            }
        }
    }
}
