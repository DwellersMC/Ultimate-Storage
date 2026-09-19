package com.flwolfy.ults.crafting;

import java.util.List;

/**
 * A decided crafting sequence that turns a pile of items into the requested ones.
 *
 * <p>The sequence is what makes chained crafting possible: a step may consume what an earlier step
 * made, so a shulker box can be crafted from shells and logs by making the chest first.
 *
 * @param steps the runs in the order they have to happen
 * @param output how many items of the requested kind the sequence makes
 */
public record UltsCraftPlan(List<UltsCraftStep> steps, long output) {

  public static final UltsCraftPlan NONE = new UltsCraftPlan(List.of(), 0L);

  public UltsCraftPlan {
    steps = List.copyOf(steps);
  }

  /** Whether the sequence really covers a missing amount. */
  public boolean covers(long missing) {
    return output > 0L && output >= missing;
  }
}
