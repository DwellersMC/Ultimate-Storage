package com.flwolfy.ults.data.config;

/**
 * What the storage screen lists.
 *
 * <p>The {@code All Items} category is always shown, whatever the mode is.
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

  /** Only items that are in stock; categories without stock are hidden as well. */
  STOCKED_COMPACT
}
