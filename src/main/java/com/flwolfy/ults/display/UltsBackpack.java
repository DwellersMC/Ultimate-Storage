package com.flwolfy.ults.display;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * A player's backpack as a thing that can be measured and tried out before anything is handed over.
 *
 * <p>Every screen that gives items away asks the same two questions — how much will the backpack take,
 * and which of these stacks fit whole — and both are answered here rather than in the screens, so the
 * amount screen, the bag and the take-everything screen can never disagree about what a backpack can
 * hold. The answers are worked out on a copy of the 36 slots, exactly the way the game's own hand-over
 * fills them: a stack of the same thing is topped up before a new slot is opened.
 */
final class UltsBackpack {

  /** The slots a player's backpack is: the hotbar and the three rows above it, and nothing else. */
  static final int SLOTS = 36;

  private UltsBackpack() {}

  /** A copy of a player's 36 backpack slots, which a hand-over can be tried out on. */
  static List<ItemStack> slots(ServerPlayer player) {
    List<ItemStack> slots = new ArrayList<>(SLOTS);
    for (int slot = 0; slot < SLOTS; slot++) {
      slots.add(player.getInventory().getItem(slot).copy());
    }
    return slots;
  }

  /** How many more pieces of one stack a backpack can take as it stands. */
  static long room(List<ItemStack> slots, ItemStack template) {
    long room = 0L;
    for (ItemStack current : slots) {
      if (current.isEmpty()) {
        room += Math.max(1, template.getMaxStackSize());
      } else if (ItemStack.isSameItemSameComponents(current, template)) {
        room += Math.max(0, current.getMaxStackSize() - current.getCount());
      }
    }
    return room;
  }

  /**
   * How many of these stacks a backpack can take whole, in the order they are listed.
   *
   * <p>A stack that does not fit entirely stops the count: half a stack in a backpack is not what handing
   * one over means, and what a caller does with the rest — drop it, hand it back, leave it in the storage
   * — is its own business and not this question. The trial is made on a copy of the slots, so asking
   * leaves the backpack it was asked about as it was.
   *
   * @param slots the backpack to try them in, which is not changed
   * @param stacks the stacks that would be handed over, in the order they would be
   * @return how many of them fit
   */
  static int fitting(List<ItemStack> slots, List<ItemStack> stacks) {
    List<ItemStack> working = new ArrayList<>(slots.size());
    slots.forEach(stack -> working.add(stack.copy()));
    int fitting = 0;
    for (ItemStack stack : stacks) {
      if (!place(working, stack)) {
        break;
      }
      fitting++;
    }
    return fitting;
  }

  /**
   * Puts one stack into a simulated backpack, the way the game's own hand-over does it.
   *
   * <p>The copy is left holding it, so a caller may keep asking with the same backpack and get the
   * answer for one stack after another.
   *
   * @param slots the backpack being filled, which is changed
   * @param stack the stack being handed over, which is not changed
   * @return whether all of it fitted
   */
  static boolean place(List<ItemStack> slots, ItemStack stack) {
    return fill(slots, stack).isEmpty();
  }

  /**
   * Puts one stack into a player's real backpack and answers what did not fit.
   *
   * <p>This exists because the game's own hand-over cannot be trusted to say what it did: it takes the
   * stack as a piece of work and may leave it empty while the backpack never received a single piece, so
   * a caller that hands a stack over and then looks at it is told the items went somewhere when in truth
   * they went nowhere. Here the stack is left alone and the leftover comes back as a stack of its own, so
   * what did not fit can be dropped or handed back.
   *
   * @param player the player receiving
   * @param stack the stack being handed over, which is not changed
   * @return what did not fit, empty when all of it went in
   */
  static ItemStack placeInto(ServerPlayer player, ItemStack stack) {
    List<ItemStack> trial = slots(player);
    ItemStack remaining = fill(trial, stack);
    for (int slot = 0; slot < SLOTS; slot++) {
      ItemStack before = player.getInventory().getItem(slot);
      ItemStack after = trial.get(slot);
      if (before.getCount() != after.getCount()
          || !ItemStack.isSameItemSameComponents(before, after)) {
        player.getInventory().setItem(slot, after);
      }
    }
    return remaining;
  }

  /**
   * Fills a backpack with one stack, topping up stacks of the same thing before opening new slots.
   *
   * <p>This is the one place the filling rules live: {@link #place} asks whether a stack would fit whole,
   * and {@link #placeInto} hands a real player one and keeps what did not fit.
   *
   * @param slots the backpack being filled, which is changed
   * @param stack the stack being handed over, which is not changed
   * @return what did not fit
   */
  static ItemStack fill(List<ItemStack> slots, ItemStack stack) {
    ItemStack remaining = stack.copy();
    for (ItemStack current : slots) {
      if (remaining.isEmpty()) {
        break;
      }
      if (ItemStack.isSameItemSameComponents(current, remaining)
          && current.getCount() < current.getMaxStackSize()) {
        int moved = Math.min(
            remaining.getCount(), current.getMaxStackSize() - current.getCount());
        current.grow(moved);
        remaining.shrink(moved);
      }
    }
    for (int slot = 0; slot < slots.size() && !remaining.isEmpty(); slot++) {
      if (!slots.get(slot).isEmpty()) {
        continue;
      }
      int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
      slots.set(slot, remaining.copyWithCount(moved));
      remaining.shrink(moved);
    }
    return remaining;
  }
}
