package net.pocketodds.network.s2c;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.pocketodds.gui.casino.PocketCasinoScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SyncShopCatalogS2CPacket {

    public static class ClientShopEntry {
        private final String offerId;
        private final ItemStack rewardStack;
        private final int price;
        private final int categoryOrdinal;
        private final int limitPeriodOrdinal;
        private final int remainingLimit;
        private final boolean available;
        private final String nameKey;
        private final String descriptionKey;

        public ClientShopEntry(String offerId, ItemStack rewardStack, int price, int categoryOrdinal,
                               int limitPeriodOrdinal, int remainingLimit, boolean available,
                               String nameKey, String descriptionKey) {
            this.offerId = offerId;
            this.rewardStack = rewardStack;
            this.price = price;
            this.categoryOrdinal = categoryOrdinal;
            this.limitPeriodOrdinal = limitPeriodOrdinal;
            this.remainingLimit = remainingLimit;
            this.available = available;
            this.nameKey = nameKey;
            this.descriptionKey = descriptionKey;
        }

        public ClientShopEntry(FriendlyByteBuf buf) {
            this.offerId = buf.readUtf(128);
            this.rewardStack = buf.readItem();
            this.price = buf.readVarInt();
            this.categoryOrdinal = buf.readVarInt();
            this.limitPeriodOrdinal = buf.readVarInt();
            this.remainingLimit = buf.readVarInt();
            this.available = buf.readBoolean();
            this.nameKey = buf.readUtf(128);
            this.descriptionKey = buf.readUtf(128);
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(offerId, 128);
            buf.writeItem(rewardStack);
            buf.writeVarInt(price);
            buf.writeVarInt(categoryOrdinal);
            buf.writeVarInt(limitPeriodOrdinal);
            buf.writeVarInt(remainingLimit);
            buf.writeBoolean(available);
            buf.writeUtf(nameKey, 128);
            buf.writeUtf(descriptionKey, 128);
        }

        public String getOfferId() { return offerId; }
        public ItemStack getRewardStack() { return rewardStack; }
        public int getPrice() { return price; }
        public int getCategoryOrdinal() { return categoryOrdinal; }
        public int getLimitPeriodOrdinal() { return limitPeriodOrdinal; }
        public int getRemainingLimit() { return remainingLimit; }
        public boolean isAvailable() { return available; }
        public String getNameKey() { return nameKey; }
        public String getDescriptionKey() { return descriptionKey; }
    }

    private final int playerTokens;
    private final List<ClientShopEntry> entries;

    public SyncShopCatalogS2CPacket(int playerTokens, List<ClientShopEntry> entries) {
        this.playerTokens = playerTokens;
        this.entries = entries != null ? entries : new ArrayList<>();
    }

    public SyncShopCatalogS2CPacket(FriendlyByteBuf buf) {
        this.playerTokens = buf.readVarInt();
        int size = buf.readVarInt();
        this.entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            this.entries.add(new ClientShopEntry(buf));
        }
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeVarInt(playerTokens);
        buf.writeVarInt(entries.size());
        for (ClientShopEntry entry : entries) {
            entry.toBytes(buf);
        }
    }

    public int getPlayerTokens() {
        return playerTokens;
    }

    public List<ClientShopEntry> getEntries() {
        return entries;
    }

    public static void handle(SyncShopCatalogS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (Minecraft.getInstance().screen instanceof PocketCasinoScreen screen) {
                    screen.updateShopCatalog(msg.playerTokens, msg.entries);
                }
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
