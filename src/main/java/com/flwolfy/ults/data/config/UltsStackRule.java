package com.flwolfy.ults.data.config;

/**
 * When two stacks are the same kind of thing, which is what lets them pool into one row.
 *
 * <p>An item and a name are the precondition either way: a diamond sword and a netherite sword are two
 * things, and so are a sword called one thing and the same sword called another. What this decides is
 * how much of the rest of a stack's data has to agree before the two are one pile.
 */
public enum UltsStackRule {

  /**
   * Every component has to be identical, which is the game's own rule for two interchangeable stacks.
   *
   * <p>An enchantment, a potion's contents, a name, an attribute — and durability: two swords worn
   * differently are two kinds of thing, so each keeps its own row and a player is always handed back
   * the very stack that was stored.
   */
  COMPONENTS,

  /**
   * The tooltip has to read the same, which is what a player can see of a stack.
   *
   * <p>Two swords with the same enchantment pool even when one is more worn than the other, because
   * nothing on the tooltip tells them apart. A stack handed over is a stack of that kind rather than
   * the exact one that was put in: the difference the rule ignores is a difference the player cannot
   * see, and the storage hands over the kind it kept.
   */
  TOOLTIP;

  /** Whether two stacks of one item pool by their components alone. */
  public boolean byComponents() {
    return this == COMPONENTS;
  }
}
