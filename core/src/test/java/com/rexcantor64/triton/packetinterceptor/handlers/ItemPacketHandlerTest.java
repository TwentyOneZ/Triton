package com.rexcantor64.triton.packetinterceptor.handlers;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemEnchantments;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentTypes;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantOffer;
import com.github.retrooper.packetevents.protocol.recipe.display.ShapelessCraftingRecipeDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.ItemStackSlotDisplay;
import com.rexcantor64.triton.api.language.Localized;
import com.rexcantor64.triton.test.DefaultFeatureSyntax;
import com.rexcantor64.triton.test.MockAdventureParser;
import com.rexcantor64.triton.test.MockPacketEventsAPI;
import com.rexcantor64.triton.utils.ComponentUtils;
import com.rexcantor64.triton.utils.ItemStackTranslationUtils;
import com.rexcantor64.triton.utils.RecipeTranslationUtils;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemPacketHandlerTest {
    private final MockAdventureParser parser = new MockAdventureParser();
    private final RecipeTranslationUtils recipes = new RecipeTranslationUtils(parser, new DefaultFeatureSyntax());
    private final ItemPacketHandler handler = new ItemPacketHandler(
            new ItemStackTranslationUtils(parser, new DefaultFeatureSyntax(), false), recipes, true);
    private final Localized locale = () -> { throw new UnsupportedOperationException(); };

    @BeforeEach
    void setup() { PacketEvents.setAPI(new MockPacketEventsAPI()); }

    private static ItemStack item(ItemType type, int amount, String name) {
        ItemStack item = ItemStack.builder().type(type).amount(amount).build();
        if (name != null) item.setComponent(ComponentTypes.CUSTOM_NAME, Component.text(name));
        return item;
    }

    @Test
    void translatesBothCostsAndResultWithoutChangingCountsOrTradeTerms() {
        ItemStack first = item(ItemTypes.EMERALD, 7, "[lang]first[/lang]");
        ItemStack second = item(ItemTypes.BOOK, 2, "[lang]second[/lang]");
        ItemStack result = item(ItemTypes.ENCHANTED_BOOK, 1, "[lang]result[/lang]");
        result.setComponent(ComponentTypes.STORED_ENCHANTMENTS, new ItemEnchantments(Map.of(EnchantmentTypes.MENDING, 1)));
        MerchantOffer offer = MerchantOffer.of(first, second, result, 3, 12, 5, -2, 0.2F, 4);
        assertTrue(handler.translateMerchantOffers(List.of(offer), locale));
        assertEquals("replaced(first)", ComponentUtils.componentToString(offer.getFirstInputItem().getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow()));
        assertEquals("replaced(second)", ComponentUtils.componentToString(offer.getSecondInputItem().getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow()));
        assertEquals("replaced(result)", ComponentUtils.componentToString(offer.getOutputItem().getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow()));
        assertEquals(ItemTypes.BOOK, offer.getSecondInputItem().getType());
        assertEquals(7, offer.getFirstInputItem().getAmount());
        assertEquals(2, offer.getSecondInputItem().getAmount());
        assertEquals(1, offer.getOutputItem().getAmount());
        assertEquals(3, offer.getUses());
        assertEquals(12, offer.getMaxUses());
        assertEquals(5, offer.getXp());
        assertEquals(-2, offer.getSpecialPrice());
        assertEquals(0.2F, offer.getPriceMultiplier());
        assertEquals(4, offer.getDemand());
        assertEquals(result.getComponent(ComponentTypes.STORED_ENCHANTMENTS), offer.getOutputItem().getComponent(ComponentTypes.STORED_ENCHANTMENTS));
        assertTrue(result.getComponent(ComponentTypes.LORE).orElseThrow().getLines().isEmpty());
        assertEquals(Component.text("[lang]first[/lang]"), first.getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow());
        assertFalse(handler.translateMerchantOffers(List.of(offer), locale));
    }

    @Test
    void keepsMissingSecondCostAndUnchangedOffersUntouched() {
        MerchantOffer offer = MerchantOffer.of(item(ItemTypes.EMERALD, 5, null), item(ItemTypes.BOOK, 1, null), 0, 10, 2, 0F, 0);
        assertFalse(handler.translateMerchantOffers(List.of(offer), locale));
        assertNull(offer.getSecondInputItem());
        offer.getFirstInputItem().setComponent(ComponentTypes.CUSTOM_NAME, Component.text("[lang]first[/lang]"));
        assertTrue(handler.translateMerchantOffers(List.of(offer), locale));
        assertNull(offer.getSecondInputItem());
    }

    @Test
    void recipeBookUsesSameEnchantmentTranslatorWithoutMutatingRecipe() {
        ItemStack original = item(ItemTypes.ENCHANTED_BOOK, 1, "[lang]recipe[/lang]");
        original.setComponent(ComponentTypes.STORED_ENCHANTMENTS, new ItemEnchantments(Map.of(EnchantmentTypes.SHARPNESS, 4)));
        ItemStackSlotDisplay input = new ItemStackSlotDisplay(item(ItemTypes.BOOK, 1, "[lang]ingredient[/lang]"));
        ItemStackSlotDisplay output = new ItemStackSlotDisplay(original);
        ShapelessCraftingRecipeDisplay recipe = new ShapelessCraftingRecipeDisplay(List.of(input), output, input);
        ShapelessCraftingRecipeDisplay translated = (ShapelessCraftingRecipeDisplay) recipes.translateRecipeDisplay(recipe, locale).orElseThrow();
        ItemStack translatedItem = ((ItemStackSlotDisplay) translated.getResult()).getStack();
        assertTrue(translatedItem.getComponent(ComponentTypes.LORE).isPresent());
        assertEquals(original.getComponent(ComponentTypes.STORED_ENCHANTMENTS), translatedItem.getComponent(ComponentTypes.STORED_ENCHANTMENTS));
        assertEquals("replaced(ingredient)", ComponentUtils.componentToString(((ItemStackSlotDisplay) translated.getIngredients().getFirst()).getStack().getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow()));
        assertSame(output, recipe.getResult());
        assertTrue(original.getComponent(ComponentTypes.LORE).orElseThrow().getLines().isEmpty());
        assertSame(input, translated.getCraftingStation());
        assertTrue(recipes.translateRecipeDisplay(translated, locale).isEmpty());
    }
}
