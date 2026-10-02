package com.flwolfy.ults.crafting;

import static org.junit.jupiter.api.Assertions.*;
import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;

import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.state.UltsWithdrawalPlanner;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.*;

@Tag("acceptance")
class UltsCraftCombinationsTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }
  private static ItemStack target(Item item) {
    ItemStack result = stack(item);
    result.set(DataComponents.MAX_STACK_SIZE, 64);
    return result;
  }
  private static UltsCraftRecipe recipe(String id, Item result, int count, Ingredient... slots) {
    return new UltsCraftRecipe(false, id, List.of(slots), target(result), count);
  }
  private static UltsCraftRecipe[] sticks() {
    Ingredient wood = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS);
    return new UltsCraftRecipe[]{
        recipe("oak", Items.OAK_PLANKS, 4, Ingredient.of(Items.OAK_LOG)),
        recipe("birch", Items.BIRCH_PLANKS, 4, Ingredient.of(Items.BIRCH_LOG)),
        recipe("spruce", Items.SPRUCE_PLANKS, 4, Ingredient.of(Items.SPRUCE_LOG)),
        recipe("wood_sticks", Items.STICK, 4, wood,
            Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS)),
        recipe("oak_wood", Items.OAK_WOOD, 3, Ingredient.of(Items.OAK_LOG), Ingredient.of(Items.OAK_LOG),
            Ingredient.of(Items.OAK_LOG), Ingredient.of(Items.OAK_LOG)),
        recipe("wood_planks", Items.OAK_PLANKS, 4, Ingredient.of(Items.OAK_WOOD)),
        recipe("bamboo_sticks", Items.STICK, 1, Ingredient.of(Items.BAMBOO), Ingredient.of(Items.BAMBOO))};
  }
  private static UltsCraftPool pool(Item... materials) {
    var pool = new UltsCraftPool(8);
    for (Item material : materials) pool.add(target(material), 1);
    pool.add(target(Items.CRAFTING_TABLE), 1);
    return pool;
  }
  private static void assertWithdrawal(UltsCraftPool before, Item item, int quantity) {
    var planned = before.copy();
    var plan = UltsWithdrawalPlanner.plan(planned, target(item), quantity, false, UltsCraftingMode.ALL);
    assertTrue(plan.available(), "request " + quantity);
    var executed = before.copy();
    assertTrue(UltsWithdrawalPlanner.run(executed, plan, target(item), quantity, false));
    assertEquals(quantity, plan.outputs().stream().mapToInt(ItemStack::getCount).sum());
    assertEquals(quantity, planned.take(target(item), quantity));
    for (var row : planned.views()) assertEquals(row.amount(), executed.amount(row.template()));
    for (var row : executed.views()) assertEquals(row.amount(), planned.amount(row.template()));
  }

  @Test void expandingChainIsNotBoundedByTheInitialItemCount() {
    try (var catalog = new UltsTestCatalog(sticks())) {
      var pool = pool();
      pool.add(target(Items.OAK_LOG), 64);
      assertEquals(512, UltsCraftResolver.capacity(target(Items.STICK), pool, UltsCraftingMode.ALL, true));
      assertWithdrawal(pool, Items.STICK, 512);
    }
  }
  @Test void differentIntermediateMaterialsContributeToTheSameSlots() {
    try (var catalog = new UltsTestCatalog(sticks())) {
      var pool = pool(Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG);
      assertEquals(24, UltsCraftResolver.capacity(target(Items.STICK), pool, UltsCraftingMode.ALL, true));
      assertWithdrawal(pool, Items.STICK, 24);
    }
  }
  @Test void differentRootRecipesContributeToOneRequest() {
    try (var catalog = new UltsTestCatalog(sticks())) {
      var pool = pool(Items.OAK_PLANKS, Items.OAK_PLANKS, Items.BAMBOO, Items.BAMBOO);
      assertEquals(5, UltsCraftResolver.capacity(target(Items.STICK), pool, UltsCraftingMode.ALL, true));
      assertWithdrawal(pool, Items.STICK, 5);
    }
  }
  @Test void anEarlyRouteIsRechosenWhenItConsumesALaterSlotsOnlyMaterial() {
    try (var catalog = new UltsTestCatalog(
        recipe("root", Items.TORCH, 1, Ingredient.of(Items.STICK), Ingredient.of(Items.SUGAR)),
        recipe("wrong", Items.STICK, 1, Ingredient.of(Items.WHEAT)),
        recipe("right", Items.STICK, 1, Ingredient.of(Items.OAK_PLANKS)),
        recipe("sugar", Items.SUGAR, 1, Ingredient.of(Items.WHEAT)))) {
      var pool = pool(Items.WHEAT, Items.OAK_PLANKS);
      assertEquals(1, UltsCraftResolver.capacity(target(Items.TORCH), pool, UltsCraftingMode.ALL, true));
      assertWithdrawal(pool, Items.TORCH, 1);
    }
  }
  @Test void overlappingHeldSlotsBacktrackAndReplayTheDecidedAllocation() {
    try (var catalog = new UltsTestCatalog(recipe("overlap", Items.TORCH, 1,
        Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS), Ingredient.of(Items.OAK_PLANKS)))) {
      assertWithdrawal(pool(Items.OAK_PLANKS, Items.BIRCH_PLANKS), Items.TORCH, 1);
    }
  }
  @Test void childRunsDoNotConsumeTheParentsReservedMaterialWhenReplayed() {
    try (var catalog = new UltsTestCatalog(
        recipe("parent", Items.TORCH, 1, Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.STICK)),
        recipe("child", Items.STICK, 1, Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS)))) {
      assertWithdrawal(pool(Items.OAK_PLANKS, Items.BIRCH_PLANKS), Items.TORCH, 1);
    }
  }
  @Test void sharedResourcesAreNotDoubleCountedAcrossRootRecipes() {
    try (var catalog = new UltsTestCatalog(
        recipe("rich", Items.STICK, 4, Ingredient.of(Items.WHEAT), Ingredient.of(Items.SUGAR)),
        recipe("simple", Items.STICK, 3, Ingredient.of(Items.WHEAT)))) {
      var pool = pool(Items.WHEAT, Items.WHEAT, Items.SUGAR);
      assertEquals(7, UltsCraftResolver.capacity(target(Items.STICK), pool, UltsCraftingMode.ALL, true));
      assertWithdrawal(pool, Items.STICK, 7);
      assertNull(UltsCraftResolver.plan(target(Items.STICK), 8, pool.copy(), UltsCraftingMode.ALL, true));
    }
  }

  @Test void expandingHugeCountsSaturatesWithoutWrappingOrChangingTheOriginalPool() {
    try (var catalog = new UltsTestCatalog(recipe("planks", Items.OAK_PLANKS, 4, Ingredient.of(Items.OAK_LOG)))) {
      var pool = pool();
      pool.add(target(Items.OAK_LOG), Long.MAX_VALUE);
      int mark = pool.mark();
      assertEquals(Long.MAX_VALUE, pool.total());
      assertEquals(Long.MAX_VALUE, UltsCraftResolver.capacity(target(Items.OAK_PLANKS), pool, UltsCraftingMode.ALL, true));
      assertEquals(Long.MAX_VALUE, pool.amount(target(Items.OAK_LOG)));
      assertEquals(mark, pool.mark());
    }
  }

  @Test void returnsCanReplenishAnIntermediateBetweenSeparateBatches() {
    try (var catalog = new UltsTestCatalog(
        recipe("use_milk", Items.STICK, 1, Ingredient.of(Items.MILK_BUCKET)),
        recipe("refill", Items.MILK_BUCKET, 1, Ingredient.of(Items.BUCKET), Ingredient.of(Items.WHEAT)))) {
      var pool = pool(Items.MILK_BUCKET, Items.WHEAT, Items.WHEAT);
      assertEquals(3, UltsCraftResolver.capacity(target(Items.STICK), pool, UltsCraftingMode.ALL, true));
      assertWithdrawal(pool, Items.STICK, 3);
    }
  }

  @Test void craftingPackingBoxesKeepsTheItemsPromisedForTheirContentsReserved() {
    var boxRecipe = new UltsCraftRecipe(false, "box", List.of(Ingredient.of(Items.STICK, Items.WHEAT)),
        stack(Items.SHULKER_BOX), 1);
    try (var catalog = new UltsTestCatalog(boxRecipe)) {
      var pool = pool(Items.WHEAT);
      pool.add(target(Items.STICK), 1728);
      var plan = UltsWithdrawalPlanner.plan(pool.copy(), target(Items.STICK), 1, true, UltsCraftingMode.ALL);
      assertTrue(plan.available());
      assertTrue(UltsWithdrawalPlanner.run(pool, plan, target(Items.STICK), 1, true));
      assertEquals(0, pool.amount(target(Items.STICK)));
      assertEquals(0, pool.amount(target(Items.WHEAT)));
      assertEquals(1728, plan.outputs().getFirst().get(DataComponents.CONTAINER)
          .allItemsCopyStream().mapToInt(ItemStack::getCount).sum());
    }
  }

  /** Independent forward exploration executes one operation at a time, with all slot choices.
   * It shares no planning, ordering, batching, bound or pool implementation with the resolver. */
  @Test void smallGraphsAgreeWithExhaustiveForwardExploration() {
    Item[] universe = {Items.WHEAT, Items.SUGAR, Items.EGG, Items.PAPER, Items.STICK, Items.TORCH};
    var recipes = new UltsCraftRecipe[]{
        recipe("paper_a", Items.PAPER, 2, Ingredient.of(Items.WHEAT)),
        recipe("paper_b", Items.PAPER, 1, Ingredient.of(Items.SUGAR)),
        recipe("sticks_a", Items.STICK, 3, Ingredient.of(Items.WHEAT), Ingredient.of(Items.EGG)),
        recipe("sticks_b", Items.STICK, 1, Ingredient.of(Items.PAPER)),
        recipe("torch", Items.TORCH, 1, Ingredient.of(Items.PAPER, Items.STICK), Ingredient.of(Items.PAPER))};
    try (var catalog = new UltsTestCatalog(recipes)) {
      for (int wheat = 0; wheat <= 2; wheat++) {
        for (int sugar = 0; sugar <= 2; sugar++) {
          for (int egg = 0; egg <= 2; egg++) {
            int[] start = {wheat, sugar, egg, 0, 0, 0};
            int expected = exhaustive(start, universe, recipes);
            var before = pool();
            for (int i = 0; i < start.length; i++) before.add(target(universe[i]), start[i]);
            var resolver = UltsCraftResolver.of(before, UltsCraftingMode.ALL, true);
            assertEquals(expected, resolver.capacity(target(Items.TORCH)), Arrays.toString(start));
            assertFalse(resolver.ranOut());
            for (int requested = 1; requested <= expected; requested++) {
              assertWithdrawal(before, Items.TORCH, requested);
            }
            int mark = before.mark();
            assertNull(resolver.plan(target(Items.TORCH), expected + 1L));
            assertEquals(mark, before.mark());
          }
        }
      }
    }
  }
  private static int exhaustive(int[] initial, Item[] universe, UltsCraftRecipe[] recipes) {
    Set<String> seen = new HashSet<>();
    ArrayDeque<int[]> queue = new ArrayDeque<>();
    queue.add(initial.clone());
    seen.add(Arrays.toString(initial));
    int best = 0;
    while (!queue.isEmpty()) {
      int[] state = queue.remove();
      best = Math.max(best, state[5]);
      for (var recipe : recipes) forward(recipe, 0, state.clone(), universe, seen, queue);
      assertTrue(seen.size() < 100_000, "oracle graph unexpectedly large");
    }
    return best;
  }
  private static void forward(UltsCraftRecipe recipe, int slot, int[] state, Item[] universe,
      Set<String> seen, ArrayDeque<int[]> queue) {
    if (slot == recipe.ingredients().size()) {
      for (int i = 0; i < universe.length; i++) {
        if (recipe.result().is(universe[i])) state[i] += recipe.outputCount();
      }
      if (seen.add(Arrays.toString(state))) queue.add(state);
      return;
    }
    for (int i = 0; i < universe.length; i++) {
      if (state[i] > 0 && recipe.ingredients().get(slot).test(target(universe[i]))) {
        int[] remaining = state.clone();
        remaining[i]--;
        forward(recipe, slot + 1, remaining, universe, seen, queue);
      }
    }
  }
}
