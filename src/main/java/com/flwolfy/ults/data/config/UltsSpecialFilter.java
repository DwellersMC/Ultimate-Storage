package com.flwolfy.ults.data.config;

/**
 * What the special item filter does to the items it names.
 *
 * <p>A stack that carries data of its own — an enchanted or damaged tool, a named book — cannot be
 * stacked with anything and takes a row of its own, so a busy server fills the special category up.
 * The filter names the items that are not worth a row, and this decides how hard it comes down on
 * them. A plain stack of the same item is never touched: it stacks, so it is not special at all.
 */
public enum UltsSpecialFilter {

  /** The filter is not in effect and every special stack is kept. */
  OFF,

  /** A named item is destroyed only when it is worn: one at full durability is kept as it is. */
  KEEP_FULL_DURABILITY,

  /** A named item is destroyed whatever state it is in. */
  FILTER_ALL;

  /** Whether the filter may destroy anything at all. */
  public boolean active() {
    return this != OFF;
  }
}
