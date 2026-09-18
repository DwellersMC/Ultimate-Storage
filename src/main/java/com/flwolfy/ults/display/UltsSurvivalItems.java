package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * The items a survival player can obtain.
 *
 * <p>Minecraft has no flag for this, so it is derived from everything the server knows:
 *
 * <ul>
 *   <li>the creative tabs normal players see, which already cover the acquisition paths that are pure
 *       code and therefore appear in no data file at all: catching a fish with a bucket, filling a
 *       bucket with lava or powder snow, turning dirt into a path or farmland with a shovel or hoe,
 *       brushing suspicious blocks, trading with villagers, enchanting, and so on,
 *   <li>every recipe result, read both from the recipe manager and from the recipe files so special
 *       recipes (fireworks, tipped arrows, map copying, ...) are included as well,
 *   <li>every item any loot table can drop, including nested tables and item tags, which covers block
 *       drops, mob drops, fishing, bartering, archaeology and structure chests,
 *   <li>minus the creative only tabs and a short list of items that vanilla lists in a normal tab but
 *       that no survival player can actually get.
 * </ul>
 *
 * <p>Because everything comes from the server's own tabs, data packs and recipe manager, modded items
 * are covered automatically.
 */
public final class UltsSurvivalItems {

  /** Tabs whose contents are never obtainable in survival. */
  private static final Set<String> EXCLUDED_TABS = Set.of(
      "minecraft:op_blocks",
      "minecraft:spawn_eggs"
  );

  /**
   * Items that vanilla lists in a normal tab, that a survival player can never obtain, and that have
   * a data source anyway (the block drops itself), so no rule can tell them apart from real items.
   */
  private static final List<Item> CREATIVE_ONLY = List.of(
      Items.PETRIFIED_OAK_SLAB,
      Items.PLAYER_HEAD,
      Items.BEDROCK,
      Items.BUDDING_AMETHYST,
      Items.REINFORCED_DEEPSLATE,
      Items.VAULT,
      Items.END_PORTAL_FRAME,
      Items.CHORUS_PLANT,
      Items.FROGSPAWN,
      Items.SUSPICIOUS_SAND,
      Items.SUSPICIOUS_GRAVEL,
      Items.SPAWNER,
      // Silk touch on these drops the block they pretend to be.
      Items.INFESTED_STONE,
      Items.INFESTED_COBBLESTONE,
      Items.INFESTED_STONE_BRICKS,
      Items.INFESTED_MOSSY_STONE_BRICKS,
      Items.INFESTED_CRACKED_STONE_BRICKS,
      Items.INFESTED_CHISELED_STONE_BRICKS,
      Items.INFESTED_DEEPSLATE
  );

  private static final Set<String> ENTRY_TYPES_WITH_CHILDREN = Set.of(
      "minecraft:alternatives",
      "minecraft:sequence",
      "minecraft:group"
  );

  private static volatile Set<Item> obtainable = Set.of();

  private UltsSurvivalItems() {}

  public static void rebuild(MinecraftServer server) {
    Set<Item> fromData = Collections.newSetFromMap(new IdentityHashMap<>());
    int fromRecipes = collectRecipeResults(server, fromData);
    int fromRecipeFiles = collectRecipeFiles(server, fromData);
    int fromLoot = collectLootDrops(server, fromData);
    Set<Item> items = Collections.newSetFromMap(new IdentityHashMap<>());
    int fromTabs = collectTabItems(items);
    items.addAll(fromData);
    CREATIVE_ONLY.forEach(items::remove);
    // Modded spawn eggs are not in the vanilla spawn egg tab, but are still creative only.
    items.removeIf(item -> BuiltInRegistries.ITEM.getKey(item).getPath().endsWith("_spawn_egg"));
    obtainable = Set.copyOf(items);
    UltsMod.LOGGER.info(
        "UltStorage survival catalog: {} obtainable item(s) ({} from creative tabs, "
            + "{} from recipes, {} from recipe files, {} from loot tables)",
        obtainable.size(), fromTabs, fromRecipes, fromRecipeFiles, fromLoot);
  }

  /** Whether a survival player can obtain this item, whatever its components are. */
  public static boolean obtainable(ItemStack stack) {
    return !stack.isEmpty() && obtainable.contains(stack.getItem());
  }

  /** Every item of the creative tabs a normal player sees. */
  private static int collectTabItems(Set<Item> items) {
    int before = items.size();
    for (UltsItemCategory category : UltsCreativeCatalog.categories()) {
      // The overview and the special category are views, not tabs.
      if (UltsCreativeCatalog.ALL_ID.equals(category.id())
          || category.special()
          || EXCLUDED_TABS.contains(category.id())) {
        continue;
      }
      for (ItemStack template : category.templates()) {
        items.add(template.getItem());
      }
    }
    return items.size() - before;
  }

