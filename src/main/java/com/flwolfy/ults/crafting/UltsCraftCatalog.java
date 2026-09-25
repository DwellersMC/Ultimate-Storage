package com.flwolfy.ults.crafting;

import com.flwolfy.ults.UltsMod;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * Every recipe the storage could run, indexed by what it produces.
 *
 * <p>Only crafting table and stonecutter recipes with a plain ingredient list take part. Recipes that
 * decide their ingredients or their result while the game runs (dyeing, fireworks, banner and map
 * copying, repairing, and the like) have no ingredient list to consume, so they are left out.
 *
 * <p>Everything the catalogue hands out is remembered until the recipes are read again: a screen asks
 * the same questions about the same items over and over, and answering them by walking the whole
 * catalogue every time is what a large pack cannot afford.
 */
public final class UltsCraftCatalog {

  private static volatile Map<Item, List<UltsCraftRecipe>> byResult = Map.of();
  /** One entry per distinct stack some recipe produces, the items a recipe slot may be filled with. */
  private static volatile List<ItemStack> producible = List.of();
  /** The producible stacks of one item, so a slot only looks at the items it actually names. */
  private static volatile Map<Item, List<ItemStack>> producibleByItem = Map.of();
  /** Every catalogued recipe, which is what a reachability pass walks. */
  private static volatile List<UltsCraftRecipe> everything = List.of();
  /** Which of those stacks one ingredient accepts; asked again and again while a plan is worked out. */
  private static final Map<Ingredient, List<ItemStack>> CANDIDATES = new ConcurrentHashMap<>();
  /** The recipes that produce exactly one stack, asked once per stack while a pile is searched. */
  private static final Map<String, List<UltsCraftRecipe>> RESULT_RECIPES = new ConcurrentHashMap<>();

  private UltsCraftCatalog() {}

  /** Reads the recipes of the running server; called at startup and on {@code /ults reload}. */
  public static void rebuild(MinecraftServer server) {
    ContextMap context = SlotDisplayContext.fromLevel(server.overworld());
    Map<Item, List<UltsCraftRecipe>> index = new HashMap<>();
    long found = 0L;
    for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
      Recipe<?> recipe = holder.value();
      RecipeType<?> type = recipe.getType();
      boolean stonecutter = type == RecipeType.STONECUTTING;
      if (!stonecutter && type != RecipeType.CRAFTING) {
        continue;
      }
      if (recipe.isSpecial()) {
        // Special recipes decide their result while the game runs and have no ingredient list.
        continue;
      }
      List<Ingredient> ingredients = placement(recipe);
      if (ingredients.isEmpty()) {
        continue;
      }
      String id = holder.id().identifier().toString();
      for (var display : recipe.display()) {
        for (ItemStack produced : display.result().resolveForStacks(context)) {
          if (produced.isEmpty() || produced.getCount() < 1) {
            continue;
          }
          UltsCraftRecipe entry = new UltsCraftRecipe(
              stonecutter, id, ingredients, produced, produced.getCount());
          index.computeIfAbsent(produced.getItem(), key -> new ArrayList<>()).add(entry);
          found++;
        }
      }
    }
    Map<Item, List<UltsCraftRecipe>> frozen = new HashMap<>();
    // The route that yields the most per operation is tried first, then the recipe id keeps it stable.
    Comparator<UltsCraftRecipe> order = Comparator
        .comparingInt(UltsCraftRecipe::outputCount).reversed()
        .thenComparing(UltsCraftRecipe::id);
    List<UltsCraftRecipe> all = new ArrayList<>((int) found);
    index.forEach((item, recipes) -> {
      List<UltsCraftRecipe> sorted = new ArrayList<>(recipes);
      sorted.sort(order);
      frozen.put(item, List.copyOf(sorted));
      all.addAll(sorted);
    });
    Map<String, ItemStack> results = new LinkedHashMap<>();
    for (List<UltsCraftRecipe> recipes : frozen.values()) {
      for (UltsCraftRecipe recipe : recipes) {
        results.putIfAbsent(key(recipe.result()), recipe.result());
      }
    }
    List<ItemStack> producibleStacks = new ArrayList<>(results.values());
    producibleStacks.sort(Comparator.comparing(UltsCraftCatalog::key));
    Map<Item, List<ItemStack>> byItem = new HashMap<>();
    for (ItemStack stack : producibleStacks) {
      byItem.computeIfAbsent(stack.getItem(), key -> new ArrayList<>()).add(stack);
    }
    Map<Item, List<ItemStack>> frozenByItem = new HashMap<>();
    byItem.forEach((item, stacks) -> frozenByItem.put(item, List.copyOf(stacks)));

