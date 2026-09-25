package com.flwolfy.ults.crafting;

import com.flwolfy.ults.data.config.UltsCraftingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <h2>Why one answer used to take a whole minute</h2>
 *
 * <p>The search tries recipes in every order it can think of, so the number of piles it looks at grows
 * with the depth. Two things have to be true for that to stay affordable, and both are what this class
 * is built around:
 *
 * <ul>
 *   <li>Looking at a pile has to be cheap. Attempts used to copy the whole pile — thousands of stacks
 *       — at every step, which by itself cost gigabytes of memory traffic for one item. A pile is now
 *       marked and rolled back instead, and a slot of a recipe is counted by item rather than by stack.
 *   <li>Hopeless branches have to be recognised before they are walked. An item no chain of recipes
 *       can reach from this pile is dropped from every recipe slot before the search starts, which is
 *       what {@link #reach()} works out. That one pass replaces almost the whole search.
 * </ul>
 *
 * <p>What is left is bounded twice over: by a number of steps and by a wall clock, so no pile can ever
 * hold a server tick hostage again. An answer that runs into either bound is reported as
 * {@link #UNKNOWN} instead of being remembered, and is worked out again at a quieter moment.
 */
public final class UltsCraftResolver {

  /** How deep a chain of recipes may go before the search gives up on that branch. */
  private static final int MAX_DEPTH = 10;
  /** How much work one answer may cost; a recipe graph that feeds itself can never run away. */
  private static final int MAX_ANSWER_NODES = 20_000;
  /** A plan really has to happen, so it may look further than a screen that is only asking. */
  private static final int MAX_PLAN_NODES = 200_000;
  /** How long one answer may take at the very most, whatever else goes wrong. */
  private static final long ANSWER_BACKSTOP_NANOS = 100_000_000L;
  /** The same ceiling for a plan, which is allowed to take longer but never a tick's worth. */
  private static final long PLAN_BACKSTOP_NANOS = 250_000_000L;
  /** How often the wall clock is read; it costs more than a search step does. */
  private static final int CLOCK_EVERY = 256;

  /** Reported when an answer could not be worked out in time; it is never remembered. */
  public static final long UNKNOWN = -1L;

  /**
   * Where the recipes come from. In game this is the catalogue of the running server; a test swaps in
   * its own small catalogue, which is the only thing that makes the search itself testable.
   */
  static UltsCraftSource source = new UltsCraftSource() {
    @Override
    public List<UltsCraftRecipe> recipes(ItemStack template) {
      return UltsCraftCatalog.recipes(template);
    }

    @Override
    public List<ItemStack> candidates(Ingredient ingredient) {
      return UltsCraftCatalog.candidates(ingredient);
    }

    @Override
    public List<UltsCraftRecipe> everything() {
      return UltsCraftCatalog.everything();
    }
  };

  private final UltsCraftPool pool;
  private final UltsCraftingMode mode;
  private final boolean requireStation;
  /** Whether the stations this pile needs are stored; captured before the search changes the pile. */
  private final boolean craftingTable;
  private final boolean stonecutter;

  /** Every stack some chain of recipes can make from this pile, which is what the search may enter. */
  private final Set<String> reachable = new HashSet<>();
  /** Slot to how much of it the untouched pile holds, worked out once per slot. */
  private final Map<Ingredient, Long> baseMatches = new HashMap<>();
  /** Slot to the stacks worth crafting for it, worked out once per slot. */
  private final Map<Ingredient, List<ItemStack>> fillable = new HashMap<>();
  /** Stack to the routes that may run right now, worked out once per stack. */
  private final Map<String, List<UltsCraftRecipe>> routeCache = new HashMap<>();
  /** Stack to whether one can be made at all, answered once per stack. */
  private final Map<String, Boolean> mades = new HashMap<>();
  /** Stack to the largest amount the pile can make of it, answered once per stack. */
  private final Map<String, Long> answers = new HashMap<>();

  private int budget;
  private long deadline = Long.MAX_VALUE;
  /** Whether the last search stopped on its budget or its clock rather than on the pile. */
  private boolean ranOut;

  // ==================== //
  // ===== Creation ===== //
  // ==================== //

  /**
   * Builds the view of one pile: what it can make, and how much of it.
   *
   * <p>This walks the recipe graph once, which is what makes every later question cheap. Nothing
   * mutates the pile.
   *
   * @param pool contents to craft from
   * @param mode configured crafting mode
   * @param requireStation whether the stored station is needed
   * @return the view of the pile
   */
  public static UltsCraftResolver of(
      UltsCraftPool pool,
      UltsCraftingMode mode,
      boolean requireStation
  ) {
    UltsCraftResolver resolver = new UltsCraftResolver(pool, mode, requireStation);
    resolver.reach();
    return resolver;
  }

  private UltsCraftResolver(UltsCraftPool pool, UltsCraftingMode mode, boolean requireStation) {
    this.pool = pool;
    this.mode = mode;
    this.requireStation = requireStation;
    this.craftingTable = pool.has(Items.CRAFTING_TABLE);
    this.stonecutter = pool.has(Items.STONECUTTER);
  }

  // ================== //
  // ===== Asking ===== //
  // ================== //

  /**
   * Whether the pile can make this item right now.
   *
   * <p>This is asked about every row of a listing, so it is answered from what the pile can reach
   * first — a lookup that rules out almost everything — and only then by really trying one run. A
   * single run needs one item for each of its slots, so trying one run is the whole question: the
   * pile cannot make an item exactly when every route it has is short of a slot.
   *
   * @param template the item in question
   * @return whether at least one can be made
   */
  public boolean craftable(ItemStack template) {
    return craftable(template, Long.MAX_VALUE);
  }

  /**
   * Whether the pile can make this item right now, or what it can reach when time runs out first.
   *
   * <p>A listing asks this about every row it might show, so it must never be able to spend a whole
   * tick on the question. When the deadline passes before the answer is worked out, the row is given
   * the answer the pile's reach gives it — which is the generous one, so a row is never dropped and
   * put back a tick later — and {@link #ranOut()} says that the exact answer is still owed.
   *
   * @param template the item in question
   * @param deadlineNanos when the answer is no longer wanted, as {@link System#nanoTime()}
   * @return whether at least one can be made
   */
  public boolean craftable(ItemStack template, long deadlineNanos) {
    if (template.isEmpty() || !mode.enabled() || pool.total() <= 0L) {
      return false;
    }
    if (mode == UltsCraftingMode.SHULKER_BOXES_ONLY && !template.is(Items.SHULKER_BOX)) {
      // Only a box may be crafted as the item that is asked for; what a box needs may be anything.
      return false;
    }
    ranOut = false;
    String wanted = key(template);
    Boolean remembered = mades.get(wanted);
    if (remembered != null) {
      return remembered;
    }
    if (!reachable.contains(wanted)) {
      // No chain of recipes leads here at all, so nothing has to be tried.
      mades.put(wanted, false);
      return false;
    }
    if (System.nanoTime() >= deadlineNanos) {
      ranOut = true;
      return true;
    }
    boolean value = canMakeOne(template, deadlineNanos);
    if (ranOut) {
      return true;
    }
    mades.put(wanted, value);
    return value;
  }

  /** Whether the last answer was given up on rather than worked out. */
  public boolean ranOut() {
    return ranOut;
  }

  /**
   * Tries one run of every route the item has, which is the same question as "could any be made".
   *
   * <p>What the pile can reach says a slot has something to fill it; that something may still be
   * wanted by another slot of the same run, and only trying the run tells the two apart. A chest that
   * wants eight planks is not craftable out of three, however reachable planks are.
   */
  private boolean canMakeOne(ItemStack template, long deadlineNanos) {
    budget = MAX_ANSWER_NODES;
    ranOut = false;
    deadline = Math.min(deadlineNanos, System.nanoTime() + ANSWER_BACKSTOP_NANOS);
    for (UltsCraftRecipe route : routes(template, true)) {
      if (canRun(route, template, 1L)) {
        return true;
      }
      if (ranOut) {
        return false;
      }
    }
    return false;
  }

  /**
   * The largest amount of an item the pile could produce right now.
   *
   * @param template what should be produced
   * @param deadlineNanos when the answer is no longer wanted, as {@link System#nanoTime()}
   * @return the largest amount, {@code 0} when nothing can be made, or {@link #UNKNOWN} when the
   *     deadline passed before the answer was worked out
   */
  public long capacity(ItemStack template, long deadlineNanos) {
    if (template.isEmpty() || !mode.enabled() || pool.total() <= 0L) {
      return 0L;
    }
    if (mode == UltsCraftingMode.SHULKER_BOXES_ONLY && !template.is(Items.SHULKER_BOX)) {
      return 0L;
    }
    String wanted = key(template);
    Long remembered = answers.get(wanted);
    if (remembered != null) {
      return remembered;
    }
    boolean possible = craftable(template, deadlineNanos);
    if (ranOut) {
      // The pile can reach it, but the exact answer is still owed, so nothing is claimed yet.
      return UNKNOWN;
    }
    if (!possible) {
      answers.put(wanted, 0L);
      return 0L;
    }
    if (System.nanoTime() >= deadlineNanos) {
      return UNKNOWN;
    }
    long value = capacityOf(template, deadlineNanos);
    if (value == UNKNOWN) {
      return UNKNOWN;
    }
    answers.put(wanted, value);
    return value;
  }

  /** The largest amount of an item the pile could produce, worked out however long it takes. */
  public long capacity(ItemStack template) {
    return capacity(template, Long.MAX_VALUE);
  }

  // ================== //
  // ===== Plans ===== //
  // ================== //

  /**
   * Plans the runs that cover a missing amount.
   *
   * <p>What happens here is exactly what running the plan does later: every slot is made sure of
   * first, and only then is the run itself carried out, taking its ingredients out of the pile and
   * putting what it makes back in. A run can therefore use what an earlier run made.
   *
   * <p>The slots are filled in the order that fails fastest: a slot nothing can fill at all comes
   * first, then the slots with the fewest things that could fill them, and the slots the pile already
   * covers come last. A recipe that cannot work at all is therefore refused before anything is
   * crafted for it, which is what keeps a recipe graph that feeds itself from being walked forever.
   *
   * <p>A plan that is found leaves the pile holding the result of its runs; a plan that is not found
   * leaves the pile exactly as it was.
   *
   * @param template what should be produced
   * @param missing how many items are still needed
   * @return the runs, or {@code null} when the pile cannot cover the amount
   */
  public @Nullable UltsCraftPlan plan(ItemStack template, long missing) {
    if (template.isEmpty() || missing <= 0L || !mode.enabled() || pool.total() <= 0L) {
      return null;
    }
    if (!craftable(template)) {
      return null;
    }
    budget = MAX_PLAN_NODES;
    ranOut = false;
    deadline = System.nanoTime() + PLAN_BACKSTOP_NANOS;
    for (UltsCraftRecipe route : routes(template, true)) {
      long operations = UltsCraftMath.divideRoundingUp(missing, route.outputCount());
      int mark = pool.mark();
      List<UltsCraftStep> steps = new ArrayList<>();
      if (gather(route, operations, steps, 1, path(template))) {
        // The pile the plan was worked out on is the pile that is kept.
        return new UltsCraftPlan(steps, UltsCraftMath.multiply(operations, route.outputCount()));
      }
      pool.rollback(mark);
      if (ranOut) {
        return null;
      }
    }
    return null;
  }

  // ============================== //
  // ===== What the pile can do ==== //
  // ============================== //

  /**
   * Marks every stack some chain of recipes can make from this pile.
   *
   * <p>A recipe counts as soon as every one of its slots could be filled by something the pile holds
   * or by something already marked. Growing that set until it settles is a walk of the recipe list a
   * handful of times, and it replaces the great majority of the search: a slot may only be filled by
   * an item that is marked, so branches that could never have worked are never entered.
   *
   * <p>Each round may only use what the round before it reached, so the set grows exactly one recipe
   * deep per round and a chain longer than the search is never marked as reachable. Marking such a
   * chain would show an item as craftable that the search then refuses to make.
   */
  private void reach() {
    if (!mode.enabled()) {
      return;
    }
    List<UltsCraftRecipe> catalogued = source.everything();
    int count = catalogued.size();
    if (count == 0) {
      return;
    }
    String[] produced = new String[count];
    for (int index = 0; index < count; index++) {
      produced[index] = key(catalogued.get(index).result());
    }
    boolean grown = true;
    for (int round = 0; round <= MAX_DEPTH && grown; round++) {
      grown = false;
      Set<String> known = Set.copyOf(reachable);
      for (int index = 0; index < count; index++) {
        UltsCraftRecipe recipe = catalogued.get(index);
        if (reachable.contains(produced[index]) || !station(recipe) || !runnable(recipe, known)) {
          continue;
        }
        reachable.add(produced[index]);
        grown = true;
      }
    }
  }

  /** Whether every slot of a recipe could be filled by what the pile holds or already reaches. */
  private boolean runnable(UltsCraftRecipe recipe, Set<String> known) {
    for (Ingredient ingredient : recipe.ingredients()) {
      if (!fillableFromPile(ingredient, known)) {
        return false;
      }
    }
    return true;
  }

  private boolean fillableFromPile(Ingredient ingredient, Set<String> known) {
    if (baseMatches(ingredient) > 0L) {
      return true;
    }
    for (ItemStack candidate : source.candidates(ingredient)) {
      if (known.contains(key(candidate))) {
        return true;
      }
    }
    return false;
  }

  /** How much of a slot the untouched pile holds; only ever asked before the search changes it. */
  private long baseMatches(Ingredient ingredient) {
    Long remembered = baseMatches.get(ingredient);
    if (remembered != null) {
      return remembered;
    }
    long value = pool.matches(ingredient);
    baseMatches.put(ingredient, value);
    return value;
  }

  // ============================== //
  // ===== Searching the pile ====== //
  // ============================== //

  private long capacityOf(ItemStack template, long deadlineNanos) {
    budget = MAX_ANSWER_NODES;
    ranOut = false;
    deadline = Math.min(deadlineNanos, System.nanoTime() + ANSWER_BACKSTOP_NANOS);
    long best = 0L;
    for (UltsCraftRecipe route : routes(template, true)) {
      long operations = maxOperations(route, template);
      if (ranOut) {
        return UNKNOWN;
      }
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
  private long maxOperations(UltsCraftRecipe route, ItemStack template) {
    long total = pool.total();
    if (total <= 0L || !canRun(route, template, 1L)) {
      return 0L;
    }
    long low = 1L;
    long high = 2L;
    while (high < total && canRun(route, template, high)) {
      low = high;
      high = Math.min(total, UltsCraftMath.multiply(high, 2L));
      if (ranOut) {
        return 0L;
      }
    }
    high = Math.min(high, total);
    while (low < high) {
      long middle = low + (high - low + 1L) / 2L;
      if (canRun(route, template, middle)) {
        low = middle;
      } else {
        high = middle - 1L;
      }
      if (ranOut) {
        return 0L;
      }
    }
    return low;
  }

  private boolean canRun(UltsCraftRecipe route, ItemStack template, long operations) {
    int mark = pool.mark();
    boolean ran = gather(route, operations, null, 1, path(template));
    // A question never leaves the pile changed, whatever the answer was.
    pool.rollback(mark);
    return ran;
  }

  /**
   * Runs one recipe often enough, crafting whatever the pile does not hold as it goes.
   *
   * @param steps where the runs are recorded, or {@code null} when only the answer is wanted
   */
  private boolean gather(
      UltsCraftRecipe route,
      long operations,
      @Nullable List<UltsCraftStep> steps,
      int depth,
      Set<String> visiting
  ) {
    if (operations <= 0L || budget <= 0) {
      return false;
    }
    if ((budget & (CLOCK_EVERY - 1)) == 0 && System.nanoTime() >= deadline) {
      ranOut = true;
      return false;
    }
    budget--;
    for (Ingredient ingredient : order(route.ingredients(), operations)) {
      // Taken as soon as it is there: the later slots of this run need what is left over, and the run
      // itself would take it in the same way. What the slot has is handed back, so the pile is not
      // counted twice for the same slot.
      long available = provide(ingredient, operations, steps, depth, visiting);
      if (available < operations) {
        return false;
      }
      if (pool.takeMatching(ingredient, operations) < operations) {
        return false;
      }
    }
    if (steps != null) {
      steps.add(new UltsCraftStep(route, operations));
    }
    pool.add(route.result(), UltsCraftMath.multiply(operations, route.outputCount()));
    return true;
  }

  /**
   * Makes sure the pile holds enough for one ingredient slot, crafting runs for it when it does not.
   *
   * <p>Nothing is taken here: the run that needs the ingredient takes it when it is carried out, so
   * the pile this plan was worked out on ends up exactly where running the plan leaves it.
   *
   * @return how much of the slot the pile holds once the runs are made, or {@code -1} when it cannot
   *     be filled at all
   */
  private long provide(
      Ingredient ingredient,
      long need,
      @Nullable List<UltsCraftStep> steps,
      int depth,
      Set<String> visiting
  ) {
    long available = pool.matches(ingredient);
    if (available >= need) {
      return available;
    }
    if (depth >= MAX_DEPTH || ranOut) {
      return -1L;
    }
    for (ItemStack candidate : candidates(ingredient)) {
      String candidateKey = key(candidate);
      if (!visiting.add(candidateKey)) {
        continue;
      }
      try {
        for (UltsCraftRecipe route : routes(candidate, false)) {
          long shortfall = need - available;
          if (shortfall <= 0L) {
            return available;
          }
          long operations = UltsCraftMath.divideRoundingUp(shortfall, route.outputCount());
          int mark = pool.mark();
          List<UltsCraftStep> made = steps == null ? null : new ArrayList<>();
          if (gather(route, operations, made, depth + 1, visiting)) {
            if (steps != null) {
              steps.addAll(made);
            }
            available = pool.matches(ingredient);
          } else {
            // A run that did not work out leaves the pile exactly as it found it.
            pool.rollback(mark);
          }
          if (ranOut) {
            return -1L;
          }
          if (available >= need) {
            return available;
          }
        }
      } finally {
        visiting.remove(candidateKey);
      }
    }
    return -1L;
  }

  /** The slots of a recipe, easiest to fill last. */
  private List<Ingredient> order(List<Ingredient> ingredients, long operations) {
    if (ingredients.size() < 2) {
      return ingredients;
    }
    List<Ingredient> ordered = new ArrayList<>(ingredients);
    Map<Ingredient, Integer> ranks = new HashMap<>(ingredients.size() * 2);
    ordered.sort(Comparator.comparingInt(
        ingredient -> ranks.computeIfAbsent(ingredient, slot -> rank(slot, operations))));
    return ordered;
  }

  private int rank(Ingredient ingredient, long operations) {
    if (pool.matches(ingredient) >= operations) {
      // Already in the pile: filling this slot cannot be what makes the recipe fail.
      return Integer.MAX_VALUE;
    }
    int candidates = candidates(ingredient).size();
    // Nothing can fill it, so the recipe is impossible: look at it before anything else.
    return candidates == 0 ? 0 : 1 + Math.min(candidates, 1_000);
  }

  /** The routes of one item that may be used right now, best first. */
  private List<UltsCraftRecipe> routes(ItemStack template, boolean root) {
    if (root && mode == UltsCraftingMode.SHULKER_BOXES_ONLY && !template.is(Items.SHULKER_BOX)) {
      return List.of();
    }
    return routeCache.computeIfAbsent(key(template), wanted -> {
      List<UltsCraftRecipe> usable = new ArrayList<>();
      for (UltsCraftRecipe route : source.recipes(template)) {
        if (station(route)) {
          usable.add(route);
        }
      }
      return List.copyOf(usable);
    });
  }

  private boolean station(UltsCraftRecipe route) {
    if (!requireStation) {
      return true;
    }
    return route.needsStonecutter() ? stonecutter : craftingTable;
  }

  /**
   * The stacks worth crafting for one slot: what a recipe makes and what this pile can reach.
   *
   * <p>Anything else can never be filled, so leaving it out is exactly what keeps the search from
   * walking the whole catalogue at every step.
   */
  private List<ItemStack> candidates(Ingredient ingredient) {
    return fillable.computeIfAbsent(ingredient, slot -> {
      List<ItemStack> usable = new ArrayList<>();
      for (ItemStack candidate : source.candidates(slot)) {
        if (reachable.contains(key(candidate))) {
          usable.add(candidate);
        }
      }
      return List.copyOf(usable);
    });
  }

  private static Set<String> path(ItemStack template) {
    Set<String> visiting = new HashSet<>();
    visiting.add(key(template));
    return visiting;
  }

  private static String key(ItemStack template) {
    return BuiltInRegistries.ITEM.getKey(template.getItem()) + "|" + template.getComponentsPatch();
  }

  // ============================= //
  // ===== One off questions ===== //
  // ============================= //

  /**
   * The largest amount of an item a pile could produce right now.
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
    return of(pool, mode, requireStation).capacity(template);
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
    return of(pool, mode, requireStation).plan(template, missing);
  }
}
