package com.flwolfy.ults.display;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * What one confirmation of "take everything" would really hand over.
 *
 * <p>A stock can hold more than any backpack ever will, so "everything" is two numbers and not one: what
 * is there, and what one click takes. This is the second, worked out from the storage's totals, the stacks
 * the question is about and the backpack as it stands — and it is worked out here rather than in the
 * screen so it can be read, tested and relied on without a player attached.
 *
 * <p>The rule is one {@code input.takeAllStacks} at a time, one backpack's worth by default, and that
 * whole amount is a <em>stream</em>: the screen promises what the storage will hand over, and the storage
 * hands it over a tick's worth at a time. What the backpack cannot take is said before the click rather
 * than discovered after it:
 * <ul>
 *   <li>While {@code allowFullInventory} is off, a click promises only what the backpack takes, and the
 *       rest of the stock stays where it is. A backpack with no room at all takes nothing, which is what
 *       makes the confirmation a barrier.</li>
 *   <li>While it is on, a click promises the whole limit and the part that does not fit is dropped on the
 *       ground, a tick's worth at a time. The limit is the bound, so emptying a warehouse can never drop
 *       a pile of loose items big enough to take the server with it.</li>
 * </ul>
 *
 * @param total everything the storage holds of what is being asked about, crafting included when the mode
 *     says so
 * @param units how many stacks one click would take: the pieces are one unit for a single item, and one
 *     unit per row for a whole bag
 * @param taken how many pieces one click would take, which is what the screen is a question about
 * @param pack how many of those pieces the backpack takes
 * @param ground how many of them would land on the ground instead
 * @param left how many pieces of the stock would stay behind
 */
record UltsTakeAllPlan(long total, int units, long taken, long pack, long ground, long left) {

  /**
   * Works out what one click can carry.
   *
   * @param total everything the storage holds of the item, or of the whole bag
   * @param wanted the stacks the question is about: one template for a single item, every row for a bag
   * @param amounts how many pieces each of those stacks holds, which for a bag is what really leaves
   * @param overflow whether the configuration lets what does not fit land on the ground
   * @param limitStacks how many stacks one take-everything takes at most
   * @param slots the backpack as it stands, which is not changed
   * @return the plan
   */
  static UltsTakeAllPlan of(
      long total,
      List<ItemStack> wanted,
      List<Long> amounts,
      boolean overflow,
      int limitStacks,
      List<ItemStack> slots
  ) {
    if (total <= 0L || wanted.isEmpty()) {
      return new UltsTakeAllPlan(Math.max(0L, total), 0, 0L, 0L, 0L, Math.max(0L, total));
    }
    int limit = Math.max(1, limitStacks);
    if (wanted.size() == 1) {
      // One kind of thing: the pieces are the measure, and the room a backpack has already counts the
      // stacks of that thing it can top up as well as the slots it can open.
      ItemStack template = wanted.getFirst();
      long room = UltsBackpack.room(slots, template);
      long most = (long) limit * Math.max(1, template.getMaxStackSize());
      long taken = Math.min(total, overflow ? most : Math.min(most, room));
      long pack = Math.min(taken, room);
      return new UltsTakeAllPlan(total, 1, taken, pack, taken - pack, total - taken);
    }
    // A whole bag: every row is a kind of its own, so the measure is the rows, and a row leaves as it is
    // or not at all — half of one is not a thing a bag can hand over. Each row is measured by what it
    // really holds: a row of sixty pieces takes sixty places in the backpack, not one.
    List<ItemStack> trial = new ArrayList<>(slots);
    long pack = 0L;
    int fitting = 0;
    long most = 0L;
    int units = 0;
    for (int index = 0; index < wanted.size() && index < limit; index++) {
      long pieces = Math.max(1L, amounts.get(index));
      ItemStack row = wanted.get(index).copyWithCount(
          (int) Math.min(pieces, Integer.MAX_VALUE));
      most += pieces;
      units++;
      if (fitting == index && UltsBackpack.place(trial, row)) {
        pack += pieces;
        fitting++;
      }
    }
    long taken = overflow ? most : pack;
    return new UltsTakeAllPlan(
        total, overflow ? units : fitting, taken, pack, taken - pack, total - taken);
  }

  /** Whether a click could hand anything over at all. */
  boolean possible() {
    return taken > 0L;
  }

  /** Whether the stock is bigger than one click can carry, so some of it stays behind. */
  boolean partial() {
    return possible() && left > 0L;
  }

  /** Whether the stock is there but the backpack cannot take it, with nothing allowed on the ground. */
  boolean blocked() {
    return total > 0L && taken <= 0L;
  }
}
