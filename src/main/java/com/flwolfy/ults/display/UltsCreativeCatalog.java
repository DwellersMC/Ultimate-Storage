package com.flwolfy.ults.display;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class UltsCreativeCatalog {

  public static final String SPECIAL_ID = "ultimate-storage:special_nbt";
  private static volatile Map<Item, List<ItemStack>> normalItems = Map.of();
  private static volatile List<UltsItemCategory> categories = List.of();

  private UltsCreativeCatalog() {}

  public static void rebuild(MinecraftServer server) {
    CreativeModeTabs.tryRebuildTabContents(
        server.getWorldData().enabledFeatures(), true, server.registryAccess());
    Map<Item, List<ItemStack>> allItems = new IdentityHashMap<>();
    Map<String, UltsItemCategory> rebuilt = new LinkedHashMap<>();
    for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
      String id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab).toString();
      Map<Item, List<ItemStack>> entries = new IdentityHashMap<>();
      for (ItemStack stack : tab.getDisplayItems()) {
        ItemStack template = stack.copyWithCount(1);
        allItems.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>()).add(template);
        entries.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>()).add(template);
      }
      entries.replaceAll((item, stacks) -> List.copyOf(stacks));
      rebuilt.put(id, new UltsItemCategory(
          id, tab.getDisplayName(), tab.getIconItem(), Map.copyOf(entries), false));
    }
    allItems.replaceAll((item, stacks) -> List.copyOf(stacks));
    normalItems = Map.copyOf(allItems);
    rebuilt.put(SPECIAL_ID, new UltsItemCategory(
        SPECIAL_ID, Component.empty(), new ItemStack(Items.CHEST), Map.of(), true));
    categories = List.copyOf(rebuilt.values());
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
}
