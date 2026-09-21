package net.pocketodds.gambling.slot;

import net.minecraft.world.item.Item;
import net.pocketodds.registration.ModItems;

public interface SlotRewardFactory {
    Item getJackpotToken();
    Item getJokerItem();

    SlotRewardFactory DEFAULT = new SlotRewardFactory() {
        @Override
        public Item getJackpotToken() {
            return ModItems.JACKPOT_TOKEN.get();
        }

        @Override
        public Item getJokerItem() {
            return ModItems.JOKER.get();
        }
    };
}
