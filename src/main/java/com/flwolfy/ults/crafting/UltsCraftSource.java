package com.flwolfy.ults.crafting;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Where a search looks recipes up.
 *
 * <p>In game this is the catalogue the server was read into. A test hands in a handful of recipes
 * instead, which is what makes the search itself testable without a running server.
 */
interface UltsCraftSource {

  /** The routes that produce exactly this stack, best first. */
  List<UltsCraftRecipe> recipes(ItemStack template);

  /** The stacks a slot could be filled with, in a stable order. */
  List<ItemStack> candidates(Ingredient ingredient);

  /** Every recipe, for the pass that marks what a pile can reach. */
  List<UltsCraftRecipe> everything();
}
