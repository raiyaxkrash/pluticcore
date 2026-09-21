package net.pocketodds.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.pocketodds.data.JackpotSavedData;
import net.pocketodds.registration.ModItems;
import net.pocketodds.util.FeedbackEffects;
import net.pocketodds.util.InventoryUtils;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

public class RouletteTokenItem extends Item {
    private static final Set<Integer> RED_NUMBERS = Set.of(
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
    );

    public RouletteTokenItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    public static String getBetType(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return (tag != null && tag.contains("BetType")) ? tag.getString("BetType") : "RED";
    }

    public static void cycleBetType(ItemStack stack) {
        String current = getBetType(stack);
        String next = switch (current) {
            case "RED" -> "BLACK";
            case "BLACK" -> "GREEN";
            default -> "RED";
        };
        stack.getOrCreateTag().putString("BetType", next);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            cycleBetType(stack);
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                String betType = getBetType(stack);
                ChatFormatting color = switch (betType) {
                    case "RED" -> ChatFormatting.RED;
                    case "BLACK" -> ChatFormatting.GRAY;
                    default -> ChatFormatting.GREEN;
                };
                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.translatable("pocketodds.roulette.bet_type", Component.literal(betType).withStyle(color)).withStyle(ChatFormatting.YELLOW));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.UI_BUTTON_CLICK.get(), 0.8f, 1.2f);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            if (serverPlayer.getCooldowns().isOnCooldown(this)) {
                return InteractionResultHolder.fail(stack);
            }

            // Consume 1 token to spin
            stack.shrink(1);
            serverPlayer.getCooldowns().addCooldown(this, 20);

            // Add contribution to jackpot
            JackpotSavedData.get(serverPlayer.serverLevel()).addContribution(16);

            // Roll 0..36
            int number = level.random.nextInt(37);
            boolean isZero = (number == 0);
            boolean isRed = RED_NUMBERS.contains(number);
            String outcomeType = isZero ? "GREEN" : (isRed ? "RED" : "BLACK");

            String playerBet = getBetType(stack);
            boolean won = playerBet.equalsIgnoreCase(outcomeType);

            ChatFormatting outcomeColor = isZero ? ChatFormatting.GREEN : (isRed ? ChatFormatting.RED : ChatFormatting.DARK_GRAY);

            if (won) {
                int rewardChips = isZero ? 35 : 2; // 35x for Green, 2x for Red/Black
                InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(ModItems.GOLD_CHIP.get(), rewardChips));

                FeedbackEffects.sendActionBar(serverPlayer,
                        Component.literal("[ 🎡 " + number + " " + outcomeType + " ] ")
                                .append(Component.translatable("pocketodds.roulette.win", "+" + rewardChips + " Gold Chips").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
                FeedbackEffects.playSound(serverPlayer, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.2f);
                FeedbackEffects.spawnParticles(serverPlayer, ParticleTypes.FIREWORK, 20, 0.4, 0.4, 0.4, 0.1);
            } else {
                if (InventoryUtils.hasInsurance(serverPlayer)) {
                    InventoryUtils.consumeInsurance(serverPlayer);
                    InventoryUtils.giveOrDrop(serverPlayer, new ItemStack(ModItems.COPPER_CHIP.get(), 4));
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.literal("[ 🎡 " + number + " " + outcomeType + " ] ")
                                    .append(Component.translatable("pocketodds.insurance.triggered", "4 copper").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.SHIELD_BLOCK, 1.0f, 1.0f);
                } else {
                    FeedbackEffects.sendActionBar(serverPlayer,
                            Component.literal("[ 🎡 " + number + " " + outcomeType + " ] ")
                                    .append(Component.translatable("pocketodds.roulette.loss").withStyle(ChatFormatting.GRAY)));
                    FeedbackEffects.playSound(serverPlayer, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
                }
            }
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        String betType = getBetType(stack);
        ChatFormatting color = switch (betType) {
            case "RED" -> ChatFormatting.RED;
            case "BLACK" -> ChatFormatting.GRAY;
            default -> ChatFormatting.GREEN;
        };

        tooltip.add(Component.translatable("tooltip.pocketodds.roulette.title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        tooltip.add(Component.translatable("tooltip.pocketodds.roulette.bet", Component.literal(betType).withStyle(color)).withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.pocketodds.roulette.controls_shift").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.pocketodds.roulette.controls_right").withStyle(ChatFormatting.GREEN));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
