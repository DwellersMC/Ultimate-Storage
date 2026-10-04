package com.flwolfy.ults.crafting;

import java.math.BigInteger;
import java.util.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
    if (targets.isEmpty()) return null;
    if (recipes.size() > 20_000 || System.nanoTime() >= deadline) return null;
    Map<Item, List<UltsCraftRecipe>> incoming = new HashMap<>();
    int scanned = 0;
    for (var route : recipes) {
      if ((++scanned & 127) == 0 && System.nanoTime() >= deadline) return null;
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
    // Give producible items room to converge to their actual recipe cost. Starting every item at one
    // permanently underprices expensive outputs (a box costs both a chest and shells), hiding joint
    // shortfalls. Any nonnegative fixed point is a valid proof; ungrounded cycles may keep a high cost.
    ancestors.forEach(item -> costs.put(item, incoming.containsKey(item) ? Fraction.HIGH : Fraction.ONE));
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
    Fraction unit = costs.get(target);
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
}
