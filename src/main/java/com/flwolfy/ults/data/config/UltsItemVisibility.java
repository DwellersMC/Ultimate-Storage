package com.flwolfy.ults.data.config;

/**
 * What the storage screen lists.
 *
 * <p>The configured value is the default for players who never switched the mode themselves; every
 * player may pick one of the modes they are allowed to use in the storage screen, and the choice is
 * remembered with their view. The {@code All Items} category is always shown, whatever the mode is.
 */
public enum UltsItemVisibility {

  /** Every catalogued item of every category, stocked or not. */
  ALL,

  /**
   * Everything a survival player can obtain, stocked or not, plus anything else that is in stock.
   *
   * <p>There is no vanilla flag for "obtainable in survival", so it is derived from the server data:
   * a recipe result or a loot table drop counts as obtainable, which also covers modded items.
   */
  SURVIVAL,

  /**
   * What the storage can hand over right now: anything in stock and, while automatic crafting is on
   * and a station is stored, anything that can be crafted at this moment. Categories without such an
   * item are hidden as well.
   */
  AVAILABLE;

  /** The next mode in the declared order, wrapping around. */
  public UltsItemVisibility next() {
    UltsItemVisibility[] modes = values();
    return modes[(ordinal() + 1) % modes.length];
  }
}
