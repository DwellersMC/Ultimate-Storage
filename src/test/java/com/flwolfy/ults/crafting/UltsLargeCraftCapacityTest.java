package com.flwolfy.ults.crafting;

import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;
import static org.junit.jupiter.api.Assertions.*;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.*;

@Tag("acceptance")
class UltsLargeCraftCapacityTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }
  private static ItemStack item(Item item) {
    var result = stack(item); result.set(DataComponents.MAX_STACK_SIZE, 64); return result;
  }
  private static UltsCraftRecipe recipe(String id, Item output, int count, int planks, int sticks) {
    var slots = new ArrayList<Ingredient>();
    for (int i = 0; i < planks; i++) slots.add(Ingredient.of(Items.SPRUCE_PLANKS));
    for (int i = 0; i < sticks; i++) slots.add(Ingredient.of(Items.STICK));
    return new UltsCraftRecipe(false, id, slots, item(output), count);
  }

  @Test void largeWoodStocksHaveExactRecipeRoundedCapacitiesWithoutMutation() {
    var planks = new UltsCraftRecipe(false, "planks", List.of(Ingredient.of(Items.SPRUCE_LOG)), item(Items.SPRUCE_PLANKS), 4);
    try (var catalog = new UltsTestCatalog(planks, recipe("sticks", Items.STICK, 4, 2, 0),
        recipe("slabs", Items.SPRUCE_SLAB, 6, 3, 0), recipe("stairs", Items.SPRUCE_STAIRS, 4, 6, 0),
        recipe("gates", Items.SPRUCE_FENCE_GATE, 1, 2, 4), recipe("fences", Items.SPRUCE_FENCE, 3, 4, 2))) {
      Item[] targets = {Items.SPRUCE_SLAB, Items.SPRUCE_STAIRS, Items.SPRUCE_FENCE_GATE, Items.SPRUCE_FENCE};
      long[][] expected = {{319998, 106664, 40000, 96000}, {320004, 106668, 40001, 96000}};
      for (int count = 40000; count <= 40001; count++) {
        var pool = new UltsCraftPool(8);
        pool.add(item(Items.SPRUCE_LOG), count); pool.add(item(Items.CRAFTING_TABLE), 1);
        pool.add(item(Items.SPRUCE_SLAB), 7);
        int mark = pool.mark();
        var resolver = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true);
        for (int index = 0; index < targets.length; index++) {
          assertEquals(expected[count - 40000][index], resolver.capacity(item(targets[index])));
          assertEquals(count, pool.amount(item(Items.SPRUCE_LOG)));
          assertEquals(7, pool.amount(item(Items.SPRUCE_SLAB)), "stored target is excluded from additional capacity");
          assertEquals(mark, pool.mark());
        }
      }
    }
  }

  @Test void aTimedOutBoundIsRetriedInsteadOfPermanentlyDisablingTheOptimization() {
    var planks = new UltsCraftRecipe(false, "planks", List.of(Ingredient.of(Items.SPRUCE_LOG)), item(Items.SPRUCE_PLANKS), 4);
    try (var catalog = new UltsTestCatalog(planks, recipe("slabs", Items.SPRUCE_SLAB, 6, 3, 0))) {
      var pool = new UltsCraftPool(8);
      pool.add(item(Items.SPRUCE_LOG), 40000); pool.add(item(Items.CRAFTING_TABLE), 1);
      var resolver = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true);
      assertTrue(resolver.craftable(item(Items.SPRUCE_SLAB)));
      long deadline = System.nanoTime() + 50_000_000L;
      boolean[] first = {true};
      UltsCraftResolver.source = new UltsCraftSource() {
        @Override public List<UltsCraftRecipe> recipes(ItemStack template) { return catalog.recipes(template); }
        @Override public List<ItemStack> candidates(Ingredient ingredient) { return catalog.candidates(ingredient); }
        @Override public List<UltsCraftRecipe> everything() {
          if (first[0]) {
            first[0] = false;
            while (System.nanoTime() < deadline) Thread.onSpinWait();
          }
          return catalog.everything();
        }
      };
      int mark = pool.mark();
      assertEquals(UltsCraftResolver.UNKNOWN, resolver.capacity(item(Items.SPRUCE_SLAB), deadline));
      assertEquals(319998, resolver.capacity(item(Items.SPRUCE_SLAB)), "the next frame rebuilds the exact bound");
      assertEquals(40000, pool.amount(item(Items.SPRUCE_LOG)));
      assertEquals(mark, pool.mark(), "neither the timeout nor the successful query consumes ingredients");
    }
  }
}
