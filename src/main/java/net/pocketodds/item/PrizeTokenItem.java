package net.pocketodds.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class PrizeTokenItem extends Item {

    public PrizeTokenItem(Properties properties) {
        super(properties.stacksTo(64).rarity(Rarity.RARE));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents, TooltipFlag isAdvanced) {
        tooltipComponents.add(Component.translatable("item.pocketodds.prize_token.tooltip").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("item.pocketodds.prize_token.hint").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        super.appendHoverText(stack, level, tooltipComponents, isAdvanced);
    }
}
