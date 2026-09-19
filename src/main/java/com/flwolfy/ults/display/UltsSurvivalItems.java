package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
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

  /**
   * Functions that decide which value of a component the produced stack carries, so the value can be
   * read out of the data and counted as something survival can produce.
   */
  private static final Set<String> VALUE_FUNCTIONS = Set.of(
      "minecraft:set_potion",
      "minecraft:enchant_randomly",
      "minecraft:enchant_with_levels",
      "minecraft:set_enchantments"
  );

  private static volatile Set<Item> obtainable = Set.of();
  /** Which values of a component kind survival can produce, keyed by kind. */
  private static volatile Map<String, Set<String>> typeValues = Map.of();
  /** The enchantment registry of the running server, used to resolve option tags. */
  private static volatile Registry<Enchantment> enchantmentRegistry;

  private UltsSurvivalItems() {}

  public static void rebuild(MinecraftServer server) {
    enchantmentRegistry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
    Set<Item> fromData = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<String, Set<String>> values = new HashMap<>();
    int fromRecipes = collectRecipeResults(server, fromData);
    int fromRecipeFiles = collectRecipeFiles(server, fromData);
    int fromLoot = collectLootDrops(server, fromData, values);
    int fromTrades = collectTrades(server, fromData, values);
    int fromCode = collectRuntimeSources(server, fromData, values);
    Set<Item> items = Collections.newSetFromMap(new IdentityHashMap<>());
    int fromTabs = collectTabItems(items);
    items.addAll(fromData);
    CREATIVE_ONLY.forEach(items::remove);
    // Modded spawn eggs are not in the vanilla spawn egg tab, but are still creative only.
    items.removeIf(item -> BuiltInRegistries.ITEM.getKey(item).getPath().endsWith("_spawn_egg"));
    obtainable = Set.copyOf(items);
    Map<String, Set<String>> frozenValues = new HashMap<>();
    values.forEach((kind, known) -> frozenValues.put(kind, Set.copyOf(known)));
    typeValues = Map.copyOf(frozenValues);
    UltsMod.LOGGER.info(
        "UltStorage survival catalog: {} obtainable item(s), {} type value kinds "
            + "({} from creative tabs, {} from recipes, {} from recipe files, {} from loot tables, "
            + "{} from trades, {} from game code)",
        obtainable.size(), typeValues.size(), fromTabs, fromRecipes, fromRecipeFiles, fromLoot,
        fromTrades, fromCode);
  }

  /**
   * Sources that live in the game code instead of a data file, but still describe what survival can
   * produce: every brewable potion and every enchantment the enchanting table can roll.
   */
  private static int collectRuntimeSources(
      MinecraftServer server,
      Set<Item> items,
      Map<String, Set<String>> values
  ) {
    int before = values.size();
    // Filling a glass bottle at a water source is the one potion that comes from code, not brewing.
    Potions.WATER.unwrapKey().ifPresent(key ->
        addValue(values, "potion", key.identifier().toString()));
    for (Holder.Reference<Potion> potion : BuiltInRegistries.POTION.listElements().toList()) {
      if (server.potionBrewing().isBrewablePotion(potion)) {
        addValue(values, "potion", potion.key().identifier().toString());
      }
    }
    for (Holder<Enchantment> enchantment
        : enchantmentRegistry.getTagOrEmpty(EnchantmentTags.IN_ENCHANTING_TABLE)) {
      enchantment.unwrapKey().ifPresent(key ->
          addValue(values, "enchantment", key.identifier().toString()));
    }
    for (Holder<Enchantment> enchantment
        : enchantmentRegistry.getTagOrEmpty(EnchantmentTags.TRADEABLE)) {
      enchantment.unwrapKey().ifPresent(key ->
          addValue(values, "enchantment", key.identifier().toString()));
    }
    if (!values.getOrDefault("enchantment", Set.of()).isEmpty()) {
      // A book plus an enchanting table is a survival way to get enchanted books.
      items.add(Items.ENCHANTED_BOOK);
    }
    return values.size() - before;
  }

  private static void addValue(Map<String, Set<String>> values, String kind, String value) {
    if (value != null && !value.isEmpty()) {
      values.computeIfAbsent(kind, key -> new HashSet<>()).add(value);
    }
  }

  /**
   * Reads the values a data function can produce: a fixed id, a list, or an item/enchantment tag that
   * is resolved through the registry, which is what turns "a random enchantment from this tag" into
   * the list of enchantments survival can actually roll.
   */
  private static void collectFunctionValues(
      String function,
      JsonObject json,
      Map<String, Set<String>> values
  ) {
    switch (function) {
      case "minecraft:set_potion" -> addValue(values, "potion", text(json, "id"));
      case "minecraft:enchant_randomly", "minecraft:enchant_with_levels" ->
          addValues(values, "enchantment", json.get("options"));
      case "minecraft:set_enchantments" -> {
        JsonElement enchantments = json.get("enchantments");
        if (enchantments != null && enchantments.isJsonObject()) {
          enchantments.getAsJsonObject().keySet()
              .forEach(name -> addValue(values, "enchantment", name));
        }
      }
      default -> {
      }
    }
  }

  private static void addValues(
      Map<String, Set<String>> values,
      String kind,
      JsonElement options
  ) {
    if (options == null) {
      return;
    }
    if (options.isJsonArray()) {
      for (JsonElement element : options.getAsJsonArray()) {
        addValues(values, kind, element);
      }
      return;
    }
    if (!options.isJsonPrimitive()) {
      return;
    }
    String value = options.getAsString();
    if (!value.startsWith("#")) {
      addValue(values, kind, value);
      return;
    }
    Identifier id = Identifier.tryParse(value.substring(1));
    if (id == null) {
      return;
    }
    if ("enchantment".equals(kind)) {
      for (Holder<Enchantment> holder
          : enchantmentRegistry.getTagOrEmpty(TagKey.create(Registries.ENCHANTMENT, id))) {
        holder.unwrapKey().ifPresent(key -> addValue(values, kind, key.identifier().toString()));
      }
    }
  }

  private static String text(JsonObject json, String field) {
    JsonElement value = json.get(field);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
  }

  /** Whether a survival player can obtain this item, whatever its components are. */
  public static boolean obtainable(ItemStack stack) {
    return !stack.isEmpty() && obtainable.contains(stack.getItem());
  }

  /** A component kind with the values a catalogue entry carries. */
  private record EntryType(String kind, List<String> values) {}

  /** The kind and values of the components this stack carries, or {@code null} when untracked. */
  private static EntryType typeOf(ItemStack stack) {
    PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
    if (potion != null && potion.potion().isPresent()) {
      return potion.potion().get().unwrapKey()
          .map(key -> new EntryType("potion", List.of(key.identifier().toString())))
          .orElse(null);
    }
    ItemEnchantments enchantments = stack.get(DataComponents.STORED_ENCHANTMENTS);
    if (enchantments != null && !enchantments.isEmpty()) {
      List<String> keys = new ArrayList<>();
      for (Holder<Enchantment> holder : enchantments.keySet()) {
        holder.unwrapKey().ifPresent(key -> keys.add(key.identifier().toString()));
      }
      keys.sort(String::compareTo);
      return keys.isEmpty() ? null : new EntryType("enchantment", List.copyOf(keys));
    }
    return null;
  }

  /**
   * Whether every value of this stack's type is one survival can produce. A potion therefore counts
   * when it can be brewed or found, while the uncraftable one never does.
   */
  public static boolean typeObtainable(ItemStack stack) {
    EntryType type = typeOf(stack);
    if (type == null) {
      return false;
    }
    Set<String> known = typeValues.getOrDefault(type.kind(), Set.of());
    return !known.isEmpty() && known.containsAll(type.values());
  }

  /** A stable key of this stack's type, used to show every type exactly once. */
  public static String typeKey(ItemStack stack) {
    EntryType type = typeOf(stack);
    return type == null ? null : type.kind() + ":" + String.join(",", type.values());
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

  private static int collectLootDrops(
      MinecraftServer server,
      Set<Item> items,
      Map<String, Set<String>> values
  ) {
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
          collectEntries(parsed.getAsJsonObject(), items, values, visited, pending);
        }
      } catch (Exception exception) {
        UltsMod.LOGGER.warn("UltStorage could not read loot table {}", id, exception);
      }
    }
    return items.size() - before;
  }

  /** Every item villagers offer, with the components the trade data gives them. */
  private static int collectTrades(
      MinecraftServer server,
      Set<Item> items,
      Map<String, Set<String>> values
  ) {
    int before = items.size();
    Map<Identifier, Resource> trades = server.getResourceManager().listResources(
        "villager_trade", id -> id.getPath().endsWith(".json"));
    for (Map.Entry<Identifier, Resource> entry : trades.entrySet()) {
      try (Reader reader = entry.getValue().openAsReader()) {
        JsonElement parsed = JsonParser.parseReader(reader);
        if (!parsed.isJsonObject()) {
          continue;
        }
        JsonElement gives = parsed.getAsJsonObject().get("gives");
        if (gives != null && gives.isJsonObject()) {
          collectStack(gives.getAsJsonObject(), "given_item_modifiers", items, values);
        }
      } catch (Exception exception) {
        UltsMod.LOGGER.warn("UltStorage could not read trade {}", entry.getKey(), exception);
      }
    }
    return items.size() - before;
  }

  /** Walks one loot table: its pools, nested entries and the tables or tags they point at. */
  private static void collectEntries(
      JsonObject table,
      Set<Item> items,
      Map<String, Set<String>> values,
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
        entries.forEach(
            entry -> collectEntry(entry, items, values, visited, pending));
      }
    }
  }

  private static void collectEntry(
      JsonElement element,
      Set<Item> items,
      Map<String, Set<String>> values,
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
      collectStack(entry, "functions", items, values);
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
        children.forEach(
            child -> collectEntry(child, items, values, visited, pending));
      }
    }
  }

  /**
   * Reads one data entry that names an item and remembers it, together with every value of a
   * component the entry lets the game choose while it runs (a random enchantment, a potion).
   */
  private static void collectStack(
      JsonObject entry,
      String functionsKey,
      Set<Item> items,
      Map<String, Set<String>> values
  ) {
    JsonElement name = entry.get("name");
    if (name == null) {
      name = entry.get("id");
    }
    if (name == null || !name.isJsonPrimitive()) {
      return;
    }
    Item item = item(name.getAsString()).orElse(null);
    if (item == null) {
      return;
    }
    JsonArray functions = entry.getAsJsonArray(functionsKey);
    if (functions != null) {
      for (JsonElement element : functions) {
        if (!element.isJsonObject()) {
          continue;
        }
        JsonElement id = element.getAsJsonObject().get("function");
        String key = id == null ? "" : id.getAsString();
        if (!VALUE_FUNCTIONS.contains(key)) {
          continue;
        }
        collectFunctionValues(key, element.getAsJsonObject(), values);
        if ("minecraft:enchant_randomly".equals(key) && item == Items.BOOK) {
          // Vanilla turns a book into an enchanted book when it enchants it at random.
          item = Items.ENCHANTED_BOOK;
        }
      }
    }
    items.add(item);
  }

  private static Optional<Item> item(String name) {
    Identifier id = Identifier.tryParse(name);
    if (id == null) {
      return Optional.empty();
    }
    return BuiltInRegistries.ITEM.getOptional(ResourceKey.create(Registries.ITEM, id));
  }
}
