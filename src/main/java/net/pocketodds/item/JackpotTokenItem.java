package net.pocketodds.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.util.FeedbackEffects;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class JackpotTokenItem extends Item {
    public JackpotTokenItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            ServerLevel serverLevel = serverPlayer.serverLevel();
            long currentJackpot = JackpotSavedData.get(serverLevel).getJackpotAmount();

            FeedbackEffects.sendActionBar(serverPlayer,
                    Component.translatable("pocketodds.jackpot.current_pool", currentJackpot).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

            CompoundTag tag = stack.getTag();
            if (tag != null && tag.contains("Winner")) {
                serverPlayer.sendSystemMessage(Component.translatable("pocketodds.jackpot_token.header").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                serverPlayer.sendSystemMessage(Component.translatable("pocketodds.jackpot_token.msg_winner", tag.getString("Winner")).withStyle(ChatFormatting.YELLOW));
                serverPlayer.sendSystemMessage(Component.translatable("pocketodds.jackpot_token.msg_amount", tag.getLong("JackpotAmount")).withStyle(ChatFormatting.GOLD));
                serverPlayer.sendSystemMessage(Component.translatable("pocketodds.jackpot_token.msg_date", tag.getString("Date")).withStyle(ChatFormatting.GRAY));
            }

            // Sound and celebratory particles
            serverLevel.playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                    SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8f, 1.2f);
            serverLevel.sendParticles(ParticleTypes.FIREWORK, serverPlayer.getX(), serverPlayer.getY() + 1.0, serverPlayer.getZ(),
                    15, 0.3, 0.5, 0.3, 0.08);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.pocketodds.jackpot_token.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("Winner")) {
            tooltip.add(Component.translatable("tooltip.pocketodds.jackpot_token.winner", tag.getString("Winner")).withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.translatable("tooltip.pocketodds.jackpot_token.amount", tag.getLong("JackpotAmount")).withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.translatable("tooltip.pocketodds.jackpot_token.date", tag.getString("Date")).withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.pocketodds.jackpot_token.desc").withStyle(ChatFormatting.YELLOW));
        }

        tooltip.add(Component.translatable("tooltip.pocketodds.jackpot_token.use").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
