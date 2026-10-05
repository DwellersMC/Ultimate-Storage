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

  @Test void tensOfMillionsWithHeldIntermediatesResolveExactRoundingBoundaries() {
    var planks = new UltsCraftRecipe(false, "planks", List.of(Ingredient.of(Items.SPRUCE_LOG, Items.SPRUCE_WOOD)), item(Items.SPRUCE_PLANKS), 4);
    var wood = new UltsCraftRecipe(false, "wood", java.util.Collections.nCopies(4, Ingredient.of(Items.SPRUCE_LOG)), item(Items.SPRUCE_WOOD), 3);
    try (var catalog = new UltsTestCatalog(planks, wood, recipe("sticks", Items.STICK, 4, 2, 0),
        recipe("fences", Items.SPRUCE_FENCE, 3, 4, 2), recipe("sword", Items.WOODEN_SWORD, 1, 2, 1))) {
      var pool = new UltsCraftPool(8);
      pool.add(item(Items.SPRUCE_LOG), 40_000_003); pool.add(item(Items.SPRUCE_PLANKS), 17);
      pool.add(item(Items.STICK), 3); pool.add(item(Items.CRAFTING_TABLE), 1);
      int mark = pool.mark();
      var resolver = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true);
      assertEquals(96_000_015, resolver.capacity(item(Items.SPRUCE_FENCE)));
      assertEquals(64_000_011, resolver.capacity(item(Items.WOODEN_SWORD)));
      assertEquals(40_000_003, pool.amount(item(Items.SPRUCE_LOG)));
      assertEquals(17, pool.amount(item(Items.SPRUCE_PLANKS)));
      assertEquals(3, pool.amount(item(Items.STICK)));
      assertEquals(mark, pool.mark());
    }
  }

  @Test void abundantPigmentCannotHideTheGlassFamilyBatchBoundary() {
    Item[] glasses = {Items.STAINED_GLASS.white(), Items.STAINED_GLASS.black(), Items.STAINED_GLASS.red(), Items.STAINED_GLASS.blue()};
    Item[] panes = {Items.STAINED_GLASS_PANE.white(), Items.STAINED_GLASS_PANE.black(), Items.STAINED_GLASS_PANE.red(), Items.STAINED_GLASS_PANE.blue()};
    var recipes = new ArrayList<UltsCraftRecipe>();
    var dye = Ingredient.of(Items.DYE.white());
    recipes.add(new UltsCraftRecipe(false, "dye", List.of(Ingredient.of(Items.BONE_MEAL)), item(Items.DYE.white()), 1));
    recipes.add(new UltsCraftRecipe(false, "panes", java.util.Collections.nCopies(6, Ingredient.of(Items.GLASS)), item(Items.GLASS_PANE), 16));
    for (int index = 0; index < glasses.length; index++) {
      var glassSlots = new ArrayList<>(java.util.Collections.nCopies(8, Ingredient.of(Items.GLASS))); glassSlots.add(dye);
      var paneSlots = new ArrayList<>(java.util.Collections.nCopies(8, Ingredient.of(Items.GLASS_PANE))); paneSlots.add(dye);
      recipes.add(new UltsCraftRecipe(false, "glass" + index, glassSlots, item(glasses[index]), 8));
      recipes.add(new UltsCraftRecipe(false, "pane" + index, java.util.Collections.nCopies(6, Ingredient.of(glasses[index])), item(panes[index]), 16));
      recipes.add(new UltsCraftRecipe(false, "colour" + index, paneSlots, item(panes[index]), 8));
    }
    var random = new java.util.Random(20261006);
    for (int order = 0; order < 12; order++) {
      java.util.Collections.shuffle(recipes, random);
      try (var catalog = new UltsTestCatalog(recipes.toArray(UltsCraftRecipe[]::new))) {
        var pool = new UltsCraftPool(8);
        pool.add(item(Items.GLASS), 3_000_000_003L); pool.add(item(Items.BONE_MEAL), 3_000_000_003L);
        pool.add(item(Items.CRAFTING_TABLE), 1);
        int mark = pool.mark(); var resolver = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true);
        long count = UltsCraftResolver.UNKNOWN;
        for (int frame = 0; frame < 100 && count == UltsCraftResolver.UNKNOWN; frame++) count = resolver.capacity(item(panes[0]));
        assertEquals(8_000_000_000L, count, "recipe order " + order + " must not count pigments as glass");
        assertEquals(mark, pool.mark()); assertEquals(3_000_000_003L, pool.usableAmount(item(Items.GLASS)));
      }
    }
  }

  @Test void groupedRequirementsNeverSaturateAwayRequiredMaterials() {
    var slot = Ingredient.of(Items.WHEAT, Items.SUGAR);
    try (var catalog = new UltsTestCatalog(new UltsCraftRecipe(false, "huge", List.of(slot, slot), item(Items.STICK), 1))) {
      var pool = new UltsCraftPool(8);
      pool.add(item(Items.WHEAT), Long.MAX_VALUE); pool.add(item(Items.SUGAR), Long.MAX_VALUE);
      assertEquals(Long.MAX_VALUE, UltsCraftResolver.capacity(item(Items.STICK), pool, UltsCraftingMode.ALL, false));
      assertEquals(Long.MAX_VALUE, pool.amount(item(Items.WHEAT)));
      assertEquals(Long.MAX_VALUE, pool.amount(item(Items.SUGAR)));
    }
  }

  @Test void sharedWoodForChestsAndHooksMatchesAConstructivelyFeasibleBoundary() {
    var p = Ingredient.of(Items.SPRUCE_PLANKS);
    var s = Ingredient.of(Items.STICK);
    var iron = Ingredient.of(Items.IRON_INGOT);
    var stick = new UltsCraftRecipe(false, "sticks", List.of(p, p), item(Items.STICK), 4);
    var bamboo = new UltsCraftRecipe(false, "bamboo_sticks", java.util.Collections.nCopies(2, Ingredient.of(Items.BAMBOO)), item(Items.STICK), 1);
    var hook = new UltsCraftRecipe(false, "hooks", List.of(p, s, iron), item(Items.TRIPWIRE_HOOK), 2);
    var chest = new UltsCraftRecipe(false, "chests", java.util.Collections.nCopies(8, p), item(Items.CHEST), 1);
    var trapped = new UltsCraftRecipe(false, "trapped", List.of(Ingredient.of(Items.CHEST), Ingredient.of(Items.TRIPWIRE_HOOK)), item(Items.TRAPPED_CHEST), 1);
    try (var catalog = new UltsTestCatalog(stick, bamboo, hook, chest, trapped)) {
      var pool = new UltsCraftPool(8);
      pool.add(item(Items.SPRUCE_PLANKS), 360_000_084); pool.add(item(Items.BAMBOO), 30_000_007);
      pool.add(item(Items.IRON_INGOT), 30_000_007); pool.add(item(Items.CRAFTING_TABLE), 1);
      var resolver = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true);
      long count = UltsCraftResolver.UNKNOWN;
      for (int frame = 0; frame < 100 && count == UltsCraftResolver.UNKNOWN; frame++) count = resolver.capacity(item(Items.TRAPPED_CHEST));
      assertEquals(42_000_009, count, "1,500,003 plank-stick runs and 14,999,993 bamboo-stick runs cover the hooks, leaving enough planks for all chests");
    }
    var allPlanks = Ingredient.of(Items.SPRUCE_PLANKS, Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.BAMBOO_PLANKS);
    var recipes = new ArrayList<UltsCraftRecipe>();
    Item[][] families = {{Items.SPRUCE_LOG, Items.SPRUCE_WOOD, Items.SPRUCE_PLANKS}, {Items.OAK_LOG, Items.OAK_WOOD, Items.OAK_PLANKS}, {Items.BIRCH_LOG, Items.BIRCH_WOOD, Items.BIRCH_PLANKS}};
    for (var family : families) {
      recipes.add(new UltsCraftRecipe(false, "wood_" + family[0], java.util.Collections.nCopies(4, Ingredient.of(family[0])), item(family[1]), 3));
      recipes.add(new UltsCraftRecipe(false, "planks_" + family[0], List.of(Ingredient.of(family[0], family[1])), item(family[2]), 4));
    }
    recipes.add(new UltsCraftRecipe(false, "bamboo_block", java.util.Collections.nCopies(9, Ingredient.of(Items.BAMBOO)), item(Items.BAMBOO_BLOCK), 1));
    recipes.add(new UltsCraftRecipe(false, "bamboo_planks", List.of(Ingredient.of(Items.BAMBOO_BLOCK)), item(Items.BAMBOO_PLANKS), 2));
    recipes.add(new UltsCraftRecipe(false, "sticks", List.of(allPlanks, allPlanks), item(Items.STICK), 4));
    recipes.add(bamboo);
    recipes.add(new UltsCraftRecipe(false, "hooks", List.of(allPlanks, s, iron), item(Items.TRIPWIRE_HOOK), 2));
    recipes.add(new UltsCraftRecipe(false, "chests", java.util.Collections.nCopies(8, allPlanks), item(Items.CHEST), 1)); recipes.add(trapped);
    recipes.add(new UltsCraftRecipe(false, "axe", List.of(allPlanks, allPlanks, allPlanks, s, s), item(Items.WOODEN_AXE), 1));
    try (var catalog = new UltsTestCatalog(recipes.toArray(UltsCraftRecipe[]::new))) {
      var pool = new UltsCraftPool(8);
      for (var family : families) pool.add(item(family[0]), 30_000_007);
      pool.add(item(Items.BAMBOO), 30_000_007); pool.add(item(Items.IRON_INGOT), 30_000_007); pool.add(item(Items.CRAFTING_TABLE), 1);
      var resolver = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true);
      long count = UltsCraftResolver.UNKNOWN;
      for (int frame = 0; frame < 100 && count == UltsCraftResolver.UNKNOWN; frame++) count = resolver.capacity(item(Items.TRAPPED_CHEST));
      assertEquals(42_000_009, count, "making the same planks from three wood families must preserve the feasible boundary");
      var billions = new UltsCraftPool(8);
      for (var family : families) billions.add(item(family[0]), 3_000_000_003L);
      billions.add(item(Items.BAMBOO), 3_000_000_003L); billions.add(item(Items.CRAFTING_TABLE), 1);
      var large = UltsCraftResolver.of(billions, UltsCraftingMode.ALL, true);
      count = UltsCraftResolver.UNKNOWN;
      for (int frame = 0; frame < 100 && count == UltsCraftResolver.UNKNOWN; frame++) count = large.capacity(item(Items.WOODEN_AXE));
      assertEquals(9_187_500_008L, count, "stick batch rounding removes the last fractional-resource candidate");
      assertNull(large.plan(item(Items.WOODEN_AXE), count + 1), "the integer boundary cannot be bypassed during withdrawal");
      assertNotNull(large.plan(item(Items.WOODEN_AXE), count), "the exact huge capacity has a concrete executable plan");
    }
  }
}
