package com.flwolfy.ults.crafting;

import com.flwolfy.ults.data.state.UltsStoredView;
import java.util.List;

/**
 * One decided crafting run: a recipe and how often it has to run.
 *
 * @param recipe recipe to run
 * @param operations how many times to run it
 * @param consumed concrete material quantities selected by planning; empty for legacy manual steps
 */
public record UltsCraftStep(UltsCraftRecipe recipe, long operations, List<UltsStoredView> consumed) {

  /** Legacy manually constructed steps resolve their slots when run. */
  public UltsCraftStep(UltsCraftRecipe recipe, long operations) {
    this(recipe, operations, List.of());
  }

  public UltsCraftStep {
    consumed = consumed.stream().map(view -> new UltsStoredView(
        view.template().copyWithCount(1), view.amount(), false)).toList();
    if (operations < 1L) {
      throw new IllegalArgumentException("A crafting step has to run at least once");
    }
  }

  /** How many items this run makes. */
  public long output() {
    return UltsCraftMath.multiply(operations, recipe.outputCount());
  }

  /** Executes the same material allocation as the search, without choosing ingredients again. */
  public boolean run(UltsCraftPool pool) {
    int mark = pool.mark();
    List<UltsStoredView> returns = new java.util.ArrayList<>();
    if (consumed.isEmpty()) {
      for (var ingredient : recipe.ingredients()) {
        if (pool.takeMatching(ingredient, operations, recipe.needsStonecutter() ? null : returns)
            < operations) {
          pool.rollback(mark);
          return false;
        }
      }
    } else {
      for (UltsStoredView material : consumed) {
        if (pool.take(material.template(), material.amount()) < material.amount()) {
          pool.rollback(mark);
          return false;
        }
        if (!recipe.needsStonecutter()) {
          addRemainder(material, returns);
        }
      }
    }
    for (UltsStoredView returned : returns) {
      pool.add(returned.template(), returned.amount());
    }
    pool.add(recipe.result(), output());
    return true;
  }

  static void addRemainder(UltsStoredView material, List<UltsStoredView> returns) {
    var remainder = material.template().getItem().getCraftingRemainder();
    if (remainder != null) {
      var returned = remainder.create();
      returns.add(new UltsStoredView(returned.copyWithCount(1),
          UltsCraftMath.multiply(material.amount(), returned.getCount()), false));
    }
  }
}
