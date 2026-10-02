package com.flwolfy.ults.crafting;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/** Installs a deterministic catalogue and restores it when the test closes. */
public final class UltsTestCatalog implements AutoCloseable, UltsCraftSource {
  private final UltsCraftSource previous;
  private final List<UltsCraftRecipe> recipes;

  public UltsTestCatalog(UltsCraftRecipe... recipes) {
    previous = UltsCraftResolver.source;
    this.recipes = List.copyOf(Arrays.asList(recipes));
    UltsCraftResolver.source = this;
  }

  @Override public List<UltsCraftRecipe> recipes(ItemStack template) {
    return recipes.stream().filter(recipe ->
        ItemStack.isSameItemSameComponents(recipe.result(), template)).toList();
  }

  @Override public List<ItemStack> candidates(Ingredient ingredient) {
    return recipes.stream().map(UltsCraftRecipe::result).filter(ingredient::test).toList();
  }

  @Override public List<UltsCraftRecipe> everything() { return recipes; }
  @Override public void close() { UltsCraftResolver.source = previous; }
}
