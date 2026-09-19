package com.flwolfy.ults.data.config;

/**
 * When the storage may craft for a withdrawal that runs short.
 *
 * <p>Crafting always needs the matching station in stock: a crafting table for crafting recipes and a
 * stonecutter for stonecutting recipes. The station itself is never consumed.
 */
public enum UltsCraftingMode {

  /** No automatic crafting at all. This is the default. */
  DISABLED,

  /** Only an uncolored shulker box may be crafted, which is what a full-box withdrawal packs into. */
  SHULKER_BOXES_ONLY,

  /** Any recipe the stored station can run. */
  ALL;

  /** Whether this mode allows crafting at all. */
  public boolean enabled() {
    return this != DISABLED;
  }
}
