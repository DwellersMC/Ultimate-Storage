package com.flwolfy.ults.data.state;

import com.flwolfy.ults.crafting.UltsCraftMath;
import com.flwolfy.ults.crafting.UltsCraftPlan;
import com.flwolfy.ults.crafting.UltsCraftPool;
import com.flwolfy.ults.crafting.UltsCraftResolver;
import com.flwolfy.ults.crafting.UltsCraftStep;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Decides whether a withdrawal can run, and what has to be crafted for it.
 *
 * <p>The decision is made on the pile itself: the plans are worked out against it, which takes the
 * ingredients of every run out of it and puts what each run makes back in. Everything that is left in
 * the pile afterwards is what the request can really be served from, so two runs that need the same
 * item can never both be promised.
 *
 * <p>Both storage modes share this, so a plan can never mean one thing in void mode and another in
 * remote mode.
 */
public final class UltsWithdrawalPlanner {

  /** How many slots one shulker box holds. */
  private static final int SHULKER_SLOTS = 27;
  /** More result stacks than this do not fit into a player inventory. */
  public static final int MAX_OUTPUT_STACKS = 36;

  private UltsWithdrawalPlanner() {}

  /**
   * Decides one withdrawal.
   *
   * @param pool contents of the storage; the working pile is modified, so hand in a copy
   * @param template what is withdrawn
   * @param quantity how much is withdrawn
   * @param boxed whether the amount counts full boxes
   * @param mode configured crafting mode
   * @return the plan, with the crafting runs it needs
   */
  public static UltsWithdrawalPlan plan(
      UltsCraftPool pool,
      ItemStack template,
      int quantity,
      boolean boxed,
      UltsCraftingMode mode
  ) {
    return plan(pool, template, quantity, boxed, mode, System.nanoTime() + 250_000_000L);
  }

  /** A preview shares its caller's budget; an unfinished search is reported as pending. */
  public static UltsWithdrawalPlan plan(
      UltsCraftPool pool, ItemStack template, int quantity, boolean boxed,
      UltsCraftingMode mode, long deadline
  ) {
    long itemAvailable = pool.amount(template);
    long boxAvailable = pool.packableAmount();
    long plainAvailable = pool.plainPackableAmount();
    int boxRequired = boxed ? quantity : 0;
    if (template.isEmpty() || quantity < 1) {
      return unavailable("invalid", itemAvailable, 0L, boxAvailable, boxRequired);
    }
    if (boxed && UltsBoxes.isShulker(template)) {
      return unavailable("nested_box", itemAvailable, 0L, boxAvailable, boxRequired);
    }
    long required = boxed
        ? UltsCraftMath.multiply(quantity, UltsCraftMath.multiply(SHULKER_SLOTS, template.getMaxStackSize()))
        : quantity;
    int outputCount = boxed
        ? quantity
        : (int) UltsCraftMath.divideRoundingUp(quantity, template.getMaxStackSize());
    if (outputCount > MAX_OUTPUT_STACKS) {
      return unavailable("too_large", itemAvailable, required, boxAvailable, boxRequired);
    }

    int mark = pool.mark();
    UltsCraftPool.Keep incoming = pool.keepState();
    List<UltsStoredView> originalBoxes = new ArrayList<>(pool.packableViews(true));
    originalBoxes.addAll(pool.packableViews(false));
    pool.reserve(template, required);
    try {
      UltsCraftResolver resolver = UltsCraftResolver.of(pool, mode, true);
      // Prefer plain boxes, but only if their materials leave enough for the contents. Every box
      // allocation remains provisional until the contents and final packaging check both succeed.
      long most = boxed ? Math.max(0L, boxRequired - plainAvailable) : 0L;
      long least = boxed ? Math.max(0L, boxRequired - boxAvailable) : 0L;
      for (long crafted = most; crafted >= least; crafted--) {
        var goals = new ArrayList<UltsCraftResolver.Goal>();
        if (crafted > 0L) goals.add(new UltsCraftResolver.Goal(
            plainBox(), UltsCraftMath.add(pool.amount(plainBox()), crafted)));
        goals.add(new UltsCraftResolver.Goal(template, required));
        List<UltsCraftPlan> plans = resolver.planTogether(goals,
            () -> !boxed || pool.packableAmount() >= boxRequired, deadline);
        if (plans == null) {
          if (resolver.ranOut()) {
            pool.rollback(mark);
            return unavailable("pending", itemAvailable, required, boxAvailable, boxRequired);
          }
          continue;
        }
        List<ItemStack> outputs = boxed
            ? packedBoxes(pool, template, boxRequired)
            : UltsWithdrawalOutput.looseStacks(template, quantity);
        List<UltsCraftStep> steps = plans.stream().flatMap(plan -> plan.steps().stream()).toList();
        long storedUsed = boxed ? storedBoxesUsed(pool, originalBoxes, boxRequired) : 0L;
        return new UltsWithdrawalPlan(
            true, "", itemAvailable, required, boxAvailable, boxRequired,
            storedUsed, boxRequired - storedUsed, outputs,
            Math.max(0L, required - itemAvailable), steps);
      }
      pool.rollback(mark);
      return unavailable(boxed && boxAvailable < boxRequired && itemAvailable >= required
          ? "boxes" : "items", itemAvailable, required, boxAvailable, boxRequired);
    } finally { pool.restoreKeep(incoming); }
  }

