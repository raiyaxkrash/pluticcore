package net.pocketodds.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import net.pocketodds.gui.casino.CasinoCategory;
import net.pocketodds.gui.casino.PocketCasinoMenu;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class PocketCasinoItem extends Item {
    public PocketCasinoItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(
                    serverPlayer,
                    new SimpleMenuProvider(
                            (id, inv, p) -> new PocketCasinoMenu(id, inv, CasinoCategory.SLOTS),
                            Component.translatable("pocketodds.gui.casino.title")
                    ),
                    buf -> buf.writeInt(CasinoCategory.SLOTS.ordinal())
            );
            net.pocketodds.service.CasinoGameService.onPlayerOpenCasino(serverPlayer);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.pocketodds.casino.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        tooltip.add(Component.translatable("tooltip.pocketodds.casino.description").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.pocketodds.casino.help").withStyle(ChatFormatting.YELLOW));
    }
}