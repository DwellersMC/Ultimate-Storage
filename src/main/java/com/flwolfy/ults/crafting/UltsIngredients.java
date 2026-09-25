package com.flwolfy.ults.crafting;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * What items a recipe slot accepts, and which of them a pile holds.
 *
 * <p>A slot matches on the item alone, components never take part, so the same handful of items is
 * asked for again and again while a pile is searched: how much of a slot a pile holds, and which
 * stacks could fill it. The answers are remembered per slot and dropped when the recipes are read
 * again, because a tag may name different items by then.
 *
 * <p>Every item of the registry also has a bit, and a slot's items are held as the words of those
 * bits. That is what makes counting a slot as cheap as a few {@code &} operations however many items
 * the slot names or the pile holds — a search asks this question hundreds of thousands of times, and
 * walking either list is what made one answer take a minute.
 */
final class UltsIngredients {

  private static final Map<Ingredient, List<Item>> ACCEPTED = new ConcurrentHashMap<>();
  private static final Map<Ingredient, long[]> MASKS = new ConcurrentHashMap<>();
  /** How many words a bit set over the item registry needs; a registry never shrinks. */
  private static volatile int words = -1;

  private UltsIngredients() {}

  /** How many words a bit set over the item registry needs. */
  static int itemWords() {
    int known = words;
    if (known >= 0) {
      return known;
    }
    int wanted = (BuiltInRegistries.ITEM.size() + 63) >>> 6;
    words = wanted;
    return wanted;
  }

  /** The registry id of an item, or {@code -1} when it was never registered. */
  static int itemId(Item item) {
    return BuiltInRegistries.ITEM.getId(item);
  }

  /** The item of a registry id, the other way around from {@link #itemId(Item)}. */
  static Item itemAt(int id) {
    return BuiltInRegistries.ITEM.byId(id);
  }

  /** The items a slot accepts, without duplicates and in a stable order. */
  @SuppressWarnings("deprecation")
  static List<Item> accepted(Ingredient ingredient) {
    // Enumerating a slot is the one thing the ingredient API marks as discouraged, and it is also the
    // only way to ask what a slot names. Nothing else here can be made cheap without it.
    return ACCEPTED.computeIfAbsent(ingredient, slot -> {
      LinkedHashSet<Item> items = new LinkedHashSet<>();
      slot.items().forEach(holder -> {
        if (holder.isBound()) {
          items.add(holder.value());
        }
      });
      return List.copyOf(items);
    });
  }

  /** The bits of the items a slot accepts, one word per 64 registry ids. */
  static long[] itemMask(Ingredient ingredient) {
    return MASKS.computeIfAbsent(ingredient, slot -> {
      long[] mask = new long[itemWords()];
      for (Item item : accepted(slot)) {
        int id = itemId(item);
        if (id >= 0 && (id >>> 6) < mask.length) {
          mask[id >>> 6] |= 1L << (id & 63);
        }
      }
      return mask;
    });
  }

  /** Whether a slot accepts an item, the same answer {@code test} gives for a stack of it. */
  static boolean accepts(Ingredient ingredient, Item item) {
    return ingredient.acceptsItem(BuiltInRegistries.ITEM.wrapAsHolder(item));
  }

  /** Forgets every remembered answer; called when the recipes are read again. */
  static void clear() {
    ACCEPTED.clear();
    MASKS.clear();
  }
}
