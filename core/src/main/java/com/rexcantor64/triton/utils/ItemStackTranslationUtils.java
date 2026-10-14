package com.rexcantor64.triton.utils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemContainerContents;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemEnchantments;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTooltipDisplay;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WrittenBookContent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentType;
import com.github.retrooper.packetevents.util.Filterable;
import com.rexcantor64.triton.Triton;
import com.rexcantor64.triton.api.language.Localized;
import com.rexcantor64.triton.api.language.TranslationManager;
import com.rexcantor64.triton.config.MainConfig;
import com.rexcantor64.triton.language.parser.MessageParser;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.Style;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class ItemStackTranslationUtils {

    private final @NotNull MessageParser parser;
    private final @NotNull MainConfig.FeatureSyntax syntax;
    private final boolean translateBooks;
    private final @Nullable TranslationManager translations;

    public ItemStackTranslationUtils(MessageParser parser, MainConfig.FeatureSyntax syntax, boolean translateBooks) {
        this(parser, syntax, translateBooks, Triton.get() == null ? null : Triton.get().getTranslationManager());
    }

    public ModifiableItemStack translateItem(ItemStack item, Localized locale) {
        val modifiableItem = new ModifiableItemStack(item);
        this.translateItem(modifiableItem, locale, true, true);
        return modifiableItem;
    }

    private void translateItem(ModifiableItemStack item, Localized locale, boolean translateLore, boolean translateBook) {
        logPacketEventsAudit("before", item, locale, translateLore, translateBook);
        translateItemName(item, locale);
        translateCustomName(item, locale);
        if (translateLore) {
            translateLore(item, locale);
            translateEnchantments(item, locale);
            translateContainerContents(item, locale);
        }
        if (translateBook && this.translateBooks) {
            translateBook(item, locale);
        }
        logPacketEventsAudit("after", item, locale, translateLore, translateBook);
    }

    private void translateItemName(ModifiableItemStack item, Localized locale) {
        val itemNameOpt = item.getOriginal().getComponent(ComponentTypes.ITEM_NAME);
        if (!itemNameOpt.isPresent()) {
            return;
        }

        val itemName = itemNameOpt.get();
        parser.translateComponent(
                        itemName,
                        locale,
                        this.syntax
                )
                .map(ComponentUtils::ensureNotItalic)
                .ifChanged(result -> item.willModify().setComponent(ComponentTypes.ITEM_NAME, result))
                .ifToRemove(() -> item.willModify().unsetComponent(ComponentTypes.ITEM_NAME));
    }

    private void translateCustomName(ModifiableItemStack item, Localized locale) {
        val itemNameOpt = item.getOriginal().getComponent(ComponentTypes.CUSTOM_NAME);
        if (!itemNameOpt.isPresent()) {
            return;
        }

        val itemName = itemNameOpt.get();
        parser.translateComponent(
                        itemName,
                        locale,
                        this.syntax
                )
                .map(ComponentUtils::ensureNotItalic)
                .ifChanged(result -> item.willModify().setComponent(ComponentTypes.CUSTOM_NAME, result))
                .ifToRemove(() -> item.willModify().unsetComponent(ComponentTypes.CUSTOM_NAME));
    }

    private void translateLore(ModifiableItemStack item, Localized locale) {
        val loreOpt = item.getOriginal().getComponent(ComponentTypes.LORE);
        if (!loreOpt.isPresent()) {
            return;
        }

        val lore = loreOpt.get();
        val enchantmentTranslations = getEnchantmentLoreTranslations(item, locale);
        AtomicBoolean loreChanged = new AtomicBoolean(false);
        List<Component> newLines = new ArrayList<>();
        Set<Component> seenEnchantmentLines = new HashSet<>();
        for (Component line : lore.getLines()) {
            val translatedLine = translateEnchantmentLore(line, enchantmentTranslations);
            if (!translatedLine.equals(line)) {
                loreChanged.set(true);
            }
            if (!enchantmentTranslations.isEmpty() && containsEnchantmentLore(line, enchantmentTranslations)
                    && !seenEnchantmentLines.add(translatedLine)) {
                loreChanged.set(true);
                continue;
            }
            parser.translateComponent(
                            translatedLine,
                            locale,
                            this.syntax
                    )
                    .map(ComponentUtils::splitByNewLine)
                    .ifChanged(result -> {
                        newLines.addAll(
                                result.stream()
                                        .map(ComponentUtils::ensureNotItalic)
                                        .collect(Collectors.toList())
                        );
                        loreChanged.set(true);
                    })
                    .ifUnchanged(() -> newLines.add(translatedLine))
                    .ifToRemove(() -> loreChanged.set(true));
        }
        if (!loreChanged.get()) {
            return;
        }
        item.willModify().setComponent(ComponentTypes.LORE, new ItemLore(newLines));
    }

    private Map<Component, Component> getEnchantmentLoreTranslations(ModifiableItemStack item, Localized locale) {
        Map<Component, Component> result = new HashMap<>();
        if (translations == null) {
            return result;
        }
        for (val type : List.of(ComponentTypes.ENCHANTMENTS, ComponentTypes.STORED_ENCHANTMENTS)) {
            getCurrentComponent(item, type).ifPresent(enchantments -> {
                for (val entry : enchantments) {
                    val enchantment = entry.getKey();
                    val description = enchantment.getDescription();
                    if (description instanceof TranslatableComponent
                            || description instanceof TextComponent && !((TextComponent) description).content().isEmpty()) {
                        getTranslatedEnchantmentDescription(enchantment, locale).ifPresent(translated -> {
                            result.put(description.style(Style.empty()).children(List.of()), translated);
                            // ponytail: old plain lore has no IDs; match exact registered names on this item, prefer translatable descriptions for new lore.
                            if (description instanceof TextComponent && Triton.get() != null) {
                                for (val language : Triton.get().getConfig().getLanguages()) {
                                    getTranslatedEnchantmentDescription(enchantment, () -> language).ifPresent(source -> {
                                        val text = ComponentUtils.componentToString(source);
                                        if (!text.isBlank()) {
                                            result.put(Component.text(text), translated);
                                        }
                                    });
                                }
                            }
                        });
                    }
                }
            });
        }
        return result;
    }

    private boolean containsEnchantmentLore(Component line, Map<Component, Component> replacements) {
        return replacements.containsKey(line.style(Style.empty()).children(List.of()))
                || line.children().stream().anyMatch(child -> containsEnchantmentLore(child, replacements));
    }

    private Component translateEnchantmentLore(Component line, Map<Component, Component> replacements) {
        if (replacements.isEmpty()) {
            return line;
        }
        val children = line.children().stream().map(child -> translateEnchantmentLore(child, replacements)).toList();
        val translated = replacements.get(line.style(Style.empty()).children(List.of()));
        if (translated != null) {
            Component result = translated.applyFallbackStyle(line.style());
            for (val child : children) {
                result = result.append(child);
            }
            return result;
        }
        return line.children(children);
    }

    private void translateEnchantments(ModifiableItemStack item, Localized locale) {
        val loreMatcher = LoreEnchantmentMatcher.from(getCurrentComponent(item, ComponentTypes.LORE));
        List<Component> enchantmentLines = new ArrayList<>();
        boolean hasEnchantments = false;

        hasEnchantments |= translateEnchantmentsComponent(
                item,
                locale,
                ComponentTypes.ENCHANTMENTS,
                loreMatcher,
                enchantmentLines
        );
        hasEnchantments |= translateEnchantmentsComponent(
                item,
                locale,
                ComponentTypes.STORED_ENCHANTMENTS,
                loreMatcher,
                enchantmentLines
        );

        if (!hasEnchantments) {
            return;
        }
        if (enchantmentLines.isEmpty() && !loreMatcher.hasDuplicateLoreLines()) {
            return;
        }

        enchantmentLines.addAll(loreMatcher.getDeduplicatedLoreLines());
        item.willModify().setComponent(ComponentTypes.LORE, new ItemLore(enchantmentLines));
    }

    private boolean translateEnchantmentsComponent(ModifiableItemStack item, Localized locale, ComponentType<ItemEnchantments> componentType, LoreEnchantmentMatcher loreMatcher, List<Component> destination) {
        val enchantmentsOpt = getCurrentComponent(item, componentType);
        if (!enchantmentsOpt.isPresent()) {
            return false;
        }

        val enchantments = enchantmentsOpt.get();
        if (enchantments.isEmpty()) {
            return false;
        }

        boolean visible = isEnchantmentComponentVisible(item, componentType, enchantments);
        if (!visible) {
            return false;
        }
        for (val enchantment : enchantments) {
            val translatedLines = getTranslatedEnchantmentTooltipLines(enchantment.getKey(), enchantment.getValue(), locale);
            if (translatedLines.isEmpty()) {
                continue;
            }
            if (loreMatcher.containsEnchantment(enchantment.getKey(), translatedLines)) {
                continue;
            }

            translatedLines.forEach(loreMatcher::addLoreLine);
            destination.addAll(translatedLines);
        }

        if (!hideComponentInTooltipDisplay(item, componentType)) {
            item.willModify().setComponent(
                    componentType,
                    new ItemEnchantments(new LinkedHashMap<>(enchantments.getEnchantments()), false)
            );
        }
        return true;
    }

    private List<Component> getTranslatedEnchantmentTooltipLines(EnchantmentType enchantment, int level, Localized locale) {
        val line = getEnchantmentTooltipLine(enchantment, level, locale);
        val translatedLines = new ArrayList<Component>();
        val translationResult = parser.translateComponent(
                line,
                locale,
                this.syntax
        );

        translationResult
                .ifChanged(result -> translatedLines.addAll(
                        ComponentUtils.splitByNewLine(result).stream()
                                .map(translatedLine -> styleEnchantmentTooltipLine(enchantment, translatedLine))
                                .collect(Collectors.toList())
                ))
                .ifUnchanged(() -> translatedLines.add(styleEnchantmentTooltipLine(enchantment, line)));
        return translatedLines;
    }

    private boolean isEnchantmentComponentVisible(ModifiableItemStack item, ComponentType<ItemEnchantments> componentType, ItemEnchantments enchantments) {
        if (usesTooltipDisplay()) {
            return !isComponentHiddenInTooltipDisplay(item, componentType)
                    && !isComponentHiddenInTooltipDisplay(item, ComponentTypes.LORE)
                    && !getCurrentComponent(item, ComponentTypes.TOOLTIP_DISPLAY)
                    .map(ItemTooltipDisplay::isHideTooltip).orElse(false);
        }
        return enchantments.isShowInTooltip();
    }

    private boolean hideComponentInTooltipDisplay(ModifiableItemStack item, ComponentType<?> componentType) {
        if (!usesTooltipDisplay()) {
            return false;
        }

        val tooltipDisplayOpt = getCurrentComponent(item, ComponentTypes.TOOLTIP_DISPLAY);
        boolean hideTooltip = tooltipDisplayOpt.map(ItemTooltipDisplay::isHideTooltip).orElse(false);
        Set<ComponentType<?>> hiddenComponents = tooltipDisplayOpt
                .map(ItemTooltipDisplay::getHiddenComponents)
                .map(HashSet::new)
                .orElseGet(HashSet::new);
        hiddenComponents.add(componentType);
        item.willModify().setComponent(ComponentTypes.TOOLTIP_DISPLAY, new ItemTooltipDisplay(hideTooltip, hiddenComponents));
        return true;
    }

    private boolean isComponentHiddenInTooltipDisplay(ModifiableItemStack item, ComponentType<?> componentType) {
        return getCurrentComponent(item, ComponentTypes.TOOLTIP_DISPLAY)
                .map(ItemTooltipDisplay::getHiddenComponents)
                .map(hiddenComponents -> hiddenComponents.contains(componentType))
                .orElse(false);
    }

    private boolean usesTooltipDisplay() {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_5);
    }

    private Component getEnchantmentTooltipLine(EnchantmentType enchantment, int level, Localized locale) {
        Component line = getTranslatedEnchantmentDescription(enchantment, locale).orElseGet(enchantment::getDescription);
        val definition = enchantment.getDefinition();
        if (level != 1 || definition == null || definition.getMaxLevel() != 1) {
            line = line.append(Component.text(" ")).append(getEnchantmentLevelComponent(level, locale));
        }
        return line;
    }

    private Optional<Component> getTranslatedEnchantmentDescription(EnchantmentType enchantment, Localized locale) {
        if (translations == null) {
            return Optional.empty();
        }
        val description = enchantment.getDescription();
        val translated = description instanceof TranslatableComponent
                ? translations.getTextComponent(locale, ((TranslatableComponent) description).key())
                : Optional.<Component>empty();
        val name = enchantment.getName();
        val legacyId = "minecraft".equals(name.getNamespace()) ? name.getKey() : name.toString();
        return translated.or(() -> translations.getTextComponent(locale, "Enchantments." + legacyId + ".name"));
    }

    private Component getEnchantmentLevelComponent(int level, Localized locale) {
        if (level >= 1 && level <= 10) {
            val key = "enchantment.level." + level;
            return translations == null ? Component.translatable(key)
                    : translations.getTextComponent(locale, key).orElseGet(() -> Component.translatable(key));
        }
        return Component.text(toRomanNumeral(level));
    }

    private String toRomanNumeral(int number) {
        if (number <= 0) {
            return String.valueOf(number);
        }

        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (number >= values[i]) {
                result.append(numerals[i]);
                number -= values[i];
            }
        }
        return result.toString();
    }

    private Component styleEnchantmentTooltipLine(EnchantmentType enchantment, Component line) {
        val styledLine = isCurse(enchantment)
                ? line.color(NamedTextColor.RED)
                : line.colorIfAbsent(NamedTextColor.GRAY);
        return styledLine
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private boolean isCurse(EnchantmentType enchantment) {
        val name = enchantment.getName();
        return ("minecraft".equals(name.getNamespace()) || name.getNamespace().isEmpty())
                && ("binding_curse".equals(name.getKey()) || "vanishing_curse".equals(name.getKey()));
    }

    private <T> Optional<T> getCurrentComponent(ModifiableItemStack item, ComponentType<T> componentType) {
        if (item.isModified()) {
            return item.getModified().getComponent(componentType);
        }
        return item.getOriginal().getComponent(componentType);
    }

    private void logPacketEventsAudit(String phase, ModifiableItemStack item, Localized locale, boolean translateLore, boolean translateBook) {
        if (Triton.get() == null || Triton.get().getConfig().getLogLevel() < 2) {
            return;
        }
        if (getCurrentComponent(item, ComponentTypes.ENCHANTMENTS).map(ItemEnchantments::isEmpty).orElse(true)
                && getCurrentComponent(item, ComponentTypes.STORED_ENCHANTMENTS).map(ItemEnchantments::isEmpty).orElse(true)) {
            return;
        }
        try {
            Triton.get().getLogger().logTrace(
                    "[EnchantAudit] PacketEvents phase=%1 type=%2 lang=%3 modified=%4 translateLore=%5 translateBook=%6 lore=%7 enchantments=%8 storedEnchantments=%9 tooltipDisplay=%10",
                    phase,
                    item.getOriginal().getType().getName().getKey(),
                    locale.getLanguageId(),
                    item.isModified(),
                    translateLore,
                    translateBook,
                    describeLore(getCurrentComponent(item, ComponentTypes.LORE)),
                    describeEnchantments(getCurrentComponent(item, ComponentTypes.ENCHANTMENTS)),
                    describeEnchantments(getCurrentComponent(item, ComponentTypes.STORED_ENCHANTMENTS)),
                    describeTooltipDisplay(getCurrentComponent(item, ComponentTypes.TOOLTIP_DISPLAY))
            );
        } catch (Exception e) {
            Triton.get().getLogger().logTrace(
                    "[EnchantAudit] PacketEvents phase=%1 failed to inspect item: %2",
                    phase,
                    e.getMessage()
            );
        }
    }

    private String describeLore(Optional<ItemLore> loreOpt) {
        if (!loreOpt.isPresent()) {
            return "absent";
        }
        return "lines=" + loreOpt.get().getLines().size();
    }

    private String describeEnchantments(Optional<ItemEnchantments> enchantmentsOpt) {
        if (!enchantmentsOpt.isPresent()) {
            return "absent";
        }

        val enchantments = enchantmentsOpt.get();
        List<String> entries = new ArrayList<>();
        int count = 0;
        for (val enchantment : enchantments) {
            if (count < 8) {
                entries.add(enchantment.getKey().getName() + "=" + enchantment.getValue());
            }
            count++;
        }
        if (count > entries.size()) {
            entries.add("+" + (count - entries.size()) + " more");
        }
        return "size=" + count
                + " empty=" + enchantments.isEmpty()
                + " showInTooltip=" + enchantments.isShowInTooltip()
                + " entries=" + entries;
    }

    private String describeTooltipDisplay(Optional<ItemTooltipDisplay> tooltipDisplayOpt) {
        if (!tooltipDisplayOpt.isPresent()) {
            return "absent";
        }

        val tooltipDisplay = tooltipDisplayOpt.get();
        List<String> hiddenComponents = tooltipDisplay.getHiddenComponents().stream()
                .map(componentType -> componentType.getName().getKey())
                .sorted()
                .collect(Collectors.toList());
        return "hideTooltip=" + tooltipDisplay.isHideTooltip()
                + " hidden=" + hiddenComponents;
    }

    private static class LoreEnchantmentMatcher {
        private final List<Component> originalLoreLines = new ArrayList<>();
        private final List<Component> knownLoreLines = new ArrayList<>();
        private final List<Integer> knownOriginalIndexes = new ArrayList<>();
        private final Set<Integer> duplicateOriginalIndexes = new HashSet<>();

        private static LoreEnchantmentMatcher from(Optional<ItemLore> loreOpt) {
            val matcher = new LoreEnchantmentMatcher();
            loreOpt.ifPresent(lore -> lore.getLines().forEach(matcher::addOriginalLoreLine));
            return matcher;
        }

        private boolean containsEnchantment(EnchantmentType enchantment, List<Component> generatedLines) {
            val translatableCandidates = collectTranslatableKeys(enchantment.getDescription());
            val exactPlainCandidates = collectExactPlainCandidates(enchantment, generatedLines);
            boolean found = false;
            List<Integer> matchingOriginalIndexes = new ArrayList<>();

            for (int i = 0; i < knownLoreLines.size(); i++) {
                if (matchesEnchantment(knownLoreLines.get(i), translatableCandidates, exactPlainCandidates)) {
                    found = true;
                    val originalIndex = knownOriginalIndexes.get(i);
                    if (originalIndex >= 0 && !matchingOriginalIndexes.contains(originalIndex)) {
                        matchingOriginalIndexes.add(originalIndex);
                    }
                }
            }

            for (int i = 1; i < matchingOriginalIndexes.size(); i++) {
                duplicateOriginalIndexes.add(matchingOriginalIndexes.get(i));
            }
            return found;
        }

        private boolean hasDuplicateLoreLines() {
            return !duplicateOriginalIndexes.isEmpty();
        }

        private List<Component> getDeduplicatedLoreLines() {
            if (!hasDuplicateLoreLines()) {
                return new ArrayList<>(originalLoreLines);
            }

            List<Component> result = new ArrayList<>(originalLoreLines.size() - duplicateOriginalIndexes.size());
            for (int i = 0; i < originalLoreLines.size(); i++) {
                if (!duplicateOriginalIndexes.contains(i)) {
                    result.add(originalLoreLines.get(i));
                }
            }
            return result;
        }

        private void addOriginalLoreLine(Component line) {
            val originalIndex = originalLoreLines.size();
            originalLoreLines.add(line);
            addKnownLoreLine(line, originalIndex);
        }

        private void addLoreLine(Component line) {
            addKnownLoreLine(line, -1);
        }

        private void addKnownLoreLine(Component line, int originalIndex) {
            knownLoreLines.add(line);
            knownOriginalIndexes.add(originalIndex);
        }

        private static boolean matchesEnchantment(Component line, Set<String> translatableCandidates, List<String> exactPlainCandidates) {
            val lineTranslatableKeys = collectTranslatableKeys(line);
            for (String key : translatableCandidates) {
                if (lineTranslatableKeys.contains(key)) {
                    return true;
                }
            }

            val plainLine = normalizePlainText(ComponentUtils.componentToString(line));
            if (!isMeaningfulPlainText(plainLine)) {
                return false;
            }
            for (String candidate : exactPlainCandidates) {
                if (plainLine.equals(candidate)) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> collectExactPlainCandidates(EnchantmentType enchantment, List<Component> generatedLines) {
            List<String> candidates = new ArrayList<>();
            addPlainCandidate(candidates, enchantment.getDescription());
            for (Component line : generatedLines) {
                addPlainCandidate(candidates, line);
            }
            addPlainCandidate(candidates, enchantment.getName().toString());
            return candidates;
        }

        private static void addPlainCandidate(List<String> candidates, Component component) {
            addPlainCandidate(candidates, ComponentUtils.componentToString(component));
        }

        private static void addPlainCandidate(List<String> candidates, String text) {
            val candidate = normalizePlainText(text);
            if (isMeaningfulPlainText(candidate) && !candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }

        private static Set<String> collectTranslatableKeys(Component component) {
            Set<String> keys = new HashSet<>();
            collectTranslatableKeys(component, keys);
            return keys;
        }

        private static void collectTranslatableKeys(Component component, Set<String> keys) {
            if (component instanceof TranslatableComponent) {
                val translatableComponent = (TranslatableComponent) component;
                keys.add(translatableComponent.key().toLowerCase(Locale.ROOT));
                for (TranslationArgument argument : translatableComponent.arguments()) {
                    if (argument.value() instanceof Component) {
                        collectTranslatableKeys((Component) argument.value(), keys);
                    }
                }
            }
            for (Component child : component.children()) {
                collectTranslatableKeys(child, keys);
            }
        }

        private static String normalizePlainText(String text) {
            return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }

        private static boolean isMeaningfulPlainText(String text) {
            for (int i = 0; i < text.length(); i++) {
                val character = text.charAt(i);
                if (!Character.isWhitespace(character) && character != '?') {
                    return true;
                }
            }
            return false;
        }
    }

    private void translateContainerContents(ModifiableItemStack item, Localized locale) {
        val containerContentsOpt = item.getOriginal().getComponent(ComponentTypes.CONTAINER);
        if (!containerContentsOpt.isPresent()) {
            return;
        }

        boolean contentsChanged = false;
        val containerContents = containerContentsOpt.get();
        val newContents = new ArrayList<ItemStack>(containerContents.getItems().size());
        for (val itemStack : containerContents.getItems()) {
            val modifiableItem = new ModifiableItemStack(itemStack);
            translateItem(modifiableItem, locale, false, false);
            if (modifiableItem.isModified()) {
                newContents.add(modifiableItem.getModified());
                contentsChanged = true;
            } else {
                newContents.add(itemStack);
            }
        }

        if (contentsChanged) {
            item.willModify().setComponent(ComponentTypes.CONTAINER, new ItemContainerContents(newContents));
        }
    }

    private void translateBook(ModifiableItemStack item, Localized locale) {
        val bookContentOpt = item.getOriginal().getComponent(ComponentTypes.WRITTEN_BOOK_CONTENT);
        if (!bookContentOpt.isPresent()) {
            return;
        }

        boolean contentsChanged = false;
        val bookContent = bookContentOpt.get();
        val newPages = new ArrayList<Filterable<@NotNull Component>>(bookContent.getPages().size());
        for (val page : bookContent.getPages()) {
            val translationResult = parser.translateComponent(
                    page.getRaw(),
                    locale,
                    syntax
            );
            translationResult
                    .ifChanged(result -> {
                        newPages.add(new Filterable<>(result, page.getFiltered()));
                    })
                    .ifUnchanged(() -> newPages.add(page));
            contentsChanged |= !translationResult.isUnchanged();
        }

        if (contentsChanged) {
            item.willModify().setComponent(
                    ComponentTypes.WRITTEN_BOOK_CONTENT,
                    new WrittenBookContent(
                            bookContent.getTitle(),
                            bookContent.getAuthor(),
                            bookContent.getGeneration(),
                            newPages,
                            bookContent.isResolved()
                    )
            );
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class ModifiableItemStack {
        private final @NotNull ItemStack original;
        private @Nullable ItemStack modified = null;

        public boolean isModified() {
            return this.modified != null;
        }

        public @NotNull ItemStack willModify() {
            if (this.modified == null) {
                this.modified = this.original.copy();
            }
            return this.modified;
        }
    }

}
