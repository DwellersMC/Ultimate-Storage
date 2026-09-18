package com.flwolfy.ults.display;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class UltsCreativeCatalog {

  public static final String ALL_ID = "ultimate-storage:all";
  public static final String SPECIAL_ID = "ultimate-storage:special_nbt";
  private static final Set<String> HIDDEN_TABS = Set.of(
      "minecraft:search",
      "minecraft:hotbar",
      "minecraft:inventory"
  );
  private static volatile Map<Item, List<ItemStack>> normalItems = Map.of();
  private static volatile List<ItemStack> allTemplates = List.of();
  private static volatile List<UltsItemCategory> categories = List.of();

  private UltsCreativeCatalog() {}

  public static void rebuild(MinecraftServer server) {
    CreativeModeTabs.tryRebuildTabContents(
        server.getWorldData().enabledFeatures(), true, server.registryAccess());
    Map<Item, List<ItemStack>> union = new IdentityHashMap<>();
    List<ItemStack> unionOrdered = new ArrayList<>();
    List<UltsItemCategory> rebuilt = new ArrayList<>();
    for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
      String id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).toString();
      if (HIDDEN_TABS.contains(id)) {
        continue;
      }
      Map<Item, List<ItemStack>> entries = new IdentityHashMap<>();
      List<ItemStack> templates = new ArrayList<>();
      for (ItemStack stack : tab.getDisplayItems()) {
        ItemStack template = stack.copyWithCount(1);
        if (addUnique(entries, templates, template)) {
          addUnique(union, unionOrdered, template);
        }
      }
      entries.replaceAll((item, stacks) -> List.copyOf(stacks));
      rebuilt.add(new UltsItemCategory(
          id, tab.getDisplayName(), "", tab.getIconItem(), Map.copyOf(entries),
          List.copyOf(templates), false));
    }
    union.replaceAll((item, stacks) -> List.copyOf(stacks));
    Map<Item, List<ItemStack>> unionIndex = Map.copyOf(union);
    normalItems = unionIndex;
    List<ItemStack> ordering = List.copyOf(unionOrdered);
    allTemplates = ordering;
    Map<String, UltsItemCategory> ordered = new LinkedHashMap<>();
    ordered.put(ALL_ID, new UltsItemCategory(
        ALL_ID, Component.empty(), "ults.gui.category.all", new ItemStack(Items.COMPASS),
        unionIndex, ordering, false));
    rebuilt.forEach(category -> ordered.put(category.id(), category));
    ordered.put(SPECIAL_ID, new UltsItemCategory(
        SPECIAL_ID, Component.empty(), "ults.gui.category.special_nbt",
        new ItemStack(Items.CHEST), Map.of(), List.of(), true));
    categories = List.copyOf(ordered.values());
  }

  public static List<UltsItemCategory> categories() {
    return categories;
  }

  public static UltsItemCategory category(String id) {
    return categories.stream().filter(category -> category.id().equals(id)).findFirst()
        .orElseGet(() -> categories.isEmpty() ? null : categories.getFirst());
  }

  public static boolean contains(ItemStack stack) {
    return normalItems.getOrDefault(stack.getItem(), List.of()).stream()
        .anyMatch(candidate -> ItemStack.isSameItemSameComponents(candidate, stack));
  }

  // Expects a filter already trimmed and lower-cased.
  public static boolean matches(ItemStack template, String filter) {
    if (filter == null || filter.isEmpty()) {
      return true;
    }
    return BuiltInRegistries.ITEM.getKey(template.getItem()).toString()
        .toLowerCase(Locale.ROOT).contains(filter);
  }

  public static long matches(String filter) {
    if (filter == null || filter.isEmpty()) {
      return allTemplates.size();
    }
    return allTemplates.stream().filter(template -> matches(template, filter)).count();
  }

  private static boolean addUnique(
      Map<Item, List<ItemStack>> index,
      List<ItemStack> ordered,
      ItemStack template
  ) {
    List<ItemStack> variants = index.computeIfAbsent(
        template.getItem(), ignored -> new ArrayList<>());
    for (ItemStack existing : variants) {
      if (ItemStack.isSameItemSameComponents(existing, template)) {
        return false;
      }
    }
    variants.add(template);
    if (ordered != null) {
      ordered.add(template);
    }
    return true;
  }
}
