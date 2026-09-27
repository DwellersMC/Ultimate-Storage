package com.flwolfy.ults.data.state;

import net.minecraft.world.item.ItemStack;

/**
 * One row of the storage: a kind of item, how many of it are held, and what is known about it.
 *
 * @param template one item of the kind, with every component it carries
 * @param amount how many of it are held
 * @param special whether it carries data of its own, so it cannot stack with anything
 * @param updatedAt when this kind was last stored, as a wall clock time, or {@code 0} when unknown
 */
public record UltsStoredView(ItemStack template, long amount, boolean special, long updatedAt) {

  public UltsStoredView {
    template = template.copyWithCount(1);
  }

  /** A row whose age is not known, which is what a listing that only cares about contents uses. */
  public UltsStoredView(ItemStack template, long amount, boolean special) {
    this(template, amount, special, 0L);
  }

  /** Whether anything is known about when this kind was last stored. */
  public boolean stampKnown() {
    return updatedAt > 0L;
  }
}
