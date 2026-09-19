package com.flwolfy.ults.crafting;

import com.flwolfy.ults.data.config.UltsCraftingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/**
 * Turns "this much of this item" into crafting runs, or says how much could be made.
 *
 * <p>A recipe is only used while everything it needs is either in the pile or can itself be made from
 * the pile, so a recipe may consume what another recipe makes. Recipes that need a crafting table or a
 * stonecutter are only usable while that station is stored; the station is never consumed.
 *
 * <p>The search is depth limited and never enters the same item twice on one path, so recipes that feed
 * each other (a block into ingots into a block) cannot make it loop.
 */
public final class UltsCraftResolver {

  /** How deep a chain of recipes may go before the search gives up on that branch. */
  private static final int MAX_DEPTH = 10;
  /** How much work one question may cost; a recipe graph that feeds itself can never run away. */
  private static final int BUDGET = 200_000;

  private final UltsCraftingMode mode;
  private final boolean requireStation;
  private int budget = BUDGET;

  private UltsCraftResolver(UltsCraftingMode mode, boolean requireStation) {
    this.mode = mode;
    this.requireStation = requireStation;
  }

  /**
   * Plans the runs that cover a missing amount.
   *
   * @param template what should be produced
   * @param missing how many items are still needed
   * @param pool contents to craft from; the pile is left untouched when no plan is found
   * @param mode configured crafting mode
   * @param requireStation whether the stored station is needed
   * @return the runs, or {@code null} when the pile cannot cover the amount
   */
  public static @Nullable UltsCraftPlan plan(
      ItemStack template,
      long missing,
      UltsCraftPool pool,
      UltsCraftingMode mode,
      boolean requireStation
  ) {
    if (template.isEmpty() || missing <= 0L || !mode.enabled()) {
      return null;
    }
    return new UltsCraftResolver(mode, requireStation).planFor(template, missing, pool);
  }

  /**
   * The largest amount of an item the pile could produce right now.
   *
   * @param template what should be produced
   * @param pool contents to craft from; never modified
   * @param mode configured crafting mode
   * @param requireStation whether the stored station is needed
   * @return the largest amount, or {@code 0}
   */
  public static long capacity(
      ItemStack template,
      UltsCraftPool pool,
      UltsCraftingMode mode,
      boolean requireStation
  ) {
    if (template.isEmpty() || !mode.enabled() || pool.total() <= 0L) {
      return 0L;
    }
    return new UltsCraftResolver(mode, requireStation).capacityOf(template, pool);
  }

  // ================= //
  // ===== Plans ===== //
  // ================= //

  private @Nullable UltsCraftPlan planFor(ItemStack template, long missing, UltsCraftPool pool) {
    for (UltsCraftRecipe route : routes(template, true, pool)) {
      long operations = UltsCraftMath.divideRoundingUp(missing, route.outputCount());
      UltsCraftPool attempt = pool.copy();
      List<UltsCraftStep> steps = new ArrayList<>();
      if (gather(route, operations, attempt, steps, 1, path(template))) {
        pool.copyFrom(attempt);
        return new UltsCraftPlan(steps, UltsCraftMath.multiply(operations, route.outputCount()));
      }
    }
    return null;
  }

  private long capacityOf(ItemStack template, UltsCraftPool pool) {
    long best = 0L;
    for (UltsCraftRecipe route : routes(template, true, pool)) {
      long operations = maxOperations(route, template, pool);
      best = Math.max(best, UltsCraftMath.multiply(operations, route.outputCount()));
    }
    return best;
  }

  /**
   * The most operations of one route that fit into the pile.
   *
   * <p>No run can use more items than the pile holds, so that total is the upper bound and the search
   * doubles its way up to it instead of walking every amount.
   */
  private long maxOperations(UltsCraftRecipe route, ItemStack template, UltsCraftPool pool) {
    long total = pool.total();
    if (total <= 0L || !canRun(route, template, 1L, pool)) {
      return 0L;
    }
    long low = 1L;
    long high = 2L;
    while (high < total && canRun(route, template, high, pool)) {
      low = high;
      high = Math.min(total, UltsCraftMath.multiply(high, 2L));
    }
    high = Math.min(high, total);
    while (low < high) {
      long middle = low + (high - low + 1L) / 2L;
      if (canRun(route, template, middle, pool)) {
        low = middle;
      } else {
        high = middle - 1L;
      }
    }
    return low;
  }

  private boolean canRun(
      UltsCraftRecipe route,
      ItemStack template,
      long operations,
      UltsCraftPool pool
  ) {
    return gather(route, operations, pool.copy(), new ArrayList<>(), 1, path(template));
  }

