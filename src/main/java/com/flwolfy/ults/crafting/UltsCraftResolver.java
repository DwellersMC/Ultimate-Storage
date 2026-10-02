package com.flwolfy.ults.crafting;

import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.state.UltsStoredView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/**
 * Turns "this much of this item" into crafting runs, or says how much could be made.
 *
 * <p>A recipe is only used while everything it needs is either in the pile or can itself be made from
 * the pile, so a recipe may consume what another recipe makes. Recipes that need a crafting table or a
 * stonecutter require that station in the initial pile. The station check consumes nothing;
 * a recipe may still explicitly name a station item as one of its ingredients.
 *
 * <p>The search is depth limited and never enters the same item twice on one path, so recipes that feed
 * each other (a block into ingots into a block) cannot make it loop.
 *
 * <p>Materials and root recipes can be combined. Continuations make every allocation provisional:
 * a later slot's failure can change an earlier choice. Recorded steps retain the concrete allocation
 * so execution does not choose different materials after child runs have been reordered.
 *
 * <p>Several independent optimizations reduce the number of states without changing feasibility:
 *
 * <ul>
 *   <li>Looking at a pile has to be cheap. Attempts used to copy the whole pile — thousands of stacks
 *       — at every step, which by itself cost gigabytes of memory traffic for one item. A pile is now
 *       marked and rolled back instead, and a slot of a recipe is counted by item rather than by stack.
 *   <li>Hopeless branches have to be recognised before they are walked. An item no chain of recipes
 *       can reach from this pile is dropped from every recipe slot before the search starts, which is
 *       what {@link #reach()} works out. Returns are included in that reachability pass.
 *   <li>Ingredient bounds group semantically identical slots. Exact rational resource potentials
 *       also prove shortfalls when several conversion routes compete for the same raw materials.
 * </ul>
 *
 * <p>What is left is bounded twice over: by a number of steps and by a wall clock, so no pile can ever
 * run without limit. An answer that runs into either bound is reported as
 * {@link #UNKNOWN} instead of being remembered, and is worked out again at a quieter moment.
 */
public final class UltsCraftResolver {

  /** How deep a chain of recipes may go before the search gives up on that branch. */
  private static final int MAX_DEPTH = 10;
  /** How much work one answer may cost; a recipe graph that feeds itself can never run away. */
  private static final int MAX_ANSWER_NODES = 20_000;
  /** A plan really has to happen, so it may look further than a screen that is only asking. */
  private static final int MAX_PLAN_NODES = 200_000;

  /**
   * How many recipes the reachability pass may look at before it stops marking.
   *
   * <p>That pass walks the whole catalogue up to {@link #MAX_DEPTH} times, and it runs before any question
   * has a chance to say it has waited long enough. The budget is counted in recipes looked at rather than
   * in time, so the same storage answers the same way on every machine.
   */
  private static final int MAX_REACH_NODES = 400_000;
  /** How long one answer may take at the very most, whatever else goes wrong. */
  private static final long ANSWER_BACKSTOP_NANOS = 100_000_000L;
  /** The same ceiling for a plan, which is allowed more work than a background listing. */
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
  private final Map<String, ItemStack> reachableReturns = new HashMap<>();
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
  /** Also bound Java call-stack depth when many small runs must be combined. */
  private int frames;
  private Set<Item> possibleReturns;
  private Set<Item> returnDependent;
  private final Map<Ingredient, List<Item>> slotKeys = new HashMap<>();
  private @Nullable UltsCraftPotential potential;