    byResult = Map.copyOf(frozen);
    producible = List.copyOf(producibleStacks);
    producibleByItem = Map.copyOf(frozenByItem);
    everything = List.copyOf(all);
    CANDIDATES.clear();
    RESULT_RECIPES.clear();
    UltsIngredients.clear();
    UltsMod.LOGGER.info(
        "UltStorage crafting catalog: {} recipe(s) for {} item(s)", found, frozen.size());
  }

  /** The recipes that produce exactly this stack, components included, best route first. */
  public static List<UltsCraftRecipe> recipes(ItemStack template) {
    if (template.isEmpty()) {
      return List.of();
    }
    return RESULT_RECIPES.computeIfAbsent(key(template), wanted -> {
      List<UltsCraftRecipe> matching = new ArrayList<>();
      for (UltsCraftRecipe recipe : byResult.getOrDefault(template.getItem(), List.of())) {
        if (ItemStack.isSameItemSameComponents(recipe.result(), template)) {
          matching.add(recipe);
        }
      }
      return List.copyOf(matching);
    });
  }

  /** Every catalogued recipe, for a pass that has to look at the whole graph at once. */
  public static List<UltsCraftRecipe> everything() {
    return everything;
  }

  /**
   * The stacks a recipe slot could be filled with.
   *
   * <p>Only what some recipe produces is worth reporting, because anything else can never be crafted
   * when the storage does not hold it anyway. A slot names its items, so only the producible stacks
   * of those items have to be looked at.
   *
   * @param ingredient the recipe slot
   * @return the matching stacks, in a stable order
   */
  public static List<ItemStack> candidates(Ingredient ingredient) {
    return CANDIDATES.computeIfAbsent(ingredient, slot -> {
      List<ItemStack> matching = new ArrayList<>();
      for (Item item : UltsIngredients.accepted(slot)) {
        for (ItemStack stack : producibleByItem.getOrDefault(item, List.of())) {
          if (slot.test(stack)) {
            matching.add(stack);
          }
        }
      }
      matching.sort(Comparator.comparing(UltsCraftCatalog::key));
      return List.copyOf(matching);
    });
  }

  private static String key(ItemStack stack) {
    return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + stack.getComponentsPatch();
  }

  /**
   * The ingredient of every grid slot, empty slots left out.
   *
   * <p>{@code PlacementInfo} lists the distinct ingredients and maps each slot onto one of them, so
   * expanding that mapping is what gives a repeated ingredient its real weight: four planks in four
   * slots have to count as four planks, not as one.
   */
  private static List<Ingredient> placement(Recipe<?> recipe) {
    PlacementInfo placement = recipe.placementInfo();
    List<Ingredient> distinct = placement.ingredients();
    IntList mapping = placement.slotsToIngredientIndex();
    List<Ingredient> ingredients = new ArrayList<>(mapping.size());
    for (int slot = 0; slot < mapping.size(); slot++) {
      int index = mapping.getInt(slot);
      if (index < 0 || index >= distinct.size()) {
        continue;
      }
      Ingredient ingredient = distinct.get(index);
      if (!ingredient.isEmpty()) {
        ingredients.add(ingredient);
      }
    }
    return List.copyOf(ingredients);
  }
}
