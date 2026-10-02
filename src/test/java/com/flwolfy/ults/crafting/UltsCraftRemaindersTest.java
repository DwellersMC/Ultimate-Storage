package com.flwolfy.ults.crafting;

import static org.junit.jupiter.api.Assertions.*;
import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;

import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.state.*;
import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("acceptance")
class UltsCraftRemaindersTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }

  private UltsCraftRecipe cake() {
    return new UltsCraftRecipe(false, "minecraft:cake", List.of(
        Ingredient.of(Items.MILK_BUCKET), Ingredient.of(Items.MILK_BUCKET), Ingredient.of(Items.MILK_BUCKET),
        Ingredient.of(Items.SUGAR), Ingredient.of(Items.EGG), Ingredient.of(Items.SUGAR),
        Ingredient.of(Items.WHEAT), Ingredient.of(Items.WHEAT), Ingredient.of(Items.WHEAT)), stack(Items.CAKE), 1);
  }

  private UltsCraftPool cakePool(int cakes) {
    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.MILK_BUCKET), cakes * 3L);
    pool.add(stack(Items.SUGAR), cakes * 2L);
    pool.add(stack(Items.EGG), cakes);
    pool.add(stack(Items.WHEAT), cakes * 3L);
    pool.add(stack(Items.CRAFTING_TABLE), 1);
    return pool;
  }

  @Test void planningAndExecutionBothReturnTheVanillaBuckets() {
    assertTrue(Items.MILK_BUCKET.getCraftingRemainder().create().is(Items.BUCKET));
    try (var catalog = new UltsTestCatalog(cake())) {
      UltsCraftPool before = cakePool(2);
      UltsCraftPool planned = before.copy();
      var plan = UltsWithdrawalPlanner.plan(planned, stack(Items.CAKE), 2, false, UltsCraftingMode.ALL);
      assertTrue(plan.available());
      assertEquals(6L, planned.amount(stack(Items.BUCKET)));
      assertTrue(UltsWithdrawalPlanner.run(before, plan, stack(Items.CAKE), 2, false));
      assertEquals(6L, before.amount(stack(Items.BUCKET)));
      assertEquals(0L, before.amount(stack(Items.MILK_BUCKET)));
      assertEquals(1L, before.amount(stack(Items.CRAFTING_TABLE)));
    }
  }

  @Test void voidWithdrawalKeepsContainersInTheStorage() {
    try (var catalog = new UltsTestCatalog(cake())) {
      UltsState state = new UltsState();
      for (UltsStoredView row : cakePool(1).views()) {
        state.deposit(row.template().copyWithCount((int) row.amount()));
      }
      var plan = state.withdrawalPlan(stack(Items.CAKE), 1, false, UltsCraftingMode.ALL);
      assertEquals(1, state.takePlanned(plan, stack(Items.CAKE), 1, false).size());
      assertEquals(3L, storedAmount(state, Items.BUCKET));
      assertEquals(0L, storedAmount(state, Items.MILK_BUCKET));
    }
  }

  private long storedAmount(UltsState state, net.minecraft.world.item.Item item) {
    return state.items().stream().filter(row -> row.template().is(item)).mapToLong(UltsStoredView::amount).sum();
  }

  @Test void alternativeIngredientReturnsOnlyContainersActuallyConsumed() {
    var recipe = new UltsCraftRecipe(false, "test:mixed", List.of(
        Ingredient.of(Items.SUGAR, Items.HONEY_BOTTLE)), stack(Items.STICK), 1);
    try (var catalog = new UltsTestCatalog(recipe)) {
      var pool = new UltsCraftPool(4);
      pool.add(stack(Items.SUGAR), 1);
      pool.add(stack(Items.HONEY_BOTTLE), 2);
      pool.add(stack(Items.CRAFTING_TABLE), 1);
      var plan = UltsWithdrawalPlanner.plan(pool.copy(), stack(Items.STICK), 3, false, UltsCraftingMode.ALL);
      assertTrue(plan.available());
      assertTrue(UltsWithdrawalPlanner.run(pool, plan, stack(Items.STICK), 3, false));
      assertEquals(2L, pool.amount(stack(Items.GLASS_BOTTLE)));
    }
  }

  @Test void aReturnedBucketCannotPayAnotherSlotOfTheSameRun() {
    var recipe = new UltsCraftRecipe(false, "test:simultaneous", List.of(
        Ingredient.of(Items.MILK_BUCKET), Ingredient.of(Items.BUCKET)), stack(Items.STICK), 1);
    var pool = new UltsCraftPool(4);
    pool.add(stack(Items.MILK_BUCKET), 1);
    var plan = new UltsWithdrawalPlan(true, "", 0, 1, 0, 0, 0, 0,
        List.of(stack(Items.STICK)), 1, List.of(new UltsCraftStep(recipe, 1)));
    assertFalse(UltsWithdrawalPlanner.run(pool, plan, stack(Items.STICK), 1, false));
    assertEquals(1L, pool.amount(stack(Items.MILK_BUCKET)));
    assertEquals(0L, pool.amount(stack(Items.BUCKET)));
  }

  @Test void lateFailureRollsBackTheReturnedContainersAsWell() {
    var recipe = new UltsCraftRecipe(false, "test:milk", List.of(Ingredient.of(Items.MILK_BUCKET)), stack(Items.STICK), 1);
    var pool = new UltsCraftPool(4);
    pool.add(stack(Items.MILK_BUCKET), 1);
    var plan = new UltsWithdrawalPlan(true, "", 0, 2, 0, 0, 0, 0,
        List.of(stack(Items.STICK)), 1, List.of(new UltsCraftStep(recipe, 1)));
    assertFalse(UltsWithdrawalPlanner.run(pool, plan, stack(Items.STICK), 2, false));
    assertEquals(1L, pool.amount(stack(Items.MILK_BUCKET)));
    assertEquals(0L, pool.amount(stack(Items.BUCKET)));
    assertEquals(0L, pool.amount(stack(Items.STICK)));
  }

  @Test void stonecuttingDoesNotApplyCraftingTableRemainders() {
    var recipe = new UltsCraftRecipe(true, "test:stonecut", List.of(Ingredient.of(Items.MILK_BUCKET)), stack(Items.STICK), 1);
    try (var catalog = new UltsTestCatalog(recipe)) {
      var pool = new UltsCraftPool(4);
      pool.add(stack(Items.MILK_BUCKET), 1);
      pool.add(stack(Items.STONECUTTER), 1);
      var plan = UltsWithdrawalPlanner.plan(pool.copy(), stack(Items.STICK), 1, false, UltsCraftingMode.ALL);
      assertTrue(plan.available());
      assertTrue(UltsWithdrawalPlanner.run(pool, plan, stack(Items.STICK), 1, false));
      assertEquals(0L, pool.amount(stack(Items.BUCKET)));
    }
  }
}
