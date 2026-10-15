package com.rexcantor64.triton.utils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemEnchantments;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ArmorTrim;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTooltipDisplay;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentType;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentTypes;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.item.trimmaterial.TrimMaterials;
import com.github.retrooper.packetevents.protocol.item.trimpattern.TrimPatterns;
import com.rexcantor64.triton.api.language.Language;
import com.rexcantor64.triton.api.language.Localized;
import com.rexcantor64.triton.api.language.TranslationManager;
import com.rexcantor64.triton.test.DefaultFeatureSyntax;
import com.rexcantor64.triton.test.MockAdventureParser;
import com.rexcantor64.triton.test.MockPacketEventsAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ItemStackTranslationUtilsTest {
    private final Localized locale = locale("pt_BR");
    private final ItemStackTranslationUtils handler = new ItemStackTranslationUtils(
            new MockAdventureParser(), new DefaultFeatureSyntax(), true
    );

    @BeforeEach
    void setup() {
        PacketEvents.setAPI(new MockPacketEventsAPI());
    }

    private static Localized locale(String id) {
        return new Localized() {
            @Override public Language getLanguage() { throw new UnsupportedOperationException(); }
            @Override public String getLanguageId() { return id; }
        };
    }

    private static ItemStack enchanted(ComponentType<ItemEnchantments> type, Map<EnchantmentType, Integer> levels) {
        ItemStack item = ItemStack.builder().type(type == ComponentTypes.STORED_ENCHANTMENTS
                ? ItemTypes.ENCHANTED_BOOK : ItemTypes.DIAMOND_SWORD).amount(2).build();
        item.setComponent(type, new ItemEnchantments(levels));
        return item;
    }

    private static List<Component> lore(ItemStack item) {
        return item.getComponent(ComponentTypes.LORE).orElseThrow().getLines();
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_US", "pt_BR", "es_MX", "ru_RU", "ft_FR", "fr_FR"})
    void translatesRegisteredVanillaKeysAndRefreshesFromOriginal(String id) {
        Map<String, String> names = Map.of("en_US", "Sharpness", "pt_BR", "Afiação", "es_MX", "Filo",
                "ru_RU", "Острота", "ft_FR", "Tranchant", "fr_FR", "Tranchant");
        TranslationManager translations = (TranslationManager) Proxy.newProxyInstance(
                TranslationManager.class.getClassLoader(), new Class<?>[]{TranslationManager.class},
                (proxy, method, args) -> {
                    assertEquals("getTextComponent", method.getName());
                    return switch ((String) args[1]) {
                        case "enchantment.minecraft.sharpness" -> Optional.of(Component.text(names.get(((Localized) args[0]).getLanguageId())));
                        case "enchantment.level.3" -> Optional.of(Component.text("III"));
                        default -> Optional.empty();
                    };
                }
        );
        ItemStackTranslationUtils translator = new ItemStackTranslationUtils(
                new MockAdventureParser(), new DefaultFeatureSyntax(), false, translations);
        ItemStack original = enchanted(ComponentTypes.ENCHANTMENTS, Map.of(EnchantmentTypes.SHARPNESS, 3));
        ItemStack translated = translator.translateItem(original, locale(id)).getModified();
        assertEquals(names.get(id) + " III", ComponentUtils.componentToString(lore(translated).getFirst()));
        assertEquals(NamedTextColor.GRAY, lore(translated).getFirst().color());
        assertEquals(TextDecoration.State.FALSE, lore(translated).getFirst().decoration(TextDecoration.ITALIC));
        assertEquals(original.getComponent(ComponentTypes.ENCHANTMENTS), translated.getComponent(ComponentTypes.ENCHANTMENTS));
        assertTrue(original.getComponent(ComponentTypes.LORE).orElseThrow().getLines().isEmpty());
        assertTrue(original.getComponent(ComponentTypes.TOOLTIP_DISPLAY).orElseThrow().getHiddenComponents().isEmpty());
        assertEquals(2, translated.getAmount());
        assertEquals("Afiação III", ComponentUtils.componentToString(lore(translator.translateItem(original, locale).getModified()).getFirst()));
    }

    @Test
    void translatesExistingNativeLoreWhenVanillaEnchantmentsAreHidden() {
        Map<String, Map<String, String>> names = Map.of(
                "pt_BR", Map.of("Enchantments.sharpness.name", "Afiação", "Enchantments.knockback.name", "Repulsão"),
                "es_MX", Map.of("Enchantments.sharpness.name", "Filo", "Enchantments.knockback.name", "Empuje"));
        TranslationManager translations = (TranslationManager) Proxy.newProxyInstance(
                TranslationManager.class.getClassLoader(), new Class<?>[]{TranslationManager.class},
                (proxy, method, args) -> Optional.ofNullable(names.get(((Localized) args[0]).getLanguageId()).get(args[1]))
                        .map(Component::text));
        ItemStackTranslationUtils translator = new ItemStackTranslationUtils(
                new MockAdventureParser(), new DefaultFeatureSyntax(), false, translations);
        ItemStack original = enchanted(ComponentTypes.ENCHANTMENTS,
                Map.of(EnchantmentTypes.SHARPNESS, 5, EnchantmentTypes.KNOCKBACK, 1));
        Component sharpness = Component.empty().append(Component.empty().color(NamedTextColor.GRAY)
                .append(Component.translatable("enchantment.minecraft.sharpness"))
                .append(Component.text(" ")).append(Component.translatable("enchantment.level.5")));
        Component knockback = Component.empty().append(Component.translatable("enchantment.minecraft.knockback")
                .color(NamedTextColor.GRAY).append(Component.text(" "))
                .append(Component.translatable("enchantment.level.1")));
        List<Component> originalLore = List.of(sharpness, knockback, Component.text("Silent Blade"));
        original.setComponent(ComponentTypes.LORE, new ItemLore(originalLore));
        ItemTooltipDisplay display = new ItemTooltipDisplay(false, Set.of(ComponentTypes.ENCHANTMENTS));
        original.setComponent(ComponentTypes.TOOLTIP_DISPLAY, display);
        for (String id : List.of("pt_BR", "es_MX")) {
            ItemStack translated = translator.translateItem(original, locale(id)).getModified();
            assertNotNull(translated);
            assertEquals(3, lore(translated).size());
            assertTrue(ComponentUtils.componentToString(lore(translated).getFirst()).startsWith(names.get(id).get("Enchantments.sharpness.name")));
            assertTrue(ComponentUtils.componentToString(lore(translated).get(1)).startsWith(names.get(id).get("Enchantments.knockback.name")));
            assertEquals(Component.text("Silent Blade"), lore(translated).get(2));
            assertEquals(display, translated.getComponent(ComponentTypes.TOOLTIP_DISPLAY).orElseThrow());
            assertEquals(original.getComponent(ComponentTypes.ENCHANTMENTS), translated.getComponent(ComponentTypes.ENCHANTMENTS));
        }
        assertEquals(originalLore, lore(original));
    }

    @Test
    void translatesNamespacedCustomNamesInExistingLore() {
        EnchantmentType custom = (EnchantmentType) Proxy.newProxyInstance(EnchantmentType.class.getClassLoader(),
                new Class<?>[]{EnchantmentType.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDescription" -> Component.text("[lang]Enchantments.mythic:prismatic.name[/lang]");
                    case "getName" -> new com.github.retrooper.packetevents.resources.ResourceLocation("mythic:prismatic");
                    case "hashCode" -> 37;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        TranslationManager translations = (TranslationManager) Proxy.newProxyInstance(
                TranslationManager.class.getClassLoader(), new Class<?>[]{TranslationManager.class},
                (proxy, method, args) -> "Enchantments.mythic:prismatic.name".equals(args[1])
                        ? Optional.of(Component.text("Prismático")) : Optional.empty());
        ItemStackTranslationUtils translator = new ItemStackTranslationUtils(
                new MockAdventureParser(), new DefaultFeatureSyntax(), false, translations);
        ItemStack original = enchanted(ComponentTypes.ENCHANTMENTS, Map.of(custom, 5));
        Component line = Component.empty().append(custom.getDescription().color(NamedTextColor.GRAY))
                .append(Component.text(" V"));
        original.setComponent(ComponentTypes.LORE, new ItemLore(List.of(line, line, Component.text("Unrelated lore"))));
        original.setComponent(ComponentTypes.TOOLTIP_DISPLAY,
                new ItemTooltipDisplay(false, Set.of(ComponentTypes.ENCHANTMENTS)));
        ItemStack translated = translator.translateItem(original, locale("pt_BR")).getModified();
        assertEquals(2, lore(translated).size());
        assertEquals("Prismático V", ComponentUtils.componentToString(lore(translated).getFirst()));
        assertEquals(Component.text("Unrelated lore"), lore(translated).get(1));
        assertEquals(3, lore(original).size());
        assertEquals(original.getComponent(ComponentTypes.ENCHANTMENTS), translated.getComponent(ComponentTypes.ENCHANTMENTS));
        assertFalse(translator.translateItem(translated, locale("pt_BR")).isModified());
    }

    @Test
    void recoversHiddenTooltipsOnlyForConfiguredPlayersAcrossFreshTranslators() {
        String uuid = "fb88dd27-c2a0-3296-8ce7-9bdd93d5692c";
        Localized player = (Localized) Proxy.newProxyInstance(Localized.class.getClassLoader(),
                new Class<?>[]{com.rexcantor64.triton.api.players.LanguagePlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUUID" -> java.util.UUID.fromString(uuid);
                    case "getLanguageId" -> "pt_BR";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        for (var type : List.of(ComponentTypes.ENCHANTMENTS, ComponentTypes.STORED_ENCHANTMENTS)) {
            ItemStack original = enchanted(type, Map.of(EnchantmentTypes.SHARPNESS, 5));
            original.unsetComponent(ComponentTypes.LORE);
            ItemTooltipDisplay hidden = new ItemTooltipDisplay(false, Set.of(type, ComponentTypes.ATTRIBUTE_MODIFIERS));
            original.setComponent(ComponentTypes.TOOLTIP_DISPLAY, hidden);
            for (int restart = 0; restart < 2; restart++) {
                var translator = new ItemStackTranslationUtils(new MockAdventureParser(), new DefaultFeatureSyntax(),
                        false, null, List.of(uuid));
                var translated = translator.translateItem(original, player).getModified();
                assertNotNull(translated);
                assertEquals(1, lore(translated).size());
                assertEquals(original.getComponent(type), translated.getComponent(type));
                assertEquals(hidden, translated.getComponent(ComponentTypes.TOOLTIP_DISPLAY).orElseThrow());
                assertFalse(translator.translateItem(translated, player).isModified());
                assertFalse(translator.translateItem(original, locale).isModified());
                assertFalse(handler.translateItem(original, player).isModified());
                assertTrue(original.getComponent(ComponentTypes.LORE).isEmpty());
                assertEquals(hidden, original.getComponent(ComponentTypes.TOOLTIP_DISPLAY).orElseThrow());
                for (var display : List.of(new ItemTooltipDisplay(true, Set.of(type)),
                        new ItemTooltipDisplay(false, Set.of(type, ComponentTypes.LORE)))) {
                    ItemStack privateItem = original.copy();
                    privateItem.setComponent(ComponentTypes.TOOLTIP_DISPLAY, display);
                    assertFalse(translator.translateItem(privateItem, player).isModified());
                }
            }
        }
    }

    @Test
    void keepsOriginallyHiddenTrimmedArmorWithoutLoreHidden() {
        ItemStack original = ItemStack.builder().type(ItemTypes.GOLDEN_CHESTPLATE).build();
        ArmorTrim trim = new ArmorTrim(TrimMaterials.RESIN, TrimPatterns.SILENCE);
        original.setComponent(ComponentTypes.TRIM, trim);
        original.setComponent(ComponentTypes.ENCHANTMENTS, new ItemEnchantments(Map.of(EnchantmentTypes.MENDING, 3,
                EnchantmentTypes.UNBREAKING, 3, EnchantmentTypes.ALL_DAMAGE_PROTECTION, 4)));
        original.unsetComponent(ComponentTypes.LORE);
        original.setComponent(ComponentTypes.TOOLTIP_DISPLAY,
                new ItemTooltipDisplay(false, Set.of(ComponentTypes.ENCHANTMENTS)));
        assertFalse(handler.translateItem(original, locale).isModified());
        assertEquals(trim, original.getComponent(ComponentTypes.TRIM).orElseThrow());
    }

    @Test
    void translatesLegacyKeysOnMultipleEnchantmentsAndPreservesGoldenArmorTrim() {
        Map<String, Map<String, String>> names = Map.of(
                "pt_BR", Map.of("Enchantments.protection.name", "Proteção", "Enchantments.unbreaking.name", "Durabilidade"),
                "es_MX", Map.of("Enchantments.protection.name", "Protección", "Enchantments.unbreaking.name", "Irrompibilidad"));
        TranslationManager translations = (TranslationManager) Proxy.newProxyInstance(
                TranslationManager.class.getClassLoader(), new Class<?>[]{TranslationManager.class},
                (proxy, method, args) -> Optional.ofNullable(names.get(((Localized) args[0]).getLanguageId()).get(args[1]))
                        .map(Component::text));
        ItemStackTranslationUtils translator = new ItemStackTranslationUtils(
                new MockAdventureParser(), new DefaultFeatureSyntax(), false, translations);
        ItemStack original = ItemStack.builder().type(ItemTypes.GOLDEN_CHESTPLATE).build();
        ArmorTrim trim = new ArmorTrim(TrimMaterials.AMETHYST, TrimPatterns.SENTRY);
        original.setComponent(ComponentTypes.TRIM, trim);
        Map<EnchantmentType, Integer> levels = new LinkedHashMap<>();
        levels.put(EnchantmentTypes.ALL_DAMAGE_PROTECTION, 4);
        levels.put(EnchantmentTypes.UNBREAKING, 3);
        original.setComponent(ComponentTypes.ENCHANTMENTS, new ItemEnchantments(levels));
        for (String id : List.of("pt_BR", "es_MX")) {
            ItemStack translated = translator.translateItem(original, locale(id)).getModified();
            assertEquals(2, lore(translated).size());
            assertTrue(ComponentUtils.componentToString(lore(translated).getFirst()).startsWith(names.get(id).get("Enchantments.protection.name")));
            assertTrue(ComponentUtils.componentToString(lore(translated).get(1)).startsWith(names.get(id).get("Enchantments.unbreaking.name")));
            assertEquals(original.getComponent(ComponentTypes.ENCHANTMENTS), translated.getComponent(ComponentTypes.ENCHANTMENTS));
            assertEquals(trim, translated.getComponent(ComponentTypes.TRIM).orElseThrow());
        }
        assertTrue(lore(original).isEmpty());
        assertTrue(original.getComponent(ComponentTypes.TOOLTIP_DISPLAY).orElseThrow().getHiddenComponents().isEmpty());
    }

    @Test
    void keepsUnregisteredEnchantmentsAndCurseStyleOnBooks() {
        Map<EnchantmentType, Integer> levels = new LinkedHashMap<>();
        levels.put(EnchantmentTypes.SHARPNESS, 3);
        levels.put(EnchantmentTypes.VANISHING_CURSE, 1);
        ItemStack original = enchanted(ComponentTypes.STORED_ENCHANTMENTS, levels);
        ItemStack translated = handler.translateItem(original, locale).getModified();
        assertEquals(2, lore(translated).size());
        assertEquals(NamedTextColor.RED, lore(translated).get(1).color());
        assertEquals(Component.translatable("enchantment.minecraft.vanishing_curse")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false), lore(translated).get(1));
        assertEquals(original.getComponent(ComponentTypes.STORED_ENCHANTMENTS), translated.getComponent(ComponentTypes.STORED_ENCHANTMENTS));
        assertTrue(translated.getComponent(ComponentTypes.TOOLTIP_DISPLAY).orElseThrow()
                .getHiddenComponents().contains(ComponentTypes.STORED_ENCHANTMENTS));
    }

    @Test
    void translatesCustomDescriptionsNamesAndLoreWithoutMutatingOriginal() {
        EnchantmentType custom = (EnchantmentType) Proxy.newProxyInstance(EnchantmentType.class.getClassLoader(),
                new Class<?>[]{EnchantmentType.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getDescription" -> Component.text("[lang]custom.enchantment[/lang]");
                    case "getName" -> new com.github.retrooper.packetevents.resources.ResourceLocation("test:custom");
                    case "getDefinition" -> EnchantmentTypes.SHARPNESS.getDefinition();
                    case "hashCode" -> 123;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ItemStack original = enchanted(ComponentTypes.ENCHANTMENTS, Map.of(custom, 11));
        original.setComponent(ComponentTypes.CUSTOM_NAME, Component.text("[lang]item.name[/lang]"));
        original.setComponent(ComponentTypes.LORE, new ItemLore(List.of(Component.text("[lang]item.lore[/lang]"))));
        ItemStack translated = handler.translateItem(original, locale).getModified();
        assertEquals("replaced(custom.enchantment) XI", ComponentUtils.componentToString(lore(translated).getFirst()));
        assertEquals("replaced(item.lore)", ComponentUtils.componentToString(lore(translated).get(1)));
        assertEquals("replaced(item.name)", ComponentUtils.componentToString(translated.getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow()));
        assertEquals(original.getComponent(ComponentTypes.ENCHANTMENTS), translated.getComponent(ComponentTypes.ENCHANTMENTS));
        assertEquals(Component.text("[lang]item.lore[/lang]"), lore(original).getFirst());
        assertFalse(handler.translateItem(translated, locale).isModified());
    }

    @Test
    void respectsHiddenComponentsAndWholeTooltip() {
        for (ItemTooltipDisplay display : List.of(
                new ItemTooltipDisplay(false, Set.of(ComponentTypes.ENCHANTMENTS)),
                new ItemTooltipDisplay(false, Set.of(ComponentTypes.LORE)),
                new ItemTooltipDisplay(true, Set.of()))) {
            ItemStack original = enchanted(ComponentTypes.ENCHANTMENTS, Map.of(EnchantmentTypes.SHARPNESS, 3));
            original.setComponent(ComponentTypes.TOOLTIP_DISPLAY, display);
            assertFalse(handler.translateItem(original, locale).isModified());
            assertTrue(original.getComponent(ComponentTypes.LORE).orElseThrow().getLines().isEmpty());
        }
    }

    @Test
    void respectsLegacyVisibilityAndCopiesEnchantmentMap() {
        PacketEvents.setAPI(new MockPacketEventsAPI() {
            @Override public com.github.retrooper.packetevents.manager.server.ServerManager getServerManager() {
                return () -> ServerVersion.V_1_21_4;
            }
        });
        ItemStack original = enchanted(ComponentTypes.ENCHANTMENTS, Map.of(EnchantmentTypes.SHARPNESS, 3));
        original.setComponent(ComponentTypes.ENCHANTMENTS, new ItemEnchantments(Map.of(EnchantmentTypes.SHARPNESS, 3), false));
        assertFalse(handler.translateItem(original, locale).isModified());
        original.setComponent(ComponentTypes.ENCHANTMENTS, new ItemEnchantments(Map.of(EnchantmentTypes.SHARPNESS, 3), true));
        ItemStack translated = handler.translateItem(original, locale).getModified();
        assertTrue(original.getComponent(ComponentTypes.ENCHANTMENTS).orElseThrow().isShowInTooltip());
        assertFalse(translated.getComponent(ComponentTypes.ENCHANTMENTS).orElseThrow().isShowInTooltip());
        assertEquals(original.getComponent(ComponentTypes.ENCHANTMENTS).orElseThrow().getEnchantments(),
                translated.getComponent(ComponentTypes.ENCHANTMENTS).orElseThrow().getEnchantments());
    }

    @Test
    void removesStructuralDuplicatesButKeepsUnrelatedLoreAndHiddenComponents() {
        ItemStack original = enchanted(ComponentTypes.ENCHANTMENTS, Map.of(EnchantmentTypes.SHARPNESS, 3));
        Component existing = Component.translatable("enchantment.minecraft.sharpness")
                .append(Component.text(" ")).append(Component.translatable("enchantment.level.3"));
        original.setComponent(ComponentTypes.LORE, new ItemLore(List.of(existing, existing, Component.text("Sharpness is useful"))));
        original.setComponent(ComponentTypes.TOOLTIP_DISPLAY, new ItemTooltipDisplay(false, Set.of(ComponentTypes.ATTRIBUTE_MODIFIERS)));
        ItemStack translated = handler.translateItem(original, locale).getModified();
        assertEquals(List.of(existing, Component.text("Sharpness is useful")), lore(translated));
        assertEquals(Set.of(ComponentTypes.ENCHANTMENTS, ComponentTypes.ATTRIBUTE_MODIFIERS),
                translated.getComponent(ComponentTypes.TOOLTIP_DISPLAY).orElseThrow().getHiddenComponents());
        assertEquals(3, lore(original).size());
        assertFalse(handler.translateItem(translated, locale).isModified());
    }
}