  /**
   * Whether the reachability pass stopped at its budget, which makes everything this view says about what
   * could be crafted a lower bound rather than the whole truth.
   */
  private boolean reachTruncated;

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
    ranOut = false;
    if (template.isEmpty() || !mode.enabled() || pool.total() <= 0L) {
      return false;
    }
    if (mode == UltsCraftingMode.SHULKER_BOXES_ONLY && !template.is(Items.SHULKER_BOX)) {
      // Only a box may be crafted as the item that is asked for; what a box needs may be anything.
      return false;
    }
    String wanted = key(template);
    Boolean remembered = mades.get(wanted);
    if (remembered != null) {
      return remembered;
    }
    if (!reachable.contains(wanted) && !reachTruncated) {
      // No chain of recipes leads here at all, so nothing has to be tried. A pass that stopped at its
      // budget cannot say that, so the item is looked for properly instead of being written off.
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

  /** Whether the reachability pass stopped at its budget, so this view knows less than it could. */
  public boolean reachTruncated() {
    return reachTruncated;
  }

  /**
   * Tries one run of every route the item has, which is the same question as "could any be made".
   *
   * <p>What the pile can reach says a slot has something to fill it; that something may still be
   * wanted by another slot of the same run, and only trying the run tells the two apart. A chest that
   * wants eight planks is not craftable out of three, however reachable planks are.
   */
  private boolean canMakeOne(ItemStack template, long deadlineNanos) {
    potential = null;
    budget = MAX_ANSWER_NODES;
    ranOut = false;
    deadline = Math.min(deadlineNanos, System.nanoTime() + ANSWER_BACKSTOP_NANOS);
    return canProduce(template, 1L);
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
    ranOut = false;
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
      ranOut = true;
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
    ranOut = false;
    if (template.isEmpty() || missing <= 0L || !mode.enabled() || pool.total() <= 0L) {
      return null;
    }
    if (!craftable(template)) {
      return null;
    }
    budget = MAX_PLAN_NODES;
    ranOut = false;
    deadline = System.nanoTime() + PLAN_BACKSTOP_NANOS;
    potential = UltsCraftPotential.of(template, source.everything().stream().filter(this::station).toList(), deadline);
    // The same rule the capacity is worked out under: a plan may not consume the very item it is
    // making from the pile, or a recipe that runs both ways would be planned as a round trip that
    // makes nothing. A withdrawal has already kept what it promised, so this only closes the surplus.
    UltsCraftPool.Keep previous = pool.keepState();
    pool.reserve(template, pool.amount(template));
    try {
      int mark = pool.mark();
      long original = pool.amount(template);
      List<UltsCraftStep> steps = new ArrayList<>();
      frames = 0;
      if (produce(routes(template, true), missing, steps, 1, path(template), () -> true)) {
        return new UltsCraftPlan(steps, pool.amount(template) - original);
      }
      pool.rollback(mark);
      return null;
    } finally {
      pool.restoreKeep(previous);
    }
  }

  /** A final stock requirement, rather than an independently promised crafting run. */
  public record Goal(ItemStack template, long amount) {
    public Goal {
      template = template.copyWithCount(1);
      if (template.isEmpty() || amount < 0L) throw new IllegalArgumentException("Invalid crafting goal");
    }
  }

  /**
   * Plans all goals in one search. A later goal or final check can backtrack every earlier allocation.
   * Existing and produced goal stock is reserved only up to the amount promised, so surplus outputs
   * remain available to later goals. Failure restores the pile and all incoming reservations.
   */
  public @Nullable List<UltsCraftPlan> planTogether(
      List<Goal> goals, BooleanSupplier finish, long deadlineNanos) {
    budget = MAX_PLAN_NODES;
    ranOut = false;
    frames = 0;
    deadline = Math.min(deadlineNanos, System.nanoTime() + PLAN_BACKSTOP_NANOS);
    if (System.nanoTime() >= deadline) { ranOut = true; return null; }
    UltsCraftPotential joint = UltsCraftPotential.of(goals.stream().map(Goal::template).toList(),
        source.everything().stream().filter(this::station).toList(), deadline);
    if (joint != null && !joint.sufficient(pool, goals)) return null;
    int mark = pool.mark();
    UltsCraftPool.Keep incoming = pool.keepState();
    for (Goal goal : goals) pool.reserve(goal.template(), Math.min(goal.amount(), pool.amount(goal.template())));
    List<UltsCraftPlan> plans = new ArrayList<>();
    Map<String, UltsCraftPotential> bounds = new HashMap<>();
    try {
      if (goals(goals, 0, plans, bounds, finish)) return List.copyOf(plans);
      pool.rollback(mark);
      return null;
    } finally { pool.restoreKeep(incoming); }
  }

  private boolean goals(List<Goal> goals, int index, List<UltsCraftPlan> plans,
      Map<String, UltsCraftPotential> bounds, BooleanSupplier finish) {
    if (System.nanoTime() >= deadline) { ranOut = true; return false; }
    if (index == goals.size()) return finish.getAsBoolean();
    Goal goal = goals.get(index);
    ItemStack template = goal.template();
    long original = pool.amount(template);
    long missing = Math.max(0L, goal.amount() - original);
    if (missing == 0L) {
      plans.add(UltsCraftPlan.NONE);
      if (goals(goals, index + 1, plans, bounds, finish)) return true;
      plans.removeLast();
      return false;
    }
    if (!mode.enabled() || (mode == UltsCraftingMode.SHULKER_BOXES_ONLY && !template.is(Items.SHULKER_BOX))) {
      return false;
    }
    String wanted = key(template);
    if (!reachable.contains(wanted) && !reachTruncated) return false;
    if (!bounds.containsKey(wanted)) bounds.put(wanted,
        UltsCraftPotential.of(template, source.everything().stream().filter(this::station).toList(), deadline));
    UltsCraftPotential previousPotential = potential;
    potential = bounds.get(wanted);
    UltsCraftPool.Keep beforeGoal = pool.keepState();
    List<UltsCraftStep> steps = new ArrayList<>();
    try {
      return produce(routes(template, true), missing, steps, 1, path(template), () -> {
        UltsCraftPool.Keep branch = pool.keepState();
        pool.restoreKeep(beforeGoal);
        pool.reserve(template, goal.amount());
        plans.add(new UltsCraftPlan(steps, pool.amount(template) - original));
        try {
          if (goals(goals, index + 1, plans, bounds, finish)) return true;
          plans.removeLast();
          return false;
        } finally { pool.restoreKeep(branch); }
      });
    } finally { potential = previousPotential; }
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
    List<List<String>> returned = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      UltsCraftRecipe recipe = catalogued.get(index);
      produced[index] = key(recipe.result());
      List<String> returns = new ArrayList<>();
      if (!recipe.needsStonecutter()) {
        for (Ingredient slot : recipe.ingredients()) {
          for (Item item : UltsIngredients.accepted(slot)) {
            var remainder = item.getCraftingRemainder();
            if (remainder != null) {
              ItemStack template = remainder.create().copyWithCount(1);
              String returnedKey = key(template);
              returns.add(returnedKey);
              reachableReturns.putIfAbsent(returnedKey, template);
            }
          }
        }
      }
      returned.add(List.copyOf(returns));
    }
    // This pass runs before any question is asked and has no clock of its own, so on a pack with a very
    // large recipe catalogue it is the one place that could spend a whole tick without noticing. It is a
    // filter rather than an answer — everything it marks is verified again when a recipe is really tried —
    // so it stops at a budget and says that it stopped: fewer recipes are known to be in reach, and the
    // view that was built on a truncated pass is treated as one that has not caught up yet.
    int spent = 0;
    boolean grown = true;
    for (int round = 0; round <= MAX_DEPTH && grown; round++) {
      grown = false;
      Set<String> known = Set.copyOf(reachable);
      for (int index = 0; index < count; index++) {
        if (++spent > MAX_REACH_NODES) {
          reachTruncated = true;
          return;
        }
        UltsCraftRecipe recipe = catalogued.get(index);
        if ((reachable.contains(produced[index]) && reachable.containsAll(returned.get(index)))
            || !station(recipe) || !runnable(recipe, known)) {
          continue;
        }
        grown |= reachable.add(produced[index]);
        grown |= reachable.addAll(returned.get(index));
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
    for (var returned : reachableReturns.entrySet()) {
      if (known.contains(returned.getKey()) && ingredient.test(returned.getValue())) return true;
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
    potential = UltsCraftPotential.of(template, source.everything().stream().filter(this::station).toList(), deadline);
    UltsCraftPool.Keep previous = pool.keepState();
    pool.reserve(template, pool.amount(template));
    try {
      long low = 0L, high = 1L;
      // Expansion and mixed routes are tested by the same feasibility search as planning.
      while (canProduce(template, high)) {
        low = high;
        if (high == Long.MAX_VALUE) return high;
        high = UltsCraftMath.multiply(high, 2L);
      }
      if (ranOut) return UNKNOWN;
      while (low < high - 1L) {
        long middle = low + (high - low) / 2L;
        if (canProduce(template, middle)) low = middle;
        else high = middle;
        if (ranOut) return UNKNOWN;
      }
      return low;
    } finally { pool.restoreKeep(previous); }
  }

  private boolean canProduce(ItemStack template, long need) {
    int mark = pool.mark();
    UltsCraftPool.Keep previous = pool.keepState();
    pool.reserve(template, pool.amount(template));
    frames = 0;
    boolean result = produce(routes(template, true), need, null, 1, path(template), () -> true);
    pool.rollback(mark);
    pool.restoreKeep(previous);
    return result;
  }

  @FunctionalInterface
  private interface Continuation { boolean run(); }

  /** Every allocation pays for work. Exhaustion is not a proof of infeasibility. */
  private boolean visit() {
    if (ranOut || budget-- <= 0 || frames >= 128
        || ((budget & (CLOCK_EVERY - 1)) == 0 && System.nanoTime() >= deadline)) {
      ranOut = true;
      return false;
    }
    return true;
  }

  /** Combine provisional batches and backtrack them when subsequent requirements fail. */
  private boolean produce(List<UltsCraftRecipe> options, long need,
      @Nullable List<UltsCraftStep> steps, int depth, Set<String> visiting, Continuation next) {
    return produce(options, options.stream().map(UltsCraftRecipe::result).toList(), need,
        steps, depth, visiting, next);
  }

  private long outputStock(List<ItemStack> goal) {
    Set<String> counted = new HashSet<>();
    long amount = 0L;
    for (ItemStack template : goal) {
      if (counted.add(com.flwolfy.ults.data.state.UltsStackKinds.of(template))) {
        amount = UltsCraftMath.add(amount, pool.amount(template));
      }
    }
    return amount;
  }

  private boolean produce(List<UltsCraftRecipe> options, List<ItemStack> goal, long need,
      @Nullable List<UltsCraftStep> steps, int depth, Set<String> visiting, Continuation next) {
    if (need <= 0L) return next.run();
    if (!visit()) return false;
    if (depth == 1 && potential != null && potential.bound(pool) < need) return false;
    if (upper(options, depth, visiting) < need) return false;
    frames++;
    try {
      for (int routeIndex = 0; routeIndex < options.size(); routeIndex++) {
        UltsCraftRecipe route = options.get(routeIndex);
        Set<String> routePath = new HashSet<>(visiting);
        routePath.add(key(route.result()));
        long wanted = UltsCraftMath.divideRoundingUp(need, route.outputCount());
        boolean reusable = options.stream().anyMatch(option -> returnDependent != null
            && returnDependent.contains(option.result().getItem()));
        // With no reusable returns, each route's quantity need only be chosen once.
        List<UltsCraftRecipe> later = reusable ? options : options.subList(routeIndex + 1, options.size());
        long bound = upper(List.of(route), depth, routePath);
        if (bound >= UltsCraftMath.multiply(wanted, route.outputCount())
            && batch(route, wanted, later, goal, need, steps, depth, routePath, visiting, next)) return true;
        if (ranOut) return false;
        long maximum = maxBatch(route, wanted, depth, routePath);
        if (ranOut) return false;
        if (maximum == wanted) maximum--;
        for (long operations = maximum; operations > 0L; operations--) {
          if (!visit()) return false;
          if (batch(route, operations, later, goal, need, steps, depth, routePath, visiting, next)) return true;
          if (ranOut) return false;
        }
      }
      return false;
    } finally { frames--; }
  }

  private boolean batch(UltsCraftRecipe route, long operations, List<UltsCraftRecipe> later,
      List<ItemStack> goal, long need, @Nullable List<UltsCraftStep> steps, int depth,
      Set<String> routePath, Set<String> visiting, Continuation next) {
    long before = outputStock(goal);
    return gather(route, operations, steps, depth, routePath, () -> {
      UltsCraftPool.Keep previous = pool.keepState();
      if (depth == 1) pool.reserve(route.result(), pool.amount(route.result()));
      long gain = outputStock(goal) - before;
      long remaining = gain >= 0L ? Math.max(0L, need - gain) : UltsCraftMath.add(need, -gain);
      try { return produce(later, goal, remaining, steps, depth, visiting, next); }
      finally { pool.restoreKeep(previous); }
    });
  }

  private long maxBatch(UltsCraftRecipe route, long high, int depth, Set<String> visiting) {
    if (probeBatch(route, high, depth, visiting)) return high;
    long low = 0L;
    while (!ranOut && low < high - 1L) {
      long middle = low + (high - low) / 2L;
      if (probeBatch(route, middle, depth, visiting)) low = middle;
      else high = middle;
    }
    return low;
  }

  private boolean probeBatch(UltsCraftRecipe route, long operations, int depth, Set<String> visiting) {
    if (upper(List.of(route), depth, visiting) < UltsCraftMath.multiply(operations, route.outputCount())) {
      return false;
    }
    int mark = pool.mark();
    boolean result = gather(route, operations, null, depth, visiting, () -> true);
    pool.rollback(mark);
    return result;
  }

  private boolean gather(UltsCraftRecipe route, long operations,
      @Nullable List<UltsCraftStep> steps, int depth, Set<String> visiting, Continuation next) {
    if (!visit()) return false;
    int mark = pool.mark(), stepMark = steps == null ? 0 : steps.size();
    boolean result = slots(route, order(route.ingredients(), operations), 0, operations,
        new ArrayList<>(), steps, depth, visiting, next);
    if (!result) { pool.rollback(mark); truncate(steps, stepMark); }
    return result;
  }

  private boolean slots(UltsCraftRecipe route, List<Ingredient> ingredients, int index,
      long operations, List<UltsStoredView> consumed, @Nullable List<UltsCraftStep> steps,
      int depth, Set<String> visiting, Continuation next) {
    if (index == ingredients.size()) {
      List<UltsStoredView> returns = new ArrayList<>();
      if (!route.needsStonecutter()) {
        consumed.forEach(material -> UltsCraftStep.addRemainder(material, returns));
      }
      for (UltsStoredView returned : returns) pool.add(returned.template(), returned.amount());
      UltsCraftStep step = new UltsCraftStep(route, operations, consumed);
      pool.add(route.result(), step.output());
      if (steps != null) steps.add(step);
      return next.run();
    }
    Ingredient slot = ingredients.get(index);
    return consume(slot, pool.matchingViews(slot), 0, operations, consumed, steps, depth, visiting, true,
        () -> slots(route, ingredients, index + 1, operations, consumed, steps, depth, visiting, next));
  }

  /** Reserve concrete materials; later failures can change a slot's allocation or recipe. */
  private boolean consume(Ingredient slot, List<UltsStoredView> held, int index, long need,
      List<UltsStoredView> consumed, @Nullable List<UltsCraftStep> steps,
      int depth, Set<String> visiting, boolean allowCraft, Continuation next) {
    if (need <= 0L) return next.run();
    if (!visit()) return false;
    if (index == held.size()) {
      if (!allowCraft || depth >= MAX_DEPTH) return false;
      List<UltsCraftRecipe> options = new ArrayList<>();
      for (ItemStack candidate : candidates(slot)) {
        if (!visiting.contains(key(candidate))) {
          for (UltsCraftRecipe route : routes(candidate, false)) {
            if (!options.contains(route)) options.add(route);
          }
        }
      }
      return produce(options, need, steps, depth + 1, visiting,
          () -> consume(slot, pool.matchingViews(slot), 0, need, consumed, steps, depth, visiting, false, next));
    }
    UltsStoredView material = held.get(index);
    long maximum = Math.min(need, material.amount()), minimum = 0L;
    if (!allowCraft || candidates(slot).isEmpty() || depth >= MAX_DEPTH) {
      long rest = 0L;
      for (int other = index + 1; other < held.size(); other++) {
        rest = UltsCraftMath.add(rest, held.get(other).amount());
      }
      minimum = Math.max(0L, need - rest);
    }
    for (long amount = maximum; amount >= minimum; amount--) {
      if (!visit()) return false;
      int mark = pool.mark(), stepMark = steps == null ? 0 : steps.size(), materialMark = consumed.size();
      if (amount == 0L || pool.take(material.template(), amount) == amount) {
        if (amount > 0L) consumed.add(new UltsStoredView(material.template(), amount, false));
        if (consume(slot, held, index + 1, need - amount, consumed, steps, depth, visiting, allowCraft, next)) {
          return true;
        }
      }
      pool.rollback(mark);
      truncate(steps, stepMark);
      consumed.subList(materialMark, consumed.size()).clear();
      if (ranOut || amount == 0L) return false;
    }
    return false;
  }

  private static void truncate(@Nullable List<UltsCraftStep> steps, int size) {
    if (steps != null) steps.subList(size, steps.size()).clear();
  }

  /** Ignore competition between different inputs: only proven shortfalls can be pruned.
   * Identical input costs share a bound. Memoization keeps wide graphs polynomial in depth. */
  private long upper(List<UltsCraftRecipe> options, int depth, Set<String> visiting) {
    return upper(options, depth, new HashMap<>());
  }

  private record BoundKey(List<Item> slot, int depth) {}

  private List<Item> slotKey(Ingredient slot) {
    return slotKeys.computeIfAbsent(slot, ingredient -> UltsIngredients.accepted(ingredient).stream()
        .sorted(Comparator.comparingInt(UltsIngredients::itemId)).toList());
  }

  private record Cost(int output, Map<List<Item>, Ingredient> slots) {}

  private long upper(List<UltsCraftRecipe> options, int depth, Map<BoundKey, Long> known) {
    if (!visit()) return Long.MAX_VALUE;
    Map<Map<List<Item>, Integer>, Cost> costs = new HashMap<>();
    for (UltsCraftRecipe route : options) {
      Map<List<Item>, Integer> counts = new HashMap<>();
      Map<List<Item>, Ingredient> slots = new HashMap<>();
      for (Ingredient slot : route.ingredients()) {
        List<Item> key = slotKey(slot);
        counts.merge(key, 1, Integer::sum);
        slots.put(key, slot);
      }
      Cost existing = costs.get(counts);
      if (existing == null || existing.output() < route.outputCount()) {
        costs.put(Map.copyOf(counts), new Cost(route.outputCount(), slots));
      }
    }
    long total = 0L;
    for (var cost : costs.entrySet()) {
      long operations = Long.MAX_VALUE;
      for (var entry : cost.getKey().entrySet()) {
        long available = upper(cost.getValue().slots().get(entry.getKey()), depth, known);
        operations = Math.min(operations,
            available == Long.MAX_VALUE ? available : available / entry.getValue());
      }
      total = UltsCraftMath.add(total, UltsCraftMath.multiply(operations, cost.getValue().output()));
    }
    return total;
  }

  private long upper(Ingredient slot, int depth, Map<BoundKey, Long> known) {
    BoundKey key = new BoundKey(slotKey(slot), depth);
    Long remembered = known.get(key);
    if (remembered != null) return remembered;
    if (possibleReturns == null) {
      possibleReturns = new HashSet<>();
      Map<Item, Set<Item>> consumers = new HashMap<>();
      for (UltsCraftRecipe route : source.everything()) {
        for (Ingredient ingredient : route.ingredients()) {
          for (Item item : UltsIngredients.accepted(ingredient)) {
            consumers.computeIfAbsent(item, ignored -> new HashSet<>()).add(route.result().getItem());
            var returned = item.getCraftingRemainder();
            if (!route.needsStonecutter() && returned != null) possibleReturns.add(returned.create().getItem());
          }
        }
      }
      returnDependent = new HashSet<>(possibleReturns);
      var pending = new java.util.ArrayDeque<>(possibleReturns);
      while (!pending.isEmpty()) {
        for (Item output : consumers.getOrDefault(pending.remove(), Set.of())) {
          if (returnDependent.add(output)) pending.add(output);
        }
      }
    }
    for (Item returned : possibleReturns) {
      if (UltsIngredients.accepts(slot, returned)) return Long.MAX_VALUE;
    }
    long held = pool.matches(slot), value = held;
    if (depth < MAX_DEPTH) {
      List<UltsCraftRecipe> options = new ArrayList<>();
      // Cycles are relaxed here, not pruned: removing them could understate this upper bound.
      for (ItemStack candidate : candidates(slot)) {
        for (UltsCraftRecipe route : routes(candidate, false)) {
          if (!options.contains(route)) options.add(route);
        }
      }
      value = UltsCraftMath.add(held, upper(options, depth + 1, known));
    }
    known.put(key, value);
    return value;
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
      // Prefer shortages first. Held slots still backtrack if their accepted materials overlap.
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
        if (reachTruncated || reachable.contains(key(candidate))) {
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
