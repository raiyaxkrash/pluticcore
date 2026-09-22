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

/**
 * Legacy token preserved solely to prevent world loading crashes and missing registry mapping errors
 * for existing worlds and items. It is no longer used in the shop or anywhere in the mod economy.
 */
@Deprecated(forRemoval = true, since = "1.1.0")
public class PrizeTokenItem extends Item {

    public PrizeTokenItem(Properties properties) {
        super(properties.stacksTo(64).rarity(Rarity.COMMON));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents, TooltipFlag isAdvanced) {
        tooltipComponents.add(Component.translatable("item.pocketodds.prize_token.obsolete").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
        super.appendHoverText(stack, level, tooltipComponents, isAdvanced);
    }
}
