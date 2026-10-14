package com.rexcantor64.triton.packetinterceptor.handlers;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantItemCost;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.rexcantor64.triton.config.MainConfig;
import com.rexcantor64.triton.language.parser.MessageParser;
import com.rexcantor64.triton.player.TritonLanguagePlayer;
import com.rexcantor64.triton.utils.ItemStackTranslationUtils;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

@RequiredArgsConstructor
public class ItemPacketHandler {

    private final ItemStackTranslationUtils itemHandler;
    private final boolean translateInventoryItems;

    public ItemPacketHandler(@NotNull MessageParser parser, @NotNull MainConfig config) {
        this.itemHandler = new ItemStackTranslationUtils(parser, config.getItemsSyntax(), config.isBooks());
        this.translateInventoryItems = config.isInventoryItems();
    }

    public void onSetCursorItemPacket(@NotNull PacketSendEvent event, @NotNull TritonLanguagePlayer<?> languagePlayer) {
        val packet = new WrapperPlayServerSetCursorItem(event);

        if (!this.translateInventoryItems && languagePlayer.getPacketEventsRefresh().getCurrentWindowId() == 0) {
            return;
        }

        val result = this.itemHandler.translateItem(packet.getStack(), languagePlayer);
        if (result.isModified()) {
            packet.setStack(result.getModified());
            event.markForReEncode(true);
        }
    }

    public void onSetPlayerInventoryPacket(@NotNull PacketSendEvent event, @NotNull TritonLanguagePlayer<?> languagePlayer) {
        val packet = new WrapperPlayServerSetPlayerInventory(event);

        if (!this.translateInventoryItems && languagePlayer.getPacketEventsRefresh().getCurrentWindowId() == 0) {
            return;
        }

        val result = this.itemHandler.translateItem(packet.getStack(), languagePlayer);
        if (result.isModified()) {
            packet.setStack(result.getModified());
            event.markForReEncode(true);
        }
    }

    public void onSetSlotPacket(@NotNull PacketSendEvent event, @NotNull TritonLanguagePlayer<?> languagePlayer) {
        val packet = new WrapperPlayServerSetSlot(event);

        if (!this.translateInventoryItems && languagePlayer.getPacketEventsRefresh().getCurrentWindowId() == 0) {
            return;
        }

        val result = this.itemHandler.translateItem(packet.getItem(), languagePlayer);
        if (result.isModified()) {
            packet.setItem(result.getModified());
            event.markForReEncode(true);
        }
    }

    public void onWindowItemsPacket(@NotNull PacketSendEvent event, @NotNull TritonLanguagePlayer<?> languagePlayer) {
        val packet = new WrapperPlayServerWindowItems(event);

        if (!this.translateInventoryItems && languagePlayer.getPacketEventsRefresh().getCurrentWindowId() == 0) {
            return;
        }

        val carriedItem = packet.getCarriedItem();
        if (carriedItem.isPresent()) {
            val result = this.itemHandler.translateItem(carriedItem.get(), languagePlayer);
            if (result.isModified()) {
                packet.setCarriedItem(result.getModified());
                event.markForReEncode(true);
            }
        }

        val windowItems = packet.getItems();
        for (int i = 0; i < windowItems.size(); i++) {
            val item = windowItems.get(i);
            val result = this.itemHandler.translateItem(item, languagePlayer);
            if (result.isModified()) {
                windowItems.set(i, result.getModified());
                event.markForReEncode(true);
            }
        }
    }

    public void onMerchantOffersPacket(@NotNull PacketSendEvent event, @NotNull TritonLanguagePlayer<?> languagePlayer) {
        val packet = new WrapperPlayServerMerchantOffers(event);
        boolean changed = false;

        for (val offer : packet.getMerchantOffers()) {
            changed |= translateMerchantCost(offer.getFirstInputCost(), offer::setFirstInputCost, languagePlayer);
            changed |= translateMerchantCost(offer.getSecondInputCost(), offer::setSecondInputCost, languagePlayer);
            changed |= translateMerchantItem(offer.getOutputItem(), offer::setOutputItem, languagePlayer);
        }

        if (changed) {
            event.markForReEncode(true);
        }
    }

    public void onClientCloseWindowPacket(@NotNull PacketReceiveEvent event, @NotNull TritonLanguagePlayer<?> languagePlayer) {
        languagePlayer.getPacketEventsRefresh().setCurrentWindowId(0);
    }

    public void onServerCloseWindowPacket(@NotNull PacketSendEvent event, @NotNull TritonLanguagePlayer<?> languagePlayer) {
        languagePlayer.getPacketEventsRefresh().setCurrentWindowId(0);
    }

    private boolean translateMerchantCost(MerchantItemCost cost, Consumer<MerchantItemCost> setter, TritonLanguagePlayer<?> languagePlayer) {
        if (cost == null) {
            return false;
        }

        val item = cost.asItem();
        return translateMerchantItem(item, translatedItem -> setter.accept(MerchantItemCost.ofItem(translatedItem)), languagePlayer);
    }

    private boolean translateMerchantItem(ItemStack item, Consumer<ItemStack> setter, TritonLanguagePlayer<?> languagePlayer) {
        if (item == null || item.isEmpty()) {
            return false;
        }

        val result = this.itemHandler.translateItem(item, languagePlayer);
        if (!result.isModified()) {
            return false;
        }
        setter.accept(result.getModified());
        return true;
    }
}