  /**
   * Runs a plan on a pile: every run takes its ingredients and puts what it makes back in, and the
   * request is then taken out of the pile.
   *
   * @param pool the pile to work on; a failed run restores it
   * @param plan the plan to run
   * @param template what is withdrawn
   * @param quantity how much is withdrawn
   * @param boxed whether the amount counts full boxes
   * @return whether the pile could serve the request
   */
  public static boolean run(
      UltsCraftPool pool,
      UltsWithdrawalPlan plan,
      ItemStack template,
      int quantity,
      boolean boxed
  ) {
    long required = boxed
        ? UltsCraftMath.multiply(quantity, UltsCraftMath.multiply(SHULKER_SLOTS, template.getMaxStackSize()))
        : quantity;
    // Marks the pile before any of it is touched: a run that cannot finish puts back what it had already
    // taken, so a caller is never left with ingredients spent and nothing to show for them.
    if (!plan.available() || template.isEmpty() || quantity < 1) return false;
    int mark = pool.mark();
    UltsCraftPool.Keep incoming = pool.keepState();
    pool.reserve(template, required);
    try {
      for (UltsCraftStep step : plan.steps()) {
        if (!step.run(pool)) { pool.rollback(mark); return false; }
      }
      pool.restoreKeep(incoming);
      if (pool.take(template, required) < required
          || (boxed && pool.takePackable(quantity) < quantity)) {
        pool.rollback(mark);
        return false;
      }
      return true;
    } finally { pool.restoreKeep(incoming); }
  }

  private static UltsWithdrawalPlan unavailable(
      String problem,
      long itemAvailable,
      long itemRequired,
      long boxAvailable,
      int boxRequired
  ) {
    return new UltsWithdrawalPlan(
        false, problem, itemAvailable, itemRequired, boxAvailable, boxRequired,
        0L, 0L, List.of(), 0L, List.of());
  }

  /**
   * Builds the boxes of a packed withdrawal in the order the storage gives them out: plain boxes
   * first, then the plain ones that were crafted, and the other colours only after those.
   */
  private static List<ItemStack> packedBoxes(
      UltsCraftPool pool,
      ItemStack template,
      int quantity
  ) {
    List<ItemStack> result = new ArrayList<>(quantity);
    for (boolean plain : new boolean[]{true, false}) {
      for (UltsStoredView view : pool.packableViews(plain)) {
        for (long index = 0; index < view.amount() && result.size() < quantity; index++) {
          result.add(UltsWithdrawalOutput.packedBox(view.template(), template));
        }
        if (result.size() == quantity) return List.copyOf(result);
      }
    }
    return List.copyOf(result);
  }

  /** Counts the original boxes actually selected by the same order used for packing and replay. */
  private static long storedBoxesUsed(UltsCraftPool pool, List<UltsStoredView> original, int quantity) {
    long stored = 0L, remaining = quantity;
    for (boolean plain : new boolean[]{true, false}) {
      for (UltsStoredView box : pool.packableViews(plain)) {
        long used = Math.min(remaining, box.amount());
        long before = original.stream().filter(view -> UltsStackKinds.same(view.template(), box.template()))
            .mapToLong(UltsStoredView::amount).findFirst().orElse(0L);
        stored += Math.min(used, before);
        remaining -= used;
        if (remaining == 0L) return stored;
      }
    }
    return stored;
  }

  /** One item of the uncolored shulker box, the only box that may be crafted. */
  private static ItemStack plainBox() {
    return Items.SHULKER_BOX.getDefaultInstance();
  }
}
