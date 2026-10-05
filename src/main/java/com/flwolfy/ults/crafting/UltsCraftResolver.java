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
 *       what {@link #reach(long)} works out. Returns are included in that reachability pass.
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
  /** Proven feasibility probes survive a display tick's deadline; unfinished probes claim nothing. */
  private final Map<String, CapacitySearch> capacitySearches = new HashMap<>();
  private static final class CapacitySearch {
    long low;
    long high = 1L;
    boolean expanding = true;
    boolean checkCeiling;
    UltsCraftPotential potential;
    int quantum;
    boolean balanced;
    boolean strengthen;
    List<Set<Item>> focuses = List.of();
    int focusIndex;
    int cutIndex;
    Map<Item, Long> supplies = new HashMap<>();
    boolean familyBounded;
    Set<Item> family;
    int familyQuantum;
    boolean familyBalanced;
    int familyFocusIndex;
    ProbeReplay replay;
    List<UltsCraftRecipe> recipes;
  }

  /** Completed failed subtrees can be replayed as proofs on the next slice of the same immutable
   * probe. Traversal order and the active potential are frozen; unfinished subtrees claim nothing. */
  private static final class ProbeReplay {
    final long need;
    final UltsCraftPotential potential;
    final long revision;
    final java.util.TreeMap<Long, Long> failures = new java.util.TreeMap<>();
    long position;
    boolean recording;
    ProbeReplay(long need, UltsCraftPotential potential, long revision) {
      this.need = need; this.potential = potential; this.revision = revision;
    }
  }
  private ProbeReplay replay;
  private long proofRevision;

  private int budget;
  private long deadline = Long.MAX_VALUE;
  /** Whether the last search stopped on its budget or its clock rather than on the pile. */
  private boolean ranOut;
  /** Also bound Java call-stack depth when many small runs must be combined. */
  private int frames;
  private Set<Item> possibleReturns;
  private Set<Item> returnDependent;
  private final Map<Ingredient, List<Item>> slotKeys = new HashMap<>();
  private final Map<Ingredient, Set<Item>> slotInputs = new HashMap<>();
  private @Nullable UltsCraftPotential potential;
  private final Map<Ingredient, UltsCraftPotential> inputPotentials = new HashMap<>();
  private record OutputDependencyKey(List<UltsCraftRecipe> routes, Set<String> goals) {}
  private final Map<OutputDependencyKey, Boolean> independentOutputs = new HashMap<>();
  private List<UltsCraftPotential.Demand> unfinished = List.of();
  private List<UltsCraftPotential.OutputCredit> anticipated = List.of();

  /**
   * Whether the reachability pass stopped at its budget, which makes everything this view says about what
   * could be crafted a lower bound rather than the whole truth.
   */
  private boolean reachTruncated;

  private static List<UltsCraftRecipe> dependencyRecipes;
  private static UltsCraftDependencies dependencies;

  /** Includes all alternative ingredients, transitive recipes, returns and required stations. */
  public static Set<Item> capacityInputs(ItemStack template) {
    List<UltsCraftRecipe> recipes = source.everything();
    if (recipes != dependencyRecipes) {
      dependencies = new UltsCraftDependencies(recipes);
      dependencyRecipes = recipes;
    }
    return dependencies.inputs(template.getItem());
  }

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
    return of(pool, mode, requireStation, Long.MAX_VALUE);
  }

  public static UltsCraftResolver of(UltsCraftPool pool, UltsCraftingMode mode,
      boolean requireStation, long deadline) {
    UltsCraftResolver resolver = new UltsCraftResolver(pool, mode, requireStation);
    resolver.reach(deadline);
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
    capacitySearches.remove(wanted);
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
  private void reach(long limit) {
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
      if ((index & 63) == 0 && System.nanoTime() >= limit) {
        reachTruncated = true;
        return;
      }
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
    // This is a filter, not a proof of infeasibility. A truncated pass leaves the exact search available.
    int spent = 0;
    boolean grown = true;
    for (int round = 0; round <= MAX_DEPTH && grown; round++) {
      grown = false;
      Set<String> known = Set.copyOf(reachable);
      for (int index = 0; index < count; index++) {
        if (++spent > MAX_REACH_NODES || ((spent & 63) == 0 && System.nanoTime() >= limit)) {
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
    String wanted = key(template);
    CapacitySearch search = capacitySearches.get(wanted);
    if (search == null) {
      search = new CapacitySearch();
      search.recipes = source.everything().stream().filter(this::station).toList();
      search.potential = UltsCraftPotential.of(template, search.recipes, deadline);
      // A late frame must not permanently disable the useful bound for this item. Retry its
      // construction with the next frame's budget instead of caching a timed-out null result.
      if (search.potential == null && System.nanoTime() >= deadline) {
        ranOut = true;
        return UNKNOWN;
      }
      capacitySearches.put(wanted, search);
      if (search.potential != null) {
        // The target's stored pieces are reserved: only additional output contributes to the bound.
        UltsCraftPool.Keep saved = pool.keepState();
        pool.reserve(template, pool.amount(template));
        long bound;
        try { bound = Math.min(search.potential.bound(pool), upper(routes(template, true), 1, path(template))); }
        finally { pool.restoreKeep(saved); }
        int quantum = 0;
        for (var route : routes(template, true)) quantum = gcd(quantum, route.outputCount());
        search.quantum = quantum;
        // Saturating output arithmetic may legitimately reach MAX_VALUE between recipe multiples.
        search.high = quantum > 0 && bound < Long.MAX_VALUE ? bound - bound % quantum : bound;
        search.expanding = false;
        search.checkCeiling = search.high > 0L;
        var required = capacityInputs(template);
        var focuses = new java.util.LinkedHashSet<Set<Item>>();
        for (var route : search.recipes) if (required.contains(route.result().getItem()))
          for (var ingredient : route.ingredients()) focuses.add(Set.copyOf(UltsIngredients.accepted(ingredient)));
        search.focuses = List.copyOf(focuses);
      }
    }
    potential = search.potential;
    UltsCraftPool.Keep previous = pool.keepState();
    pool.reserve(template, pool.amount(template));
    try {
      // Apply integer family bounds before testing the first fractional ceiling. A coarse batch
      // conversion can make that ceiling impossible and its mixed-route proof extremely expensive.
      // Timeouts retain the initialized search and retry only this unfinished bound next frame.
      if (search.potential != null && !search.familyBounded) {
        if (search.family == null) {
          var family = cardinalityFamily(template.getItem(), search.recipes);
          if (family == null) { ranOut = true; return UNKNOWN; }
          search.family = Set.copyOf(family);
          search.familyQuantum = familyQuantum(family, search.recipes);
        }
        if (search.family.size() > 1 && search.familyQuantum > 1) {
          var targets = search.family.stream().map(Item::getDefaultInstance).toList();
          if (!search.familyBalanced) {
            var proof = UltsCraftPotential.balanced(targets, search.recipes, pool, deadline);
            if (proof == null && System.nanoTime() >= deadline) { ranOut = true; return UNKNOWN; }
            tightenFamily(search, proof);
            search.familyBalanced = true;
          }
          // A pigment's abundant stock cannot compensate for missing glass. Check focused family
          // proofs as well as the balanced proof, retaining each completed stage across deadlines.
          while (search.familyFocusIndex < search.focuses.size()) {
            var proof = UltsCraftPotential.focused(targets, search.recipes, search.focuses.get(search.familyFocusIndex), deadline);
            if (proof == null && System.nanoTime() >= deadline) { ranOut = true; return UNKNOWN; }
            tightenFamily(search, proof);
            search.familyFocusIndex++;
          }
        }
        search.familyBounded = true;
      }
      if (search.potential != null && search.strengthen) {
        if (!search.balanced) {
          var proof = UltsCraftPotential.balanced(template, search.recipes, pool, deadline);
          if (proof == null && System.nanoTime() >= deadline) { ranOut = true; return UNKNOWN; }
          search.balanced = true;
          tighten(search, proof);
        }
        while (search.focusIndex < search.focuses.size()) {
          var proof = UltsCraftPotential.focused(template, search.recipes, search.focuses.get(search.focusIndex), deadline);
          if (proof == null && System.nanoTime() >= deadline) { ranOut = true; return UNKNOWN; }
          search.focusIndex++;
          tighten(search, proof);
          var balancedProof = UltsCraftPotential.balanced(template, search.recipes, pool, deadline, search.focuses.get(search.focusIndex - 1));
          if (balancedProof == null && System.nanoTime() >= deadline) { search.focusIndex--; ranOut = true; return UNKNOWN; }
          tighten(search, balancedProof);
        }
        while (search.cutIndex < search.focuses.size()) {
          var sources = search.focuses.get(search.cutIndex);
          if (sources.stream().noneMatch(item -> capacityInputs(item.getDefaultInstance()).contains(template.getItem()))) {
            var proof = UltsCraftPotential.cut(template, search.recipes, sources, possibleReturns == null ? Set.of() : possibleReturns, deadline);
            if (proof == null && System.nanoTime() >= deadline) { ranOut = true; return UNKNOWN; }
            if (proof != null) {
              long supply = 0;
              for (Item source : sources) {
                Long count = search.supplies.get(source);
                if (count == null) {
                  count = sourceSupply(source, search.recipes);
                  if (ranOut) return UNKNOWN;
                  search.supplies.put(source, count);
                }
                supply = UltsCraftMath.add(supply, count);
              }
              long bound = proof.boundWithSources(pool, sources, supply);
              if (search.quantum > 0 && bound < Long.MAX_VALUE) bound -= bound % search.quantum;
              if (bound < search.high) { search.high = bound; search.checkCeiling = true; }
            }
          }
          search.cutIndex++;
        }
        potential = search.potential;
      }
      if (search.checkCeiling) {
        boolean feasible = capacityProbe(template, search.high, search);
        if (ranOut) { search.strengthen = true; return UNKNOWN; }
        search.checkCeiling = false;
        if (feasible) return search.high;
      }
      // Expansion and mixed routes are tested by the same feasibility search as planning.
      while (search.expanding) {
        boolean feasible = capacityProbe(template, search.high, search);
        if (ranOut) { search.strengthen = true; return UNKNOWN; }
        if (!feasible) { search.expanding = false; break; }
        search.low = search.high;
        if (search.high == Long.MAX_VALUE) return search.high;
        search.high = UltsCraftMath.multiply(search.high, 2L);
      }
      while (search.low < search.high - 1L) {
        long middle = search.low + (search.high - search.low) / 2L;
        boolean feasible = capacityProbe(template, middle, search);
        if (ranOut) { search.strengthen = true; return UNKNOWN; }
        if (feasible) search.low = middle;
        else search.high = middle;
      }
      return search.low;
    } finally { pool.restoreKeep(previous); }
  }

  private static int gcd(int first, int second) {
    while (second != 0) { int rest = first % second; first = second; second = rest; }
    return first;
  }

  /** Follow quantity-preserving conversions, such as eight plain panes into eight coloured panes.
   * The total family stock can only change by the gcd of every recipe's net contribution. */
  private @Nullable Set<Item> cardinalityFamily(Item target, List<UltsCraftRecipe> recipes) {
    var family = new HashSet<Item>(); family.add(target);
    int work = 0;
    boolean changed;
    do {
      changed = false;
      for (var route : recipes) {
        if (++work > MAX_REACH_NODES || ((work & 255) == 0 && System.nanoTime() >= deadline)) return null;
        if (family.contains(route.result().getItem())) {
          var repeated = new HashMap<List<Item>, Integer>();
          for (Ingredient slot : route.ingredients()) repeated.merge(slotKey(slot), 1, Integer::sum);
          for (var entry : repeated.entrySet()) if (entry.getValue() == route.outputCount()) changed |= family.addAll(entry.getKey());
        } else {
          int consumed = 0;
          for (Ingredient slot : route.ingredients()) {
            var accepted = slotKey(slot);
            if (!accepted.isEmpty() && family.containsAll(accepted)) consumed++;
          }
          if (consumed == route.outputCount()) changed |= family.add(route.result().getItem());
        }
      }
    } while (changed);
    return family;
  }

  private int familyQuantum(Set<Item> family, List<UltsCraftRecipe> recipes) {
    if (possibleReturns != null && possibleReturns.stream().anyMatch(family::contains)) return 1;
    int quantum = 0;
    for (var route : recipes) {
      int net = family.contains(route.result().getItem()) ? route.outputCount() : 0;
      for (Ingredient slot : route.ingredients()) {
        var accepted = slotKey(slot);
        boolean any = accepted.stream().anyMatch(family::contains);
        if (any && !family.containsAll(accepted)) return 1;
        if (any) net--;
      }
      quantum = gcd(quantum, Math.abs(net));
      if (quantum == 1) break;
    }
    return quantum;
  }

  private boolean capacityProbe(ItemStack template, long need, CapacitySearch search) {
    if (search.replay == null || search.replay.need != need || search.replay.potential != potential
        || search.replay.revision != proofRevision)
      search.replay = new ProbeReplay(need, potential, proofRevision);
    var active = search.replay; active.position = 0;
    replay = active;
    try {
      boolean feasible = canProduce(template, need);
      if (ranOut) active.recording = true;
      return feasible;
    } finally { replay = null; }
  }

  private long sourceSupply(Item source, List<UltsCraftRecipe> recipes) {
    var ingredient = Ingredient.of(source);
    long total = upper(ingredient, 1, new HashMap<>());
    if (ranOut) return Long.MAX_VALUE;
    var incoming = pool.keepState();
    long held = pool.matches(ingredient);
    for (var row : pool.matchingViews(ingredient)) pool.reserve(row.template(), pool.amount(row.template()));
    try {
      var proof = UltsCraftPotential.balanced(source.getDefaultInstance(), recipes, pool, deadline);
      if (proof == null && System.nanoTime() >= deadline) { ranOut = true; return Long.MAX_VALUE; }
      if (proof != null) {
        int quantum = 0;
        for (var route : recipes) if (route.result().is(source) && (reachTruncated || runnable(route, reachable))) quantum = gcd(quantum, route.outputCount());
        long extra = quantum == 0 ? 0 : proof.bound(pool);
        if (quantum > 0 && extra < Long.MAX_VALUE) extra -= extra % quantum;
        total = Math.min(total, UltsCraftMath.add(held, extra));
      }
      return total;
    } finally { pool.restoreKeep(incoming); }
  }

  private void tighten(CapacitySearch search, @Nullable UltsCraftPotential proof) {
    if (proof == null) return;
    long bound = proof.bound(pool);
    if (search.quantum > 0 && bound < Long.MAX_VALUE) bound -= bound % search.quantum;
    // A tighter resource bound is inclusive. It replaces a previously disproven high endpoint,
    // so it must be tested before binary search can treat it as an infeasible endpoint again.
    if (bound < search.high) { search.high = bound; search.potential = proof; search.checkCeiling = true; }
  }

  private void tightenFamily(CapacitySearch search, @Nullable UltsCraftPotential proof) {
    if (proof == null) return;
    long bound = proof.bound(pool, List.copyOf(search.family));
    long held = pool.matches(Ingredient.of(search.family.stream()));
    if (bound < Long.MAX_VALUE && bound >= held) bound -= (bound - held) % search.familyQuantum;
    if (bound < search.high) { search.high = bound; search.checkCeiling = true; }
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
    if (replay != null) replay.position++;
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
      // Try direct alternatives before enumerating a mixture. A coarse crafting batch may leave
      // a remainder that a stonecutter handles immediately. Each preference gets a small share of
      // the existing node budget; exhausting it is not a negative feasibility result.
      if ((replay == null || !replay.recording) && options.size() > 1 && goal.stream().allMatch(stack -> ItemStack.isSameItemSameComponents(stack, goal.getFirst()))) {
        for (var option : options) {
          int remaining = budget;
          int allowance = Math.min(256, Math.max(0, remaining / (options.size() + 1)));
          if (allowance == 0) break;
          budget = allowance;
          var routePath = new HashSet<>(visiting); routePath.add(key(option.result()));
          long operations = UltsCraftMath.divideRoundingUp(need, option.outputCount());
          boolean completed = batch(option, operations, List.of(), goal, need, steps, depth, routePath, visiting, next);
          int spent = allowance - budget;
          budget = Math.max(0, remaining - spent);
          if (completed) return true;
          if (System.nanoTime() >= deadline || budget <= 0) { ranOut = true; return false; }
          ranOut = false;
        }
      }
      for (int routeIndex = 0; routeIndex < options.size(); routeIndex++) {
        UltsCraftRecipe route = options.get(routeIndex);
        Set<String> routePath = new HashSet<>(visiting);
        routePath.add(key(route.result()));
        long wanted = UltsCraftMath.divideRoundingUp(need, route.outputCount());
        boolean reusable = options.stream().anyMatch(option -> returnDependent != null
            && returnDependent.contains(option.result().getItem()));
        // With no reusable returns, each route's quantity need only be chosen once.
        List<UltsCraftRecipe> later = reusable ? options : options.subList(routeIndex + 1, options.size());
        if (!reusable && !later.isEmpty() && hasSeparateHeld(route, later) && independentOutputs(options, goal)
            && prefix(route, wanted, later, goal, need, steps, depth, routePath, visiting, next)) return true;
        if (ranOut) return false;
        long bound = upper(List.of(route), depth, routePath);
        if (bound >= UltsCraftMath.multiply(wanted, route.outputCount())
            && batch(route, wanted, later, goal, need, steps, depth, routePath, visiting, next)) return true;
        if (ranOut) return false;
        // The last route cannot cover its outstanding result with fewer runs. With no useful
        // remainders and one exact output kind, exploring millions of smaller batches proves the
        // same shortfall repeatedly. Ingredient choices for the full batch were already explored.
        if (!reusable && later.isEmpty() && goal.stream().allMatch(stack ->
            ItemStack.isSameItemSameComponents(stack, route.result()))) continue;
        long maximum = maxBatch(route, wanted, depth, routePath);
        if (ranOut) return false;
        if (maximum == wanted) maximum--;
        long minimum = 1L;
        if (!reusable && !later.isEmpty() && independentOutputs(options, goal)) {
          long remainderBound = upper(later, depth, visiting);
          if (ranOut) return false;
          minimum = Math.max(minimum, UltsCraftMath.divideRoundingUp(Math.max(0L, need - remainderBound), route.outputCount()));
          var related = new java.util.LinkedHashSet<Ingredient>(route.ingredients());
          unfinished.forEach(demand -> related.add(demand.ingredient()));
          var bounds = new ArrayList<UltsCraftPotential>();
          related.forEach(ingredient -> bounds.add(inputPotentials.get(ingredient)));
          if (potential != null) bounds.add(potential);
          for (var boundProof : bounds) if (boundProof != null && minimum <= maximum) {
            var range = boundProof.batches(pool, unfinished, anticipated, route, later, need, minimum, maximum);
            minimum = Math.max(minimum, range[0]); maximum = Math.min(maximum, range[1]);
          }
        }
        for (long operations = maximum; operations >= minimum; operations--) {
          if (!visit()) return false;
          if (batch(route, operations, later, goal, need, steps, depth, routePath, visiting, next)) return true;
          if (ranOut) return false;
        }
      }
      return false;
    } finally { frames--; }
  }

  private boolean independentOutputs(List<UltsCraftRecipe> options, List<ItemStack> goal) {
    if (goal.stream().allMatch(stack -> ItemStack.isSameItemSameComponents(stack, goal.getFirst()))) return true;
    var dependencyKey = new OutputDependencyKey(List.copyOf(options), goal.stream().map(UltsCraftResolver::key).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    return independentOutputs.computeIfAbsent(dependencyKey, ignored -> {
      var outputs = new HashMap<Item, String>();
      for (ItemStack target : goal) {
        String before = outputs.putIfAbsent(target.getItem(), key(target));
        if (before != null && !before.equals(key(target))) return false;
      }
      // A child's extra output of a different goal kind could pay part of the request. Only use
      // the route interval when no recipe input can manufacture any of those other goal kinds.
      for (var route : options) for (Ingredient ingredient : route.ingredients())
        for (Item material : UltsIngredients.accepted(ingredient))
          for (Item input : capacityInputs(material.getDefaultInstance()))
            if (outputs.containsKey(input) && input != route.result().getItem()) return false;
      return true;
    });
  }

  /** Try independent material families before mixing them. The reservation is only a search
   * preference; failure returns to the unrestricted exact search. */
  private boolean hasSeparateHeld(UltsCraftRecipe route, List<UltsCraftRecipe> later) {
    var current = route.ingredients().stream().map(this::slotKey).toList();
    for (var option : later) for (Ingredient ingredient : option.ingredients())
      if (!current.contains(slotKey(ingredient)) && pool.matches(ingredient) > 0) return true;
    return false;
  }

  private boolean prefix(UltsCraftRecipe route, long wanted, List<UltsCraftRecipe> later,
      List<ItemStack> goal, long need, @Nullable List<UltsCraftStep> steps, int depth,
      Set<String> routePath, Set<String> visiting, Continuation next) {
    var original = pool.keepState();
    boolean held = false;
    for (var option : later) for (var ingredient : option.ingredients())
      for (var row : pool.matchingViews(ingredient)) {
        pool.reserve(row.template(), pool.amount(row.template())); held = true;
      }
    if (!held) return false;
    try {
      long high = wanted;
      if (potential != null) {
        long bound = potential.bound(pool, List.of(route.result().getItem()));
        if (bound != Long.MAX_VALUE) high = Math.min(high, bound / route.outputCount());
      }
      if (high <= 0) return false;
      long maximum = maxBatch(route, high, depth, routePath, need, true);
      if (ranOut || maximum <= 0) return false;
      var protectedStock = pool.keepState();
      pool.restoreKeep(original);
      long remainderBound;
      try { remainderBound = upper(later, depth, visiting); }
      finally { pool.restoreKeep(protectedStock); }
      if (ranOut || Math.max(0L, need - UltsCraftMath.multiply(maximum, route.outputCount())) > remainderBound) return false;
      return batch(route, maximum, later, goal, need, steps, depth, routePath, visiting, original, next);
    } finally { pool.restoreKeep(original); }
  }

  private boolean batch(UltsCraftRecipe route, long operations, List<UltsCraftRecipe> later,
      List<ItemStack> goal, long need, @Nullable List<UltsCraftStep> steps, int depth,
      Set<String> routePath, Set<String> visiting, Continuation next) {
    return batch(route, operations, later, goal, need, steps, depth, routePath, visiting, null, next);
  }

  private boolean batch(UltsCraftRecipe route, long operations, List<UltsCraftRecipe> later,
      List<ItemStack> goal, long need, @Nullable List<UltsCraftStep> steps, int depth,
      Set<String> routePath, Set<String> visiting, @Nullable UltsCraftPool.Keep release, Continuation next) {
    long before = outputStock(goal);
    var quantities = new HashMap<String, Long>();
    for (ItemStack target : goal) quantities.put(com.flwolfy.ults.data.state.UltsStackKinds.of(target), pool.amount(target));
    long surplus = Math.max(0L, UltsCraftMath.multiply(operations, route.outputCount()) - need);
    var beforeDemands = unfinished;
    long outstanding = Math.max(0L, need - UltsCraftMath.multiply(operations, route.outputCount()));
    var options = new ArrayList<>(later); options.add(route);
    if (release == null && outstanding > 0 && independentOutputs(options, goal)) {
      var demands = new ArrayList<>(unfinished);
      demands.add(new UltsCraftPotential.Demand(Ingredient.of(goal.stream().map(ItemStack::getItem).distinct()), outstanding));
      unfinished = demands;
    }
    try { return gather(route, operations, steps, depth, routePath, surplus, () -> {
      var activeDemands = unfinished; unfinished = beforeDemands;
      UltsCraftPool.Keep previous = pool.keepState();
      if (release != null) pool.restoreKeep(release);
      long gain = 0L;
      var counted = new HashSet<String>();
      for (ItemStack target : goal) {
        String key = com.flwolfy.ults.data.state.UltsStackKinds.of(target);
        if (!counted.add(key)) continue;
        long growth = Math.max(0L, pool.amount(target) - quantities.get(key));
        gain = UltsCraftMath.add(gain, growth);
        // Protect newly promised units across later routes, while the original compatible
        // materials remain available for conversions (for example paper into sticks).
        pool.reserve(target, UltsCraftMath.add(pool.amount(target) - pool.usableAmount(target), growth));
      }
      if (depth == 1) gain = outputStock(goal) - before;
      long remaining = gain >= 0L ? Math.max(0L, need - gain) : UltsCraftMath.add(need, -gain);
      try { return produce(later, goal, remaining, steps, depth, visiting, next); }
      finally { pool.restoreKeep(previous); unfinished = activeDemands; }
    }); } finally { unfinished = beforeDemands; }
  }

  private long maxBatch(UltsCraftRecipe route, long high, int depth, Set<String> visiting) {
    return maxBatch(route, high, depth, visiting, Long.MAX_VALUE, false);
  }

  private long maxBatch(UltsCraftRecipe route, long high, int depth, Set<String> visiting, long need, boolean future) {
    if (probeBatch(route, high, depth, visiting, need, future)) return high;
    long low = 0L;
    while (!ranOut && low < high - 1L) {
      long middle = low + (high - low) / 2L;
      if (probeBatch(route, middle, depth, visiting, need, future)) low = middle;
      else high = middle;
    }
    return low;
  }

  private boolean probeBatch(UltsCraftRecipe route, long operations, int depth, Set<String> visiting, long need, boolean future) {
    if (upper(List.of(route), depth, visiting) < UltsCraftMath.multiply(operations, route.outputCount())) {
      return false;
    }
    int mark = pool.mark();
    var before = unfinished;
    var beforeCredits = anticipated;
    if (!future) { unfinished = List.of(); anticipated = List.of(); }
    boolean result;
    try { result = gather(route, operations, null, depth, visiting,
        Math.max(0L, UltsCraftMath.multiply(operations, route.outputCount()) - need), () -> true); }
    finally { unfinished = before; anticipated = beforeCredits; }
    pool.rollback(mark);
    return result;
  }

  private boolean gather(UltsCraftRecipe route, long operations,
      @Nullable List<UltsCraftStep> steps, int depth, Set<String> visiting, long surplus, Continuation next) {
    var active = replay;
    long start = active == null ? 0 : active.position;
    if (active != null && active.recording) {
      Long end = active.failures.get(start);
      if (end != null) { active.position = end; return false; }
    }
    boolean result = gatherUncached(route, operations, steps, depth, visiting, surplus, next);
    if (!result && !ranOut && active != null && active.recording && active.position > start) {
      // A parent proof subsumes its completed child proofs, keeping the frontier compact.
      active.failures.subMap(start, false, active.position, false).clear();
      if (active.failures.size() < 8192 || active.failures.containsKey(start)) active.failures.put(start, active.position);
    }
    return result;
  }

  private boolean gatherUncached(UltsCraftRecipe route, long operations,
      @Nullable List<UltsCraftStep> steps, int depth, Set<String> visiting, long surplus, Continuation next) {
    if (!visit()) return false;
    var demands = new ArrayList<>(unfinished);
    for (Ingredient ingredient : route.ingredients()) demands.add(new UltsCraftPotential.Demand(ingredient, operations));
    var credits = new ArrayList<>(anticipated);
    if (surplus > 0) credits.add(new UltsCraftPotential.OutputCredit(route.result(), surplus));
    var residues = demandResidues(demands, credits);
    if (ranOut) return false;
    if (potential != null && !potential.sufficientInputs(pool, demands, credits, residues)) return false;
    for (Ingredient ingredient : new java.util.LinkedHashSet<>(route.ingredients())) {
      if (!inputPotentials.containsKey(ingredient)) {
        var inputs = UltsIngredients.accepted(ingredient).stream().map(Item::getDefaultInstance).toList();
        var recipes = source.everything().stream().filter(this::station).toList();
        var bound = UltsCraftPotential.balanced(inputs, recipes, pool, deadline);
        if (bound == null && System.nanoTime() < deadline) bound = UltsCraftPotential.of(inputs, recipes, deadline);
        if (bound == null && System.nanoTime() >= deadline) { ranOut = true; return false; }
        inputPotentials.put(ingredient, bound);
        if (bound != null) {
          proofRevision++;
          // New pruning inequalities can change ordinal traversal. Never reuse a subtree proof
          // from the previous traversal, including proofs recorded earlier in this same slice.
          if (replay != null) { replay.failures.clear(); replay.recording = false; }
        }
      }
      var bound = inputPotentials.get(ingredient);
      if (bound != null && !bound.sufficientInputs(pool, demands, credits, residues)) return false;
    }
    int mark = pool.mark(), stepMark = steps == null ? 0 : steps.size();
    var beforeCredits = anticipated;
    anticipated = credits;
    boolean result;
    try {
      result = slots(route, grouped(route.ingredients(), operations), 0, operations,
          new ArrayList<>(), steps, depth, visiting, () -> {
            var active = anticipated;
            anticipated = beforeCredits;
            try { return next.run(); }
            finally { anticipated = active; }
          });
    } finally { anticipated = beforeCredits; }
    if (!result) { pool.rollback(mark); truncate(steps, stepMark); }
    return result;
  }

  private record DemandQuantumKey(Set<Item> family, Set<Item> goals) {}
  private final Map<DemandQuantumKey, Integer> demandQuanta = new HashMap<>();
  private Map<Item, List<UltsCraftRecipe>> demandRecipes;

  /** All manufacturing steps preserve each family's cardinality modulo their net batch gcd.
   * Explicit ingredient consumption is accounted separately. Any forced leftover also consumes
   * resource potential, even when a fractional bound would allow the very last output unit. */
  private List<UltsCraftPotential.Residue> demandResidues(List<UltsCraftPotential.Demand> demands,
      List<UltsCraftPotential.OutputCredit> credits) {
    var goals = new HashSet<Item>();
    var families = new java.util.LinkedHashSet<Set<Item>>();
    for (var demand : demands) {
      var accepted = Set.copyOf(slotKey(demand.ingredient()));
      goals.addAll(accepted); families.add(accepted);
    }
    var result = new ArrayList<UltsCraftPotential.Residue>();
    for (var family : families) {
      if (family.isEmpty()) continue;
      boolean mixed = demands.stream().anyMatch(demand -> {
        var accepted = slotKey(demand.ingredient());
        return accepted.stream().anyMatch(family::contains) && !family.containsAll(accepted);
      });
      if (mixed) continue;
      var key = new DemandQuantumKey(family, Set.copyOf(goals));
      Integer quantum = demandQuanta.get(key);
      if (quantum == null) {
        if (demandRecipes == null) {
          var incoming = new HashMap<Item, List<UltsCraftRecipe>>();
          int scanned = 0;
          for (var recipe : source.everything()) {
            if ((++scanned & 127) == 0 && System.nanoTime() >= deadline) { ranOut = true; return List.of(); }
            if (station(recipe)) incoming.computeIfAbsent(recipe.result().getItem(), ignored -> new ArrayList<>()).add(recipe);
          }
          demandRecipes = incoming;
        }
        var pending = new java.util.ArrayDeque<>(goals);
        var seen = new HashSet<>(goals);
        var recipes = new java.util.LinkedHashSet<UltsCraftRecipe>();
        int scanned = 0;
        while (!pending.isEmpty()) {
          if ((++scanned & 127) == 0 && System.nanoTime() >= deadline) { ranOut = true; return List.of(); }
          Item output = pending.remove();
          for (var recipe : demandRecipes.getOrDefault(output, List.of())) {
            recipes.add(recipe);
            for (Ingredient slot : recipe.ingredients()) for (Item input : slotKey(slot))
              if (seen.add(input)) pending.add(input);
          }
        }
        quantum = familyQuantum(family, List.copyOf(recipes));
        demandQuanta.put(key, quantum);
      }
      if (quantum <= 1) continue;
      var balance = java.math.BigInteger.ZERO;
      for (int index = 0; index < pool.size(); index++) if (family.contains(pool.templateAt(index).getItem()))
        balance = balance.add(java.math.BigInteger.valueOf(pool.usableAmountAt(index)));
      for (var credit : credits) if (family.contains(credit.template().getItem()))
        balance = balance.add(java.math.BigInteger.valueOf(credit.amount()));
      for (var demand : demands) if (family.containsAll(slotKey(demand.ingredient())))
        balance = balance.subtract(java.math.BigInteger.valueOf(demand.amount()));
      int residue = balance.mod(java.math.BigInteger.valueOf(quantum)).intValue();
      if (residue > 0) result.add(new UltsCraftPotential.Residue(family, residue));
    }
    return result;
  }

  private boolean slots(UltsCraftRecipe route, List<UltsCraftPotential.Demand> ingredients, int index,
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
    Ingredient slot = ingredients.get(index).ingredient();
    long needed = ingredients.get(index).amount();
    var before = unfinished;
    var future = new ArrayList<>(before);
    for (int remaining = index + 1; remaining < ingredients.size(); remaining++)
      future.add(ingredients.get(remaining));
    unfinished = future;
    try {
      return consume(slot, pool.matchingViews(slot), 0, needed, consumed, steps, depth, visiting, true, () -> {
        var active = unfinished;
        unfinished = before;
        try { return slots(route, ingredients, index + 1, operations, consumed, steps, depth, visiting, next); }
        finally { unfinished = active; }
      });
    } finally { unfinished = before; }
  }

  /** Reserve concrete materials; later failures can change a slot's allocation or recipe. */
  private boolean consume(Ingredient slot, List<UltsStoredView> held, int index, long need,
      List<UltsStoredView> consumed, @Nullable List<UltsCraftStep> steps,
      int depth, Set<String> visiting, boolean allowCraft, Continuation next) {
    if (need <= 0L) return next.run();
    if (!visit()) return false;
    var manufacture = allowCraft && depth < MAX_DEPTH ? manufacturingOptions(slot, visiting) : List.<UltsCraftRecipe>of();
    if (index == held.size()) {
      if (!allowCraft || depth >= MAX_DEPTH) return false;
      var before = pool.keepState();
      return produce(manufacture, need, steps, depth + 1, visiting, () -> {
        var promised = pool.keepState();
        pool.restoreKeep(before);
        try { return consume(slot, pool.matchingViews(slot), 0, need, consumed, steps, depth, visiting, false, next); }
        finally { pool.restoreKeep(promised); }
      });
    }
    UltsStoredView material = held.get(index);
    long maximum = Math.min(need, material.amount()), minimum = 0L;
    if (manufacture.isEmpty()) {
      long rest = 0L;
      for (int other = index + 1; other < held.size(); other++) {
        rest = UltsCraftMath.add(rest, held.get(other).amount());
      }
      minimum = Math.max(0L, need - rest);
    }
    if (!manufacture.isEmpty() && index == held.size() - 1 && minimum < maximum
        && possibleReturns != null && possibleReturns.stream().noneMatch(item -> UltsIngredients.accepts(slot, item))) {
      var manufactured = manufacture.stream().map(route -> route.result().getItem()).distinct().toList();
      var related = new java.util.LinkedHashSet<Ingredient>();
      related.add(slot);
      unfinished.forEach(demand -> related.add(demand.ingredient()));
      var bounds = new ArrayList<UltsCraftPotential>();
      related.forEach(ingredient -> bounds.add(inputPotentials.get(ingredient)));
      if (potential != null) bounds.add(potential);
      for (var bound : bounds) if (bound != null) {
        var range = bound.allocation(pool, unfinished, anticipated, material.template().getItem(),
            manufactured, need, minimum, maximum);
        minimum = Math.max(minimum, range[0]); maximum = Math.min(maximum, range[1]);
        if (minimum > maximum) return false;
      }
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

  private List<UltsCraftRecipe> manufacturingOptions(Ingredient slot, Set<String> visiting) {
    var options = new ArrayList<UltsCraftRecipe>();
    for (ItemStack candidate : candidates(slot)) if (!visiting.contains(key(candidate)))
      for (var route : routes(candidate, false)) if (!nonIncreasingConversion(route, slot) && !options.contains(route)) options.add(route);
    return options;
  }

  private static void truncate(@Nullable List<UltsCraftStep> steps, int size) {
    if (steps != null) steps.subList(size, steps.size()).clear();
  }

  /** An ingredient matches by item, so recolouring an already accepted item cannot fill a shortage.
   * Keep expanding recipes and recipes with remainders: either may create useful additional stock. */
  private boolean nonIncreasingConversion(UltsCraftRecipe route, Ingredient wanted) {
    int consumed = 0;
    for (Ingredient slot : route.ingredients()) {
      var accepted = UltsIngredients.accepted(slot);
      if (accepted.stream().anyMatch(item -> item.getCraftingRemainder() != null)) return false;
      if (!accepted.isEmpty() && accepted.stream().allMatch(item -> UltsIngredients.accepts(wanted, item))) consumed++;
    }
    return consumed >= route.outputCount();
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

  private record CostKey(Map<List<Item>, Integer> counts, int output) {}
  private record Cost(int factor, Map<List<Item>, Ingredient> slots) {}

  private long upper(List<UltsCraftRecipe> options, int depth, Map<BoundKey, Long> known) {
    if (!visit()) return Long.MAX_VALUE;
    Map<CostKey, Cost> costs = new HashMap<>();
    for (UltsCraftRecipe route : options) {
      Map<List<Item>, Integer> counts = new HashMap<>();
      Map<List<Item>, Ingredient> slots = new HashMap<>();
      for (Ingredient slot : route.ingredients()) {
        List<Item> key = slotKey(slot);
        counts.merge(key, 1, Integer::sum);
        slots.put(key, slot);
      }
      int common = route.outputCount();
      for (int count : counts.values()) common = gcd(common, count);
      final int divisor = common;
      if (divisor > 1) counts.replaceAll((key, count) -> count / divisor);
      int output = route.outputCount() / divisor;
      var key = new CostKey(Map.copyOf(counts), output);
      Cost existing = costs.get(key);
      // Equal conversion ratios share their resources. The gcd retains real batch granularity:
      // one 2+2 -> 2 recipe cannot use an odd last ingredient, while 3 -> 6 and 1 -> 2 can combine.
      costs.put(key, new Cost(existing == null ? divisor : gcd(existing.factor(), divisor), slots));
    }
    long total = 0L;
    for (var cost : costs.entrySet()) {
      long operations = Long.MAX_VALUE;
      for (var entry : cost.getKey().counts().entrySet()) {
        long available = upper(cost.getValue().slots().get(entry.getKey()), depth, known);
        operations = Math.min(operations,
            available == Long.MAX_VALUE ? available : available / (entry.getValue() * cost.getValue().factor()));
      }
      total = UltsCraftMath.add(total, UltsCraftMath.multiply(operations,
          (long) cost.getKey().output() * cost.getValue().factor()));
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
  private List<UltsCraftPotential.Demand> grouped(List<Ingredient> ingredients, long operations) {
    var counts = new java.util.LinkedHashMap<List<Item>, Integer>();
    var slots = new java.util.LinkedHashMap<List<Item>, Ingredient>();
    for (Ingredient ingredient : ingredients) {
      var key = slotKey(ingredient);
      counts.merge(key, 1, Integer::sum); slots.putIfAbsent(key, ingredient);
    }
    var grouped = new ArrayList<UltsCraftPotential.Demand>();
    for (var entry : counts.entrySet()) {
      var ingredient = slots.get(entry.getKey());
      // Never turn an overflowing material requirement into a feasible saturated request.
      if (operations > Long.MAX_VALUE / entry.getValue()) {
        for (int repeat = 0; repeat < entry.getValue(); repeat++) grouped.add(new UltsCraftPotential.Demand(ingredient, operations));
      } else grouped.add(new UltsCraftPotential.Demand(ingredient, operations * entry.getValue()));
    }
    grouped.sort(Comparator.comparingInt(demand -> rank(demand.ingredient(), demand.amount())));
    return grouped;
  }

  private int rank(Ingredient ingredient, long operations) {
    int sources = sources(ingredient);
    var candidates = candidates(ingredient);
    if (!candidates.isEmpty() && candidates.stream().map(ItemStack::getItem).distinct().count() == 1) {
      int alternative = 0;
      for (ItemStack candidate : candidates) for (var route : routes(candidate, false)) {
        if (nonIncreasingConversion(route, ingredient)) continue;
        int bottleneck = Integer.MAX_VALUE;
        for (Ingredient input : route.ingredients()) bottleneck = Math.min(bottleneck, sources(input));
        alternative = Math.max(alternative, bottleneck);
      }
      if (alternative > 0) sources = Math.min(sources, alternative);
    }
    return sources * 1024 + Math.min(1023, candidates.size());
  }

  private int sources(Ingredient ingredient) {
    var inputs = slotInputs.computeIfAbsent(ingredient, slot -> {
      var items = new HashSet<>(UltsIngredients.accepted(slot));
      for (ItemStack candidate : candidates(slot)) items.addAll(capacityInputs(candidate));
      return Set.copyOf(items);
    });
    // Count usable source kinds, not only immediate output kinds. Sticks are one output but can
    // use several wood families; bamboo planks need bamboo. Fill the inflexible slot first.
    int sources = 0;
    for (int index = 0; index < pool.size(); index++)
      if (pool.usableAmountAt(index) > 0 && inputs.contains(pool.templateAt(index).getItem())
          && !pool.templateAt(index).is(Items.CRAFTING_TABLE) && !pool.templateAt(index).is(Items.STONECUTTER)) sources++;
    return sources;
  }

  /** The routes of one item that may be used right now, best first. */
  private List<UltsCraftRecipe> routes(ItemStack template, boolean root) {
    if (root && mode == UltsCraftingMode.SHULKER_BOXES_ONLY && !template.is(Items.SHULKER_BOX)) {
      return List.of();
    }
    return routeCache.computeIfAbsent(key(template), wanted -> {
      List<UltsCraftRecipe> usable = new ArrayList<>();
      for (UltsCraftRecipe route : source.recipes(template)) {
        if (station(route) && (reachTruncated || runnable(route, reachable))) {
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
