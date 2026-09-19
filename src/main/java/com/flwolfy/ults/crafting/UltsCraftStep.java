package com.flwolfy.ults.crafting;

/**
 * One decided crafting run: a recipe and how often it has to run.
 *
 * @param recipe recipe to run
 * @param operations how many times to run it
 */
public record UltsCraftStep(UltsCraftRecipe recipe, long operations) {

  public UltsCraftStep {
    if (operations < 1L) {
      throw new IllegalArgumentException("A crafting step has to run at least once");
    }
  }

  /** How many items this run makes. */
  public long output() {
    return UltsCraftMath.multiply(operations, recipe.outputCount());
  }
}