  /**
   * Runs one recipe often enough, crafting whatever the pile does not hold as it goes.
   *
   * <p>What happens here is exactly what running the plan does later: every slot is made sure of
   * first, and only then is the run itself carried out, taking its ingredients out of the pile and
   * putting what it makes back in. A run can therefore use what an earlier run made.
   *
   * <p>The slots are filled in the order that fails fastest: a slot nothing can fill at all comes
   * first, then the slots with the fewest things that could fill them, and the slots the pile already
   * covers come last. A recipe that cannot work at all is therefore refused before anything is
   * crafted for it, which is what keeps a recipe graph that feeds itself from being walked forever.
   */
  private boolean gather(
      UltsCraftRecipe route,
      long operations,
      UltsCraftPool pool,
      List<UltsCraftStep> steps,
      int depth,
      Set<String> visiting
  ) {
    if (operations <= 0L || budget <= 0) {
      return false;
    }
    budget--;
    for (Ingredient ingredient : order(route.ingredients(), operations, pool)) {
      if (!provide(ingredient, operations, pool, steps, depth, visiting)) {
        return false;
      }
      // Taken as soon as it is there: the later slots of this run need what is left over, and the run
      // itself would take it in the same way.
      if (pool.takeMatching(ingredient, operations) < operations) {
        return false;
      }
    }
    steps.add(new UltsCraftStep(route, operations));
    pool.add(route.result(), UltsCraftMath.multiply(operations, route.outputCount()));
    return true;
  }

  /** The slots of a recipe, easiest to fill last. */
  private List<Ingredient> order(
      List<Ingredient> ingredients,
      long operations,
      UltsCraftPool pool
  ) {
    if (ingredients.size() < 2) {
      return ingredients;
    }
    List<Ingredient> ordered = new ArrayList<>(ingredients);
    ordered.sort(Comparator.comparingInt(ingredient -> rank(ingredient, operations, pool)));
    return ordered;
  }

  private int rank(Ingredient ingredient, long operations, UltsCraftPool pool) {
    if (pool.matches(ingredient) >= operations) {
      // Already in the pile: filling this slot cannot be what makes the recipe fail.
      return Integer.MAX_VALUE;
    }
    int candidates = UltsCraftCatalog.candidates(ingredient).size();
    // Nothing can fill it, so the recipe is impossible: look at it before anything else.
    return candidates == 0 ? 0 : 1 + Math.min(candidates, 1_000);
  }

  /**
   * Makes sure the pile holds enough for one ingredient slot, crafting runs for it when it does not.
   *
   * <p>Nothing is taken here: the run that needs the ingredient takes it when it is carried out, so
   * the pile this plan was worked out on ends up exactly where running the plan leaves it.
   */
  private boolean provide(
      Ingredient ingredient,
      long need,
      UltsCraftPool pool,
      List<UltsCraftStep> steps,
      int depth,
      Set<String> visiting
  ) {
    if (pool.matches(ingredient) >= need) {
      return true;
    }
    if (depth >= MAX_DEPTH) {
      return false;
    }
    for (ItemStack candidate : UltsCraftCatalog.candidates(ingredient)) {
      String key = key(candidate);
      if (visiting.contains(key)) {
        continue;
      }
      for (UltsCraftRecipe route : routes(candidate, false, pool)) {
        long shortfall = need - pool.matches(ingredient);
        if (shortfall <= 0L) {
          return true;
        }
        long operations = UltsCraftMath.divideRoundingUp(shortfall, route.outputCount());
        UltsCraftPool attempt = pool.copy();
        List<UltsCraftStep> made = new ArrayList<>();
        Set<String> deeper = new HashSet<>(visiting);
        deeper.add(key);
        if (gather(route, operations, attempt, made, depth + 1, deeper)) {
          pool.copyFrom(attempt);
          steps.addAll(made);
        }
        if (pool.matches(ingredient) >= need) {
          return true;
        }
      }
    }
    return false;
  }

  /** The routes of one item that may be used right now, best first. */
  private List<UltsCraftRecipe> routes(ItemStack template, boolean root, UltsCraftPool pool) {
    if (root && mode == UltsCraftingMode.SHULKER_BOXES_ONLY
        && !template.is(Items.SHULKER_BOX)) {
      // Only a box may be crafted as the item that is asked for; what a box needs may be anything.
      return List.of();
    }
    List<UltsCraftRecipe> usable = new ArrayList<>();
    for (UltsCraftRecipe route : UltsCraftCatalog.recipes(template)) {
      if (station(route, pool)) {
        usable.add(route);
      }
    }
    return usable;
  }

  private boolean station(UltsCraftRecipe route, UltsCraftPool pool) {
    if (!requireStation) {
      return true;
    }
    return route.needsStonecutter()
        ? pool.has(Items.STONECUTTER)
        : pool.has(Items.CRAFTING_TABLE);
  }

  private static Set<String> path(ItemStack template) {
    Set<String> visiting = new HashSet<>();
    visiting.add(key(template));
    return visiting;
  }

  private static String key(ItemStack template) {
    return BuiltInRegistries.ITEM.getKey(template.getItem()) + "|" + template.getComponentsPatch();
  }
}
