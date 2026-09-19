package com.flwolfy.ults.crafting;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * One recipe the storage may run.
 *
 * @param stonecutter whether the recipe needs a stonecutter instead of a crafting table
 * @param id registry id of the recipe, used to keep the route order stable
 * @param ingredients one entry per ingredient slot, empty slots already removed
 * @param result one item of what the recipe produces, with all of its components
 * @param outputCount how many items one operation produces
 */
public record UltsCraftRecipe(
    boolean stonecutter,
    String id,
    List<Ingredient> ingredients,
    ItemStack result,
    int outputCount
) {

  public UltsCraftRecipe {
    ingredients = List.copyOf(ingredients);
    result = result.copyWithCount(1);
  }

  /** The station this recipe needs, as the item the storage has to hold. */
  public boolean needsStonecutter() {
    return stonecutter;
  }
}
