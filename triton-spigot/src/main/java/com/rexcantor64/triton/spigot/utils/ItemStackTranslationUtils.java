package com.rexcantor64.triton.spigot.utils;

import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.MethodAccessor;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.nbt.NbtBase;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import com.comphenix.protocol.wrappers.nbt.NbtList;
import com.google.gson.JsonSyntaxException;
import com.rexcantor64.triton.Triton;
import com.rexcantor64.triton.api.language.Localized;
import com.rexcantor64.triton.spigot.SpigotTriton;
import com.rexcantor64.triton.spigot.wrappers.items.WrappedFilterable;
import com.rexcantor64.triton.spigot.wrappers.items.WrappedItemContainerContents;
import com.rexcantor64.triton.spigot.wrappers.items.WrappedItemLore;
import com.rexcantor64.triton.spigot.wrappers.items.WrappedPatchedDataComponentMap;
import com.rexcantor64.triton.spigot.wrappers.items.WrappedWrittenBookContent;
import com.rexcantor64.triton.utils.ComponentUtils;
import lombok.val;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ItemStackTranslationUtils {

    private static final MethodAccessor NBT_DESERIALIZER_METHOD;

    static {
        val mojangsonParserClass = MinecraftReflection.getMinecraftClass("nbt.TagParser", "nbt.MojangsonParser", "MojangsonParser");
        FuzzyReflection fuzzy = FuzzyReflection.fromClass(mojangsonParserClass);
        val method = fuzzy.getMethodByReturnTypeAndParameters("deserializeNbtCompound", MinecraftReflection.getNBTCompoundClass(), String.class);
        NBT_DESERIALIZER_METHOD = Accessors.getMethodAccessor(method);
    }

    /**
     * Translates an item stack in one of two ways:
     * - if the item has a CraftBukkit handler, the item is translated through its NBT tag;
     * - otherwise, Bukkit's ItemMeta API is used instead.
     * <p>
     * Special attention is given to Shulker Boxes (the names of the items inside them are also translated for preview purposes)
     * and to Written Books (their text is also translated).
     *
     * @param item           The item to translate. Might be mutated
     * @param languagePlayer The language player to translate for
     * @param translateBooks Whether it should translate written books
     * @return The translated item stack, which may or may not be the same as the given parameter
     */
    @Contract("null, _, _ -> null; !null, _, _ -> !null")
    public static @Nullable ItemStack translateItemStack(@Nullable ItemStack item, @NotNull Localized languagePlayer, boolean translateBooks) {
        if (item == null || item.getType() == Material.AIR) {
            return item;
        }
        if (MinecraftVersion.v1_20_5.atOrAbove()) { // 1.20.6+
            Triton.get().getLogger().logDebug(
                    "[EnchantDebug] Spigot item translation using data components for %1",
                    item.getType()
            );
            return translateItemStackPost_1_20_6(item, languagePlayer, translateBooks, true);
        } else {
            Triton.get().getLogger().logDebug(
                    "[EnchantDebug] Spigot item translation using legacy NBT for %1",
                    item.getType()
            );
            return translateItemStackPre_1_20_6(item, languagePlayer, translateBooks);
        }
    }

    private static @NotNull ItemStack translateItemStackPre_1_20_6(@NotNull ItemStack item, @NotNull Localized languagePlayer, boolean translateBooks) {
        NbtCompound compound = null;
        try {
            val nbtTagOptional = NbtFactory.fromItemOptional(item);
            if (!nbtTagOptional.isPresent()) return item;
            compound = NbtFactory.asCompound(nbtTagOptional.get());
        } catch (IllegalArgumentException ignore) {
            // This means the item is just an ItemStack and not a CraftItemStack
            // However we can still translate stuff using the Bukkit ItemMeta API instead of NBT tags
        }

        if (compound == null) {
            // If the item is not a craft item, use the Bukkit API
            return translateBukkitItemStack(item, languagePlayer);
        }
        logNbtItemAudit("before-legacy-nbt", item, languagePlayer, compound);

        // Translate the contents of shulker boxes
        if (compound.containsKey("BlockEntityTag")) {
            NbtCompound blockEntityTag = compound.getCompoundOrDefault("BlockEntityTag");
            if (blockEntityTag.containsKey("Items")) {
                NbtBase<?> itemsBase = blockEntityTag.getValue("Items");
                if (itemsBase instanceof NbtList<?>) {
                    NbtList<?> items = (NbtList<?>) itemsBase;
                    Collection<? extends NbtBase<?>> itemsCollection = items.asCollection();
                    for (NbtBase<?> base : itemsCollection) {
                        NbtCompound invItem = NbtFactory.asCompound(base);
                        if (!invItem.containsKey("tag")) continue;
                        NbtCompound tag = invItem.getCompoundOrDefault("tag");
                        translateNbtItem(tag, languagePlayer, false);
                    }
                }
            }
        }

        // try to translate name and lore
        translateNbtItem(compound, languagePlayer, true);
        // translate the content of written books
        if (translateBooks && item.getType() == Material.WRITTEN_BOOK && main().getConfig().isBooks()) {
            if (compound.containsKey("pages")) {
                NbtList<String> pages = compound.getList("pages");
                Collection<NbtBase<String>> pagesCollection = pages.asCollection();
                List<String> newPagesCollection = new ArrayList<>();
                for (NbtBase<String> page : pagesCollection) {
                    try {
                        main().getMessageParser()
                                .translateComponent(
                                        ComponentUtils.deserializeFromJson(page.getValue()),
                                        languagePlayer,
                                        main().getConfig().getItemsSyntax()
                                )
                                .map(ComponentUtils::serializeToJson)
                                .ifChanged(newPagesCollection::add)
                                .ifUnchanged(() -> newPagesCollection.add(page.getValue()));
                    } catch (JsonSyntaxException e) {
                        val pageString = page.getValue();
                        main().getMessageParser()
                                .translateString(
                                        pageString,
                                        languagePlayer,
                                        main().getConfig().getItemsSyntax()
                                )
                                .ifChanged(newPagesCollection::add)
                                .ifUnchanged(() -> newPagesCollection.add(pageString));
                    }
                }
                compound.put("pages", NbtFactory.ofList("pages", newPagesCollection));
            }
        }
        logNbtItemAudit("after-legacy-nbt", item, languagePlayer, compound);
        return item;
    }

    private static @NotNull ItemStack translateItemStackPost_1_20_6(@NotNull ItemStack item, @NotNull Localized languagePlayer, boolean translateBooks, boolean translateLore) {
        Optional<WrappedPatchedDataComponentMap> componentMapOpt = WrappedPatchedDataComponentMap.fromItemStack(item);
        if (!componentMapOpt.isPresent()) {
            // item stack is not a craft item stack, delegate to the bukkit translator
            Triton.get().getLogger().logDebug(
                    "[EnchantDebug] No CraftItemStack data component map for %1; delegating to Bukkit ItemMeta",
                    item.getType()
            );
            return translateBukkitItemStack(item, languagePlayer);
        }
        WrappedPatchedDataComponentMap componentMap = componentMapOpt.get();
        logDataComponentAudit("before-data-components", item, languagePlayer, translateBooks, translateLore, componentMap);

        // translate item name
        WrappedChatComponent itemName = componentMap.getItemName();
        if (itemName != null) {
            main().getMessageParser()
                    .translateComponent(
                            WrappedComponentUtils.deserialize(itemName),
                            languagePlayer,
                            main().getConfig().getItemsSyntax()
                    )
                    .map(ComponentUtils::ensureNotItalic)
                    .map(WrappedComponentUtils::serialize)
                    .ifToRemove(() -> componentMap.setItemName(null))
                    .ifChanged(componentMap::setItemName);
        }

        // translate custom name
        WrappedChatComponent customName = componentMap.getCustomName();
        if (customName != null) {
            main().getMessageParser()
                    .translateComponent(
                            WrappedComponentUtils.deserialize(customName),
                            languagePlayer,
                            main().getConfig().getItemsSyntax()
                    )
                    .map(ComponentUtils::ensureNotItalic)
                    .map(WrappedComponentUtils::serialize)
                    .ifToRemove(() -> componentMap.setCustomName(null))
                    .ifChanged(componentMap::setCustomName);
        }

        if (translateLore) {
            // translate lore
            WrappedItemLore itemLore = componentMap.getLore();
            if (itemLore != null) {
                List<WrappedChatComponent> newLines = new ArrayList<>();
                for (WrappedChatComponent line : itemLore.getLines()) {
                    main().getMessageParser()
                            .translateComponent(
                                    WrappedComponentUtils.deserialize(line),
                                    languagePlayer,
                                    main().getConfig().getItemsSyntax()
                            )
                            .map(ComponentUtils::splitByNewLine)
                            .ifChanged(result -> newLines.addAll(
                                    result.stream()
                                            .map(ComponentUtils::ensureNotItalic)
                                            .map(WrappedComponentUtils::serialize)
                                            .collect(Collectors.toList())
                            ))
                            .ifUnchanged(() -> newLines.add(line));
                }
                itemLore.setLines(newLines);
                componentMap.setLore(itemLore);
            }
            // translate tooltips of shulker boxes (and other containers)
            WrappedItemContainerContents containerContents = componentMap.getContainer();
            if (containerContents != null) {
                List<ItemStack> newItems = containerContents.getItems()
                        .map(containerItem -> {
                            if (containerItem == null) {
                                return null;
                            }
                            return translateItemStackPost_1_20_6(containerItem.clone(), languagePlayer, false, false);
                        })
                        .collect(Collectors.toList());
                containerContents.setItems(newItems);
                componentMap.setContainer(containerContents);
            }
        }

        // translate contents of written books
        if (translateBooks && main().getConfig().isBooks()) {
            WrappedWrittenBookContent bookContent = componentMap.getWrittenBookContent();
            if (bookContent != null) {
                List<WrappedFilterable<WrappedChatComponent>> newPages = new ArrayList<>();
                for (WrappedFilterable<WrappedChatComponent> page : bookContent.getPages()) {
                    main().getMessageParser()
                            .translateComponent(
                                    WrappedComponentUtils.deserialize(page.getRaw()),
                                    languagePlayer,
                                    main().getConfig().getItemsSyntax()
                            )
                            .map(WrappedComponentUtils::serialize)
                            .ifChanged(pageContents -> {
                                page.setRaw(pageContents);
                                newPages.add(page);
                            })
                            .ifUnchanged(() -> newPages.add(page));
                }
                bookContent.setPages(newPages);
                componentMap.setWrittenBookContent(bookContent);
            }
        }

        logDataComponentAudit("after-data-components", item, languagePlayer, translateBooks, translateLore, componentMap);
        return item;
    }

    private static @NotNull ItemStack translateBukkitItemStack(@NotNull ItemStack item, @NotNull Localized languagePlayer) {
        logBukkitItemAudit("before-bukkit", item, languagePlayer);
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName()) {
                main().getMessageParser()
                        .translateString(
                                meta.getDisplayName(),
                                languagePlayer,
                                main().getConfig().getItemsSyntax()
                        )
                        .ifChanged(meta::setDisplayName)
                        .ifToRemove(() -> meta.setDisplayName(null));
            }
            if (meta.hasLore()) {
                List<String> newLore = new ArrayList<>();
                for (String lore : meta.getLore()) {
                    main().getMessageParser()
                            .translateString(lore, languagePlayer, main().getConfig().getItemsSyntax())
                            .ifChanged(result -> newLore.addAll(Arrays.asList(result.split("\n"))))
                            .ifUnchanged(() -> newLore.add(lore));
                }
                meta.setLore(newLore);
            }
            item.setItemMeta(meta);
        }
        logBukkitItemAudit("after-bukkit", item, languagePlayer);
        return item;
    }

    /**
     * Translates an item's name (and optionally lore) by their NBT tag, mutating the given compound
     *
     * @param compound       The NBT tag of the item
     * @param languagePlayer The language player to translate for
     * @param translateLore  Whether to attempt to translate the lore of the item
     */
    public static void translateNbtItem(@NotNull NbtCompound compound, @NotNull Localized languagePlayer, boolean translateLore) {
        if (!compound.containsKey("display")) {
            return;
        }

        NbtCompound display = compound.getCompoundOrDefault("display");
        if (display.containsKey("Name")) {
            String name = display.getStringOrDefault("Name");
            try {
                main().getMessageParser()
                        .translateComponent(
                                ComponentUtils.deserializeFromJson(name),
                                languagePlayer,
                                main().getConfig().getItemsSyntax()
                        )
                        .map(ComponentUtils::ensureNotItalic)
                        .map(ComponentUtils::serializeToJson)
                        .ifChanged(result -> display.put("Name", result))
                        .ifToRemove(() -> display.remove("Name"));
            } catch (JsonSyntaxException e) {
                main().getMessageParser()
                        .translateString(
                                name,
                                languagePlayer,
                                main().getConfig().getItemsSyntax()
                        )
                        .ifChanged(result -> display.put("Name", result))
                        .ifToRemove(() -> display.remove("Name"));

            }
        }

        if (translateLore && display.containsKey("Lore")) {
            NbtList<String> loreNbt = display.getListOrDefault("Lore");

            List<String> newLore = new ArrayList<>();
            for (String lore : loreNbt) {
                try {
                    main().getMessageParser()
                            .translateComponent(
                                    ComponentUtils.deserializeFromJson(lore),
                                    languagePlayer,
                                    main().getConfig().getItemsSyntax()
                            )
                            .map(ComponentUtils::splitByNewLine)
                            .ifChanged(result -> newLore.addAll(
                                    result.stream()
                                            .map(ComponentUtils::ensureNotItalic)
                                            .map(ComponentUtils::serializeToJson)
                                            .collect(Collectors.toList())
                            ))
                            .ifUnchanged(() -> newLore.add(lore));
                } catch (JsonSyntaxException e) {
                    main().getMessageParser()
                            .translateString(
                                    lore,
                                    languagePlayer,
                                    main().getConfig().getItemsSyntax()
                            )
                            .ifChanged(result -> newLore.addAll(
                                    Arrays.asList(result.split("\n"))
                            ))
                            .ifUnchanged(() -> newLore.add(lore));
                }
            }
            display.put(NbtFactory.ofList("Lore", newLore));
        }
    }

    public static String translateNbtString(String nbt, Localized localized) {
        val compound = deserializeItemTagNbt(nbt);
        translateNbtItem(compound, localized, true);
        return serializeItemTagNbt(compound);
    }

    private static void logDataComponentAudit(
            @NotNull String phase,
            @NotNull ItemStack item,
            @NotNull Localized languagePlayer,
            boolean translateBooks,
            boolean translateLore,
            @NotNull WrappedPatchedDataComponentMap componentMap
    ) {
        try {
            Triton.get().getLogger().logDebug(
                    "[EnchantAudit] ProtocolLib phase=%1 path=data-components type=%2 amount=%3 itemId=%4 lang=%5 translateLore=%6 translateBooks=%7 lore=%8 enchantments=%9 storedEnchantments=%10 tooltipDisplay=%11 bukkitMeta=%12 componentMap=%13",
                    phase,
                    item.getType(),
                    item.getAmount(),
                    System.identityHashCode(item),
                    languagePlayer.getLanguageId(),
                    translateLore,
                    translateBooks,
                    describeWrappedLore(componentMap.getLore()),
                    describeRawComponent(componentMap.getEnchantments()),
                    describeRawComponent(componentMap.getStoredEnchantments()),
                    describeRawComponent(componentMap.getTooltipDisplay()),
                    describeBukkitMeta(item),
                    limit(String.valueOf(componentMap.getHandle()), 700)
            );
        } catch (Exception e) {
            Triton.get().getLogger().logDebug(
                    "[EnchantAudit] ProtocolLib phase=%1 path=data-components failed to inspect %2: %3",
                    phase,
                    item.getType(),
                    e.getMessage()
            );
        }
    }

    private static void logBukkitItemAudit(@NotNull String phase, @NotNull ItemStack item, @NotNull Localized languagePlayer) {
        try {
            Triton.get().getLogger().logDebug(
                    "[EnchantAudit] ProtocolLib phase=%1 path=bukkit-meta type=%2 amount=%3 itemId=%4 lang=%5 bukkitMeta=%6",
                    phase,
                    item.getType(),
                    item.getAmount(),
                    System.identityHashCode(item),
                    languagePlayer.getLanguageId(),
                    describeBukkitMeta(item)
            );
        } catch (Exception e) {
            Triton.get().getLogger().logDebug(
                    "[EnchantAudit] ProtocolLib phase=%1 path=bukkit-meta failed to inspect %2: %3",
                    phase,
                    item.getType(),
                    e.getMessage()
            );
        }
    }

    private static void logNbtItemAudit(
            @NotNull String phase,
            @NotNull ItemStack item,
            @NotNull Localized languagePlayer,
            @NotNull NbtCompound compound
    ) {
        try {
            Triton.get().getLogger().logDebug(
                    "[EnchantAudit] ProtocolLib phase=%1 path=legacy-nbt type=%2 amount=%3 itemId=%4 lang=%5 display=%6 enchantments=%7 storedEnchantments=%8 legacyEnch=%9 hideFlags=%10 bukkitMeta=%11",
                    phase,
                    item.getType(),
                    item.getAmount(),
                    System.identityHashCode(item),
                    languagePlayer.getLanguageId(),
                    describeLegacyDisplay(compound),
                    describeNbtValue(compound, "Enchantments"),
                    describeNbtValue(compound, "StoredEnchantments"),
                    describeNbtValue(compound, "ench"),
                    describeNbtValue(compound, "HideFlags"),
                    describeBukkitMeta(item)
            );
        } catch (Exception e) {
            Triton.get().getLogger().logDebug(
                    "[EnchantAudit] ProtocolLib phase=%1 path=legacy-nbt failed to inspect %2: %3",
                    phase,
                    item.getType(),
                    e.getMessage()
            );
        }
    }

    private static String describeWrappedLore(@Nullable WrappedItemLore lore) {
        if (lore == null) {
            return "absent";
        }
        return "lines=" + lore.getLines().size();
    }

    private static String describeBukkitMeta(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return "absent";
        }

        ItemMeta meta = item.getItemMeta();
        String storedEnchants = "n/a";
        if (meta instanceof EnchantmentStorageMeta) {
            storedEnchants = describeEnchantmentMap(((EnchantmentStorageMeta) meta).getStoredEnchants());
        }

        return "displayName=" + meta.hasDisplayName()
                + " loreLines=" + (meta.hasLore() ? meta.getLore().size() : 0)
                + " enchants=" + describeEnchantmentMap(meta.getEnchants())
                + " storedEnchants=" + storedEnchants
                + " flags=" + meta.getItemFlags().stream().map(ItemFlag::name).sorted().collect(Collectors.toList())
                + " hideEnchants=" + meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS);
    }

    private static String describeEnchantmentMap(@NotNull Map<?, Integer> enchantments) {
        if (enchantments.isEmpty()) {
            return "empty";
        }

        List<String> entries = new ArrayList<>();
        int count = 0;
        for (val entry : enchantments.entrySet()) {
            if (count < 8) {
                entries.add(String.valueOf(entry.getKey()) + "=" + entry.getValue());
            }
            count++;
        }
        if (count > entries.size()) {
            entries.add("+" + (count - entries.size()) + " more");
        }
        return "size=" + count + " entries=" + entries;
    }

    private static String describeRawComponent(@Nullable Object component) {
        if (component == null) {
            return "absent";
        }

        List<String> methodValues = new ArrayList<>();
        for (Method method : component.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || !isUsefulAuditMethod(method)) {
                continue;
            }
            try {
                Object result = method.invoke(component);
                methodValues.add(method.getName() + "=" + result);
            } catch (Exception ignored) {
                // Best-effort reflection for audit only.
            }
        }
        return "class=" + component.getClass().getName()
                + " methods=" + methodValues
                + " value=" + limit(String.valueOf(component), 500);
    }

    private static boolean isUsefulAuditMethod(@NotNull Method method) {
        val name = method.getName().toLowerCase();
        val returnType = method.getReturnType();
        boolean booleanReturn = returnType == boolean.class || returnType == Boolean.class;
        boolean numericReturn = returnType == int.class || returnType == Integer.class;
        return (booleanReturn && (name.contains("tooltip") || name.equals("isempty") || name.equals("empty")))
                || (numericReturn && name.equals("size"));
    }

    private static String describeLegacyDisplay(@NotNull NbtCompound compound) {
        if (!compound.containsKey("display")) {
            return "absent";
        }

        NbtCompound display = compound.getCompoundOrDefault("display");
        int loreLines = 0;
        if (display.containsKey("Lore")) {
            loreLines = display.getListOrDefault("Lore").size();
        }
        return "name=" + display.containsKey("Name")
                + " loreLines=" + loreLines;
    }

    private static String describeNbtValue(@NotNull NbtCompound compound, @NotNull String key) {
        if (!compound.containsKey(key)) {
            return "absent";
        }
        return limit(String.valueOf(compound.getValue(key)), 500);
    }

    private static String limit(@Nullable String value, int maxLength) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static NbtCompound deserializeItemTagNbt(String nbt) {
        val nmsCompound = NBT_DESERIALIZER_METHOD.invoke(null, nbt);
        return NbtFactory.fromNMSCompound(nmsCompound);
    }

    private static String serializeItemTagNbt(NbtCompound nbt) {
        return nbt.getHandle().toString();
    }

    private static SpigotTriton main() {
        return SpigotTriton.asSpigot();
    }

}