  private static int collectRecipeResults(MinecraftServer server, Set<Item> items) {
    int before = items.size();
    ContextMap context = SlotDisplayContext.fromLevel(server.overworld());
    try {
      for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
        for (var display : holder.value().display()) {
          for (ItemStack stack : display.result().resolveForStacks(context)) {
            if (!stack.isEmpty()) {
              items.add(stack.getItem());
            }
          }
        }
      }
    } catch (RuntimeException exception) {
      UltsMod.LOGGER.warn("UltStorage could not read every recipe result", exception);
    }
    return items.size() - before;
  }

  /**
   * Reads the result of every recipe file as well, because special recipes (fireworks, tipped arrows,
   * banner copies, ...) do not always resolve to an item through the recipe manager.
   */
  private static int collectRecipeFiles(MinecraftServer server, Set<Item> items) {
    int before = items.size();
    Map<Identifier, Resource> recipes = server.getResourceManager().listResources(
        "recipe", id -> id.getPath().endsWith(".json"));
    for (Map.Entry<Identifier, Resource> entry : recipes.entrySet()) {
      try (Reader reader = entry.getValue().openAsReader()) {
        JsonElement parsed = JsonParser.parseReader(reader);
        if (!parsed.isJsonObject()) {
          continue;
        }
        JsonElement result = parsed.getAsJsonObject().get("result");
        if (result == null) {
          continue;
        }
        if (result.isJsonPrimitive()) {
          item(result.getAsString()).ifPresent(items::add);
        } else if (result.isJsonObject()) {
          JsonElement id = result.getAsJsonObject().get("id");
          if (id != null && id.isJsonPrimitive()) {
            item(id.getAsString()).ifPresent(items::add);
          }
        }
      } catch (Exception exception) {
        UltsMod.LOGGER.warn("UltStorage could not read recipe {}", entry.getKey(), exception);
      }
    }
    return items.size() - before;
  }

  private static int collectLootDrops(MinecraftServer server, Set<Item> items) {
    int before = items.size();
    Map<Identifier, Resource> tables = server.getResourceManager().listResources(
        "loot_table", id -> id.getPath().endsWith(".json"));
    Set<Identifier> visited = new HashSet<>();
    Deque<Identifier> pending = new ArrayDeque<>(tables.keySet());
    while (!pending.isEmpty()) {
      Identifier id = pending.poll();
      if (!visited.add(id)) {
        continue;
      }
      Resource resource = tables.get(id);
      if (resource == null) {
        continue;
      }
      try (Reader reader = resource.openAsReader()) {
        JsonElement parsed = JsonParser.parseReader(reader);
        if (parsed.isJsonObject()) {
          collectEntries(parsed.getAsJsonObject(), items, visited, pending);
        }
      } catch (Exception exception) {
        UltsMod.LOGGER.warn("UltStorage could not read loot table {}", id, exception);
      }
    }
    return items.size() - before;
  }

  /** Walks one loot table: its pools, nested entries and the tables or tags they point at. */
  private static void collectEntries(
      JsonObject table,
      Set<Item> items,
      Set<Identifier> visited,
      Deque<Identifier> pending
  ) {
    JsonArray pools = table.getAsJsonArray("pools");
    if (pools == null) {
      return;
    }
    for (JsonElement pool : pools) {
      if (!pool.isJsonObject()) {
        continue;
      }
      JsonArray entries = pool.getAsJsonObject().getAsJsonArray("entries");
      if (entries != null) {
        entries.forEach(entry -> collectEntry(entry, items, visited, pending));
      }
    }
  }

  private static void collectEntry(
      JsonElement element,
      Set<Item> items,
      Set<Identifier> visited,
      Deque<Identifier> pending
  ) {
    if (!element.isJsonObject()) {
      return;
    }
    JsonObject entry = element.getAsJsonObject();
    String type = entry.has("type") ? entry.get("type").getAsString() : "";
    JsonElement name = entry.get("name");
    if ("minecraft:item".equals(type) && name != null) {
      item(name.getAsString()).ifPresent(items::add);
      return;
    }
    if ("minecraft:tag".equals(type) && name != null) {
      String tag = name.getAsString();
      TagKey<Item> key = TagKey.create(
          Registries.ITEM, Identifier.parse(tag.startsWith("#") ? tag.substring(1) : tag));
      for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(key)) {
        items.add(holder.value());
      }
      return;
    }
    if ("minecraft:loot_table".equals(type) && name != null) {
      // A nested table: read it later, even when it lives in another data pack.
      Identifier nested = Identifier.tryParse(name.getAsString());
      if (nested != null && !visited.contains(nested)) {
        pending.add(nested);
      }
      return;
    }
    if (ENTRY_TYPES_WITH_CHILDREN.contains(type)) {
      JsonArray children = entry.getAsJsonArray("children");
      if (children != null) {
        children.forEach(child -> collectEntry(child, items, visited, pending));
      }
    }
  }

  private static Optional<Item> item(String name) {
    Identifier id = Identifier.tryParse(name);
    if (id == null) {
      return Optional.empty();
    }
    return BuiltInRegistries.ITEM.getOptional(ResourceKey.create(Registries.ITEM, id));
  }
}
