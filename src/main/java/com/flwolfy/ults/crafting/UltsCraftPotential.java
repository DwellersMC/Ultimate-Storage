package com.flwolfy.ults.crafting;

import java.math.BigInteger;
import java.util.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/** A conservative, exact-rational resource bound. Every usable recipe must preserve or decrease
 * this potential. It detects shared raw resources even when they have several conversion routes. */
final class UltsCraftPotential {
  private record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {
    static final Fraction ONE = new Fraction(BigInteger.ONE, BigInteger.ONE);
    static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
    static final Fraction HIGH = new Fraction(BigInteger.ONE.shiftLeft(128), BigInteger.ONE);
    Fraction {
      BigInteger gcd = numerator.gcd(denominator);
      numerator = numerator.divide(gcd);
      denominator = denominator.divide(gcd);
    }
    Fraction add(Fraction other) {
      if (numerator.signum() == 0) return other;
      if (other.numerator.signum() == 0) return this;
      return new Fraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
          denominator.multiply(other.denominator));
    }
    Fraction divide(int count) {
      return count == 1 ? this : new Fraction(numerator, denominator.multiply(BigInteger.valueOf(count)));
    }
    Fraction subtract(Fraction other) { return add(other.multiply(-1)); }
    Fraction multiply(long count) { return new Fraction(numerator.multiply(BigInteger.valueOf(count)), denominator); }
    @Override public int compareTo(Fraction other) {
      if (this == other) return 0;
      return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }
  }

  private final Item target;
  private final Map<Item, Fraction> costs;
  private UltsCraftPotential(Item target, Map<Item, Fraction> costs) {
    this.target = target;
    this.costs = costs;
  }

  static @Nullable UltsCraftPotential of(ItemStack target, List<UltsCraftRecipe> recipes, long deadline) {
    return of(List.of(target), recipes, deadline);
  }

  static @Nullable UltsCraftPotential of(List<ItemStack> targets, List<UltsCraftRecipe> recipes, long deadline) {
    return of(targets, recipes, deadline, null);
  }

  static @Nullable UltsCraftPotential focused(ItemStack target, List<UltsCraftRecipe> recipes, Set<Item> focus, long deadline) {
    return of(List.of(target), recipes, deadline, focus);
  }

  static @Nullable UltsCraftPotential focused(List<ItemStack> targets, List<UltsCraftRecipe> recipes, Set<Item> focus, long deadline) {
    return of(targets, recipes, deadline, focus);
  }

  private static @Nullable UltsCraftPotential of(List<ItemStack> targets, List<UltsCraftRecipe> recipes, long deadline, Set<Item> focus) {
    return of(targets, recipes, deadline, focus, Set.of());
  }

  static @Nullable UltsCraftPotential cut(ItemStack target, List<UltsCraftRecipe> recipes, Set<Item> sources, Set<Item> returns, long deadline) {
    var proof = of(List.of(target), recipes, deadline, null, sources);
    if (proof == null) return null;
    // Manufacturing an abstract source may also supply a valued remainder outside the cut.
    // Abstain rather than omit that extra resource from the virtual stock.
    for (Item item : returns) if (proof.costs.getOrDefault(item, Fraction.ZERO).numerator().signum() > 0) return null;
    return proof;
  }

  private static @Nullable UltsCraftPotential of(List<ItemStack> targets, List<UltsCraftRecipe> recipes, long deadline, Set<Item> focus, Set<Item> sources) {
    if (targets.isEmpty()) return null;
    if (recipes.size() > 20_000 || System.nanoTime() >= deadline) return null;
    Map<Item, List<UltsCraftRecipe>> incoming = new HashMap<>();
    int scanned = 0;
    for (var route : recipes) {
      if ((++scanned & 127) == 0 && System.nanoTime() >= deadline) return null;
      if (sources.contains(route.result().getItem())) continue;
      incoming.computeIfAbsent(route.result().getItem(), key -> new ArrayList<>()).add(route);
    }
    Set<Item> ancestors = new HashSet<>();
    ArrayDeque<Item> pending = new ArrayDeque<>();
    for (ItemStack target : targets) {
      if (ancestors.add(target.getItem())) pending.add(target.getItem());
    }
    List<UltsCraftRecipe> relevant = new ArrayList<>();
    while (!pending.isEmpty()) {
      for (var route : incoming.getOrDefault(pending.remove(), List.of())) {
        relevant.add(route);
        for (var slot : route.ingredients()) {
          for (Item item : UltsIngredients.accepted(slot)) {
            if ((++scanned & 255) == 0 && System.nanoTime() >= deadline) return null;
            if (ancestors.add(item)) pending.add(item);
          }
        }
      }
    }
    // A useful return could carry potential too. In that case this optimization abstains;
    // the planner still explores and executes the recipe and its returns normally.
    for (var route : relevant) {
      if (route.needsStonecutter()) continue;
      for (var slot : route.ingredients()) {
        for (Item item : UltsIngredients.accepted(slot)) {
          if ((++scanned & 255) == 0 && System.nanoTime() >= deadline) return null;
          var returned = item.getCraftingRemainder();
          if (returned != null && ancestors.contains(returned.create().getItem())) return null;
        }
      }
    }
    Map<Item, Fraction> costs = new HashMap<>();
    Set<Item> positive = ancestors;
    if (focus != null) {
      positive = new HashSet<>(focus);
      var queue = new ArrayDeque<>(focus);
      while (!queue.isEmpty()) for (var route : incoming.getOrDefault(queue.remove(), List.of()))
        for (var slot : route.ingredients()) for (Item item : UltsIngredients.accepted(slot)) {
          if (++scanned > 400_000 || ((scanned & 255) == 0 && System.nanoTime() >= deadline)) return null;
          if (positive.add(item)) queue.add(item);
        }
      boolean grown;
      do {
        grown = false;
        for (var route : relevant) {
          if (++scanned > 400_000 || ((scanned & 127) == 0 && System.nanoTime() >= deadline)) return null;
          boolean depends = false;
          for (var slot : route.ingredients()) for (Item item : UltsIngredients.accepted(slot)) depends |= positive.contains(item);
          if (depends) grown |= positive.add(route.result().getItem());
        }
      } while (grown);
    }
    // Give producible items room to converge to their actual recipe cost. Starting every item at one
    // permanently underprices expensive outputs (a box costs both a chest and shells), hiding joint
    // shortfalls. Any nonnegative fixed point is a valid proof; ungrounded cycles may keep a high cost.
    for (Item item : ancestors) costs.put(item, !positive.contains(item) ? Fraction.ZERO
        : incoming.containsKey(item) ? Fraction.HIGH : Fraction.ONE);
    int work = 0;
    boolean changed;
    do {
      changed = false;
      for (var route : relevant) {
        if (++work > 20_000) return null;
        if ((work & 127) == 0 && System.nanoTime() >= deadline) return null;
        Fraction sum = Fraction.ZERO;
        for (var slot : route.ingredients()) {
          Fraction cheapest = null;
          for (Item item : UltsIngredients.accepted(slot)) {
            Fraction cost = costs.get(item);
            if (cheapest == null || (cost != cheapest && cost.compareTo(cheapest) < 0)) cheapest = cost;
          }
          if (cheapest == null) return null;
          sum = sum.add(cheapest);
        }
        Fraction candidate = sum.divide(route.outputCount());
        // A productive cycle may never reach a fixed point. Abstain before huge integers form.
        if (candidate.numerator().bitLength() > 256 || candidate.denominator().bitLength() > 256) return null;
        Item output = route.result().getItem();
        if (candidate.compareTo(costs.get(output)) < 0) {
          costs.put(output, candidate);
          changed = true;
        }
      }
    } while (changed);
    // At the fixed point, every recipe inequality has been checked using exact arithmetic.
    return new UltsCraftPotential(targets.getFirst().getItem(), Map.copyOf(costs));
  }

  /** Balance alternative materials around the requested output, then enforce every recipe inequality
   * again. Unstored intermediates are priced from their actual recipes, not an assumed stock source. */
  static @Nullable UltsCraftPotential balanced(ItemStack target, List<UltsCraftRecipe> recipes,
      UltsCraftPool pool, long deadline) {
    return balanced(target, recipes, pool, deadline, null);
  }

  static @Nullable UltsCraftPotential balanced(ItemStack target, List<UltsCraftRecipe> recipes,
      UltsCraftPool pool, long deadline, @Nullable Set<Item> focus) {
    return balanced(List.of(target), recipes, pool, deadline, focus);
  }

  static @Nullable UltsCraftPotential balanced(List<ItemStack> targets, List<UltsCraftRecipe> recipes,
      UltsCraftPool pool, long deadline) {
    return balanced(targets, recipes, pool, deadline, null);
  }

  private static @Nullable UltsCraftPotential balanced(List<ItemStack> targets, List<UltsCraftRecipe> recipes,
      UltsCraftPool pool, long deadline, @Nullable Set<Item> focus) {
    ItemStack target = targets.getFirst();
    var base = of(targets, recipes, deadline, focus);
    if (base == null) return null;
    if (base.costs.get(target.getItem()).numerator().signum() <= 0) return base;
    var items = base.costs.keySet();
    var relevant = recipes.stream().filter(route -> items.contains(route.result().getItem())).toList();
    var costs = new HashMap<Item, Fraction>();
    items.forEach(item -> costs.put(item, Fraction.ZERO));
    for (ItemStack goal : targets) costs.put(goal.getItem(), Fraction.ONE);
    int work = 0;
    boolean changed;
    do {
      changed = false;
      for (var route : relevant) {
        if (++work > 20_000 || ((work & 127) == 0 && System.nanoTime() >= deadline)) return null;
        Fraction required = costs.get(route.result().getItem()).multiply(route.outputCount());
        Fraction available = Fraction.ZERO;
        for (var slot : route.ingredients()) {
          Fraction minimum = null;
          for (Item item : UltsIngredients.accepted(slot)) {
            Fraction cost = costs.get(item);
            if (cost == null) return null;
            if (minimum == null || cost.compareTo(minimum) < 0) minimum = cost;
          }
          if (minimum == null) return null;
          available = available.add(minimum);
        }
        if (available.compareTo(required) >= 0) continue;
        int adjustable = 0;
        for (var slot : route.ingredients())
          if (UltsIngredients.accepted(slot).stream().allMatch(item -> base.costs.get(item).numerator().signum() > 0)) adjustable++;
        if (adjustable == 0) return null;
        Fraction unit = required.divide(adjustable);
        if (unit.numerator().bitLength() > 256 || unit.denominator().bitLength() > 256) return null;
        for (var slot : route.ingredients()) for (Item item : UltsIngredients.accepted(slot))
          if (base.costs.get(item).numerator().signum() > 0 && costs.get(item).compareTo(unit) < 0) { costs.put(item, unit); changed = true; }
      }
    } while (changed);
    var produced = new HashSet<Item>();
    relevant.forEach(route -> produced.add(route.result().getItem()));
    var held = new HashSet<Item>();
    for (int index = 0; index < pool.size(); index++) if (pool.usableAmountAt(index) > 0) held.add(pool.templateAt(index).getItem());
    for (Item item : items) if (produced.contains(item) && !held.contains(item) && base.costs.get(item).numerator().signum() > 0) costs.put(item, Fraction.HIGH);
    do {
      changed = false;
      for (var route : relevant) {
        if (++work > 40_000 || ((work & 127) == 0 && System.nanoTime() >= deadline)) return null;
        Fraction sum = Fraction.ZERO;
        for (var slot : route.ingredients()) {
          Fraction minimum = null;
          for (Item item : UltsIngredients.accepted(slot)) {
            Fraction cost = costs.get(item);
            if (minimum == null || cost.compareTo(minimum) < 0) minimum = cost;
          }
          sum = sum.add(minimum);
        }
        Fraction candidate = sum.divide(route.outputCount());
        if (candidate.numerator().bitLength() > 256 || candidate.denominator().bitLength() > 256) return null;
        Item output = route.result().getItem();
        if (candidate.compareTo(costs.get(output)) < 0) { costs.put(output, candidate); changed = true; }
      }
    } while (changed);
    return new UltsCraftPotential(target.getItem(), Map.copyOf(costs));
  }

  /** Proves a joint shortfall before exploring individual recipes. Including reserved stock is a
   * generous relaxation, so it can never reject a feasible allocation. Duplicate goals need a maximum. */
  boolean sufficient(UltsCraftPool pool, List<UltsCraftResolver.Goal> goals) {
    Fraction total = Fraction.ZERO;
    for (int index = 0; index < pool.size(); index++) {
      Fraction cost = costs.get(pool.templateAt(index).getItem());
      if (cost != null) total = total.add(cost.multiply(pool.amountAt(index)));
    }
    Map<String, UltsCraftResolver.Goal> unique = new HashMap<>();
    for (var goal : goals) unique.merge(com.flwolfy.ults.data.state.UltsStackKinds.of(goal.template()), goal,
        (first, second) -> first.amount() >= second.amount() ? first : second);
    Fraction required = Fraction.ZERO;
    for (var goal : unique.values()) required = required.add(costs.get(goal.template().getItem()).multiply(goal.amount()));
    return total.compareTo(required) >= 0;
  }

  long bound(UltsCraftPool pool) {
    return bound(pool, List.of(target));
  }

  long bound(UltsCraftPool pool, List<Item> targets) {
    Fraction unit = null;
    for (Item item : targets) {
      Fraction cost = costs.getOrDefault(item, Fraction.ZERO);
      if (unit == null || cost.compareTo(unit) < 0) unit = cost;
    }
    if (unit == null) return Long.MAX_VALUE;
    if (unit.numerator().signum() <= 0) return Long.MAX_VALUE;
    Fraction total = Fraction.ZERO;
    for (int index = 0; index < pool.size(); index++) {
      Fraction cost = costs.get(pool.templateAt(index).getItem());
      if (cost != null) total = total.add(cost.multiply(pool.usableAmountAt(index)));
    }
    BigInteger output = total.numerator().multiply(unit.denominator())
        .divide(total.denominator().multiply(unit.numerator()));
    return output.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
  }

  /** Upstream manufacture is replaced by an independently proven, generous source supply. This
   * proof bounds the initial request only; it must not prune a partially consumed working pool. */
  long boundWithSources(UltsCraftPool pool, Set<Item> sources, long supply) {
    Fraction unit = costs.getOrDefault(target, Fraction.ZERO);
    if (unit.numerator().signum() <= 0) return Long.MAX_VALUE;
    Fraction total = Fraction.ONE.multiply(supply);
    for (int index = 0; index < pool.size(); index++) {
      Item item = pool.templateAt(index).getItem();
      if (sources.contains(item)) continue;
      Fraction cost = costs.get(item);
      if (cost != null) total = total.add(cost.multiply(pool.usableAmountAt(index)));
    }
    return total.numerator().multiply(unit.denominator()).divide(total.denominator().multiply(unit.numerator()))
        .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
  }

  record Demand(Ingredient ingredient, long amount) {}
  record OutputCredit(ItemStack template, long amount) {}
  record Residue(Set<Item> family, int amount) {}

  /** Necessary resource inequality for all unfinished slots, including those of ancestor recipes.
   * Items outside this potential carry zero cost; alternative ingredients use the cheapest cost.
   * Surplus output is credited generously even when a future slot cannot use it. */
  boolean sufficientInputs(UltsCraftPool pool, List<Demand> demands, List<OutputCredit> credits, List<Residue> residues) {
    Fraction total = Fraction.ZERO;
    for (int index = 0; index < pool.size(); index++) {
      Fraction cost = costs.get(pool.templateAt(index).getItem());
      if (cost != null) total = total.add(cost.multiply(pool.usableAmountAt(index)));
    }
    for (OutputCredit credit : credits) {
      Fraction outputCost = costs.get(credit.template().getItem());
      if (outputCost != null) total = total.add(outputCost.multiply(credit.amount()));
    }
    Fraction needed = Fraction.ZERO;
    for (Demand demand : demands) {
      Fraction cheapest = null;
      for (Item item : UltsIngredients.accepted(demand.ingredient())) {
        Fraction cost = costs.getOrDefault(item, Fraction.ZERO);
        if (cheapest == null || cost.compareTo(cheapest) < 0) cheapest = cost;
      }
      if (cheapest != null) needed = needed.add(cheapest.multiply(demand.amount()));
    }
    // A fractional resource bound must also pay for stock that integer recipe batches cannot
    // consume. Use the strongest single family proof; overlapping families are never added.
    Fraction unavoidable = Fraction.ZERO;
    for (Residue residue : residues) {
      Fraction cheapest = null;
      for (Item item : residue.family()) {
        Fraction cost = costs.getOrDefault(item, Fraction.ZERO);
        if (cheapest == null || cost.compareTo(cheapest) < 0) cheapest = cost;
      }
      if (cheapest != null) {
        Fraction extra = cheapest.multiply(residue.amount());
        if (extra.compareTo(unavoidable) > 0) unavoidable = extra;
      }
    }
    return total.compareTo(needed.add(unavoidable)) >= 0;
  }

  /** At the last held variant, any unmet units must be manufactured. A necessary linear resource
   * inequality narrows the allocation interval without enumerating millions of rejected amounts. */
  long[] allocation(UltsCraftPool pool, List<Demand> future, List<OutputCredit> credits,
      Item material, List<Item> manufactured, long need, long minimum, long maximum) {
    Fraction crafted = null;
    for (Item item : manufactured) {
      Fraction cost = costs.getOrDefault(item, Fraction.ZERO);
      if (crafted == null || cost.compareTo(crafted) < 0) crafted = cost;
    }
    if (crafted == null) return new long[]{minimum, maximum};
    Fraction total = Fraction.ZERO;
    for (int index = 0; index < pool.size(); index++) {
      Fraction cost = costs.get(pool.templateAt(index).getItem());
      if (cost != null) total = total.add(cost.multiply(pool.usableAmountAt(index)));
    }
    for (OutputCredit credit : credits) {
      Fraction cost = costs.get(credit.template().getItem());
      if (cost != null) total = total.add(cost.multiply(credit.amount()));
    }
    Fraction required = Fraction.ZERO;
    for (Demand demand : future) {
      Fraction cheapest = null;
      for (Item item : UltsIngredients.accepted(demand.ingredient())) {
        Fraction cost = costs.getOrDefault(item, Fraction.ZERO);
        if (cheapest == null || cost.compareTo(cheapest) < 0) cheapest = cost;
      }
      if (cheapest != null) required = required.add(cheapest.multiply(demand.amount()));
    }
    Fraction slope = crafted.subtract(costs.getOrDefault(material, Fraction.ZERO));
    Fraction threshold = required.add(crafted.multiply(need)).subtract(total);
    return interval(slope, threshold, minimum, maximum);
  }

  private Fraction price(UltsCraftRecipe recipe) {
    Fraction total = Fraction.ZERO;
    for (Ingredient ingredient : recipe.ingredients()) {
      Fraction cheapest = null;
      for (Item item : UltsIngredients.accepted(ingredient)) {
        Fraction cost = costs.getOrDefault(item, Fraction.ZERO);
        if (cheapest == null || cost.compareTo(cheapest) < 0) cheapest = cost;
      }
      if (cheapest != null) total = total.add(cheapest);
    }
    return total;
  }

  long[] batches(UltsCraftPool pool, List<Demand> future, List<OutputCredit> credits,
      UltsCraftRecipe route, List<UltsCraftRecipe> later, long need, long minimum, long maximum) {
    Fraction alternative = null;
    for (var option : later) {
      Fraction price = price(option).divide(option.outputCount());
      if (alternative == null || price.compareTo(alternative) < 0) alternative = price;
    }
    if (alternative == null) return new long[]{minimum, maximum};
    Fraction total = Fraction.ZERO;
    for (int index = 0; index < pool.size(); index++) {
      Fraction cost = costs.get(pool.templateAt(index).getItem());
      if (cost != null) total = total.add(cost.multiply(pool.usableAmountAt(index)));
    }
    for (OutputCredit credit : credits) {
      Fraction cost = costs.get(credit.template().getItem());
      if (cost != null) total = total.add(cost.multiply(credit.amount()));
    }
    Fraction required = Fraction.ZERO;
    for (Demand demand : future) {
      Fraction cheapest = null;
      for (Item item : UltsIngredients.accepted(demand.ingredient())) {
        Fraction cost = costs.getOrDefault(item, Fraction.ZERO);
        if (cheapest == null || cost.compareTo(cheapest) < 0) cheapest = cost;
      }
      if (cheapest != null) required = required.add(cheapest.multiply(demand.amount()));
    }
    return interval(alternative.multiply(route.outputCount()).subtract(price(route)),
        required.add(alternative.multiply(need)).subtract(total), minimum, maximum);
  }

  private static long[] interval(Fraction slope, Fraction threshold, long minimum, long maximum) {
    if (slope.numerator().signum() >= 0) {
      if (slope.multiply(maximum).compareTo(threshold) < 0) return new long[]{1, 0};
      long low = minimum, high = maximum;
      while (low < high) {
        long middle = low + (high - low) / 2;
        if (slope.multiply(middle).compareTo(threshold) >= 0) high = middle;
        else low = middle + 1;
      }
      return new long[]{low, maximum};
    }
    if (slope.multiply(minimum).compareTo(threshold) < 0) return new long[]{1, 0};
    long low = minimum, high = maximum;
    while (low < high) {
      long middle = low + (high - low) / 2 + 1;
      if (slope.multiply(middle).compareTo(threshold) >= 0) low = middle;
      else high = middle - 1;
    }
    return new long[]{minimum, low};
  }
}
