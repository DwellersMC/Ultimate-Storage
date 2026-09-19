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
import net.minecraft.world.item.crafting.Ingredient;

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
  private static final int MAX_OUTPUT_STACKS = 36;

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

    // The boxes are decided first: a box is made from what the storage holds, and only what is left
    // after that is available for the item itself. The request itself is off limits to both plans for
    // as long as they are worked out, so a recipe on the way can never eat what has to be handed over.
    pool.keep(template, required);
    long missingPlain = boxed ? Math.max(0L, boxRequired - plainAvailable) : 0L;
    UltsCraftPlan boxPlan = UltsCraftPlan.NONE;
    if (missingPlain > 0L) {
      UltsCraftPlan wanted = UltsCraftResolver.plan(plainBox(), missingPlain, pool, mode, true);
      if (wanted == null) {
        // Not every missing box can be crafted; the other colours may cover what is left over.
        long possible = Math.min(
            missingPlain, UltsCraftResolver.capacity(plainBox(), pool, mode, true));
        wanted = possible > 0L
            ? UltsCraftResolver.plan(plainBox(), possible, pool, mode, true)
            : UltsCraftPlan.NONE;
      }
      if (wanted == null) {
        pool.keep(null, 0L);
        return unavailable("boxes", itemAvailable, required, boxAvailable, boxRequired);
      }
      boxPlan = wanted;
    }
    // What the request is short of is crafted; the pile is left holding the result of those runs.
    long missingItems = Math.max(0L, required - itemAvailable);
    UltsCraftPlan itemPlan = missingItems > 0L
        ? UltsCraftResolver.plan(template, missingItems, pool, mode, true)
        : UltsCraftPlan.NONE;
    if (itemPlan == null) {
      pool.keep(null, 0L);
      return unavailable("items", itemAvailable, required, boxAvailable, boxRequired);
    }
    pool.keep(null, 0L);
    if (pool.amount(template) < required) {
      return unavailable("items", itemAvailable, required, boxAvailable, boxRequired);
    }
    if (boxed && pool.packableAmount() < boxRequired) {
      return unavailable("boxes", itemAvailable, required, boxAvailable, boxRequired);
    }

    long plainUsed = Math.min(plainAvailable, boxRequired);
    long craftedUsed = Math.min(boxPlan.output(), boxRequired - plainUsed);
    long otherUsed = Math.max(0L, boxRequired - plainUsed - craftedUsed);
    List<ItemStack> outputs = boxed
        ? packedBoxes(pool, template, boxRequired, plainUsed + craftedUsed, otherUsed)
        : UltsWithdrawalOutput.looseStacks(template, quantity);
    if (outputs.size() < outputCount) {
      return unavailable("boxes", itemAvailable, required, boxAvailable, boxRequired);
    }
    // The runs are recorded in the order they were worked out, so running them again on another pile
    // of the same contents leads to the same result.
    List<UltsCraftStep> steps = new ArrayList<>(boxPlan.steps());
    steps.addAll(itemPlan.steps());
    return new UltsWithdrawalPlan(
        true, "", itemAvailable, required, boxAvailable, boxRequired,
        plainUsed + otherUsed, craftedUsed, outputs, missingItems, steps);
  }

  /**
   * Runs a plan on a pile: every run takes its ingredients and puts what it makes back in, and the
   * request is then taken out of the pile.
   *
   * @param pool the pile to work on; a failed run may have left it partly used
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
    pool.keep(template, required);
    for (UltsCraftStep step : plan.steps()) {
      for (Ingredient ingredient : step.recipe().ingredients()) {
        if (pool.takeMatching(ingredient, step.operations()) < step.operations()) {
          pool.keep(null, 0L);
          return false;
        }
      }
      pool.add(step.recipe().result(), step.output());
    }
    pool.keep(null, 0L);
    if (pool.take(template, required) < required) {
      return false;
    }
    return !boxed || pool.takePackable(quantity) >= quantity;
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
      int quantity,
      long plainBoxes,
      long otherBoxes
  ) {
    List<ItemStack> result = new ArrayList<>(quantity);
    for (long index = 0; index < plainBoxes && result.size() < quantity; index++) {
      result.add(UltsWithdrawalOutput.packedBox(plainBox(), template));
    }
    long remaining = Math.min(otherBoxes, quantity - result.size());
    if (remaining > 0L) {
      for (UltsStoredView view : pool.packableViews(false)) {
        for (long index = 0; index < view.amount() && remaining > 0L; index++) {
          result.add(UltsWithdrawalOutput.packedBox(view.template(), template));
          remaining--;
        }
        if (remaining <= 0L) {
          break;
        }
      }
    }
    return List.copyOf(result);
  }

  /** One item of the uncolored shulker box, the only box that may be crafted. */
  private static ItemStack plainBox() {
    return Items.SHULKER_BOX.getDefaultInstance();
  }
}
