package net.pocketodds.util;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public class FeedbackEffects {

    public static void sendActionBar(ServerPlayer player, Component message) {
        player.sendSystemMessage(message, true);
    }

    public static void playSound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        player.playNotifySound(sound, SoundSource.PLAYERS, volume, pitch);
    }

    public static void spawnParticles(ServerPlayer player, ParticleOptions particle, int count, double spreadX, double spreadY, double spreadZ, double speed) {
        ServerLevel level = player.serverLevel();
        level.sendParticles(player, particle, false,
                player.getX(), player.getY() + 1.0, player.getZ(),
                count, spreadX, spreadY, spreadZ, speed);
    }
}
