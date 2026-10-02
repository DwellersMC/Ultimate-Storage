package com.flwolfy.ults.crafting;

import static org.junit.jupiter.api.Assertions.*;
import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.state.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.*;

@Tag("acceptance")
class UltsWithdrawalPlannerTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }
  static ItemStack sized(Item item) {
    var result = stack(item);
    result.set(DataComponents.MAX_STACK_SIZE, 64);
    return result;
  }
  static UltsCraftRecipe recipe(String id, Item result, int count, Ingredient... slots) {
    return new UltsCraftRecipe(false, id, List.of(slots), sized(result), count);
  }
  static UltsCraftRecipe box(String id, int count, Ingredient... slots) {
    return new UltsCraftRecipe(false, id, List.of(slots), stack(Items.SHULKER_BOX), count);
  }
  static UltsCraftRecipe[] vanilla() {
    var planks = new Ingredient[8];
    Arrays.fill(planks, Ingredient.of(Items.OAK_PLANKS));
    return new UltsCraftRecipe[]{
        recipe("planks", Items.OAK_PLANKS, 4, Ingredient.of(Items.OAK_LOG)),
        recipe("sticks", Items.STICK, 4, Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS)),
        recipe("chest", Items.CHEST, 1, planks),
        box("box", 1, Ingredient.of(Items.CHEST), Ingredient.of(Items.SHULKER_SHELL), Ingredient.of(Items.SHULKER_SHELL))};
  }
  static UltsCraftPool pool() {
    var pool = new UltsCraftPool(8);
    pool.add(sized(Items.CRAFTING_TABLE), 1);
    return pool;
  }
  static UltsWithdrawalPlan packed(UltsCraftPool before, int quantity) {
    var planned = before.copy();
    var plan = UltsWithdrawalPlanner.plan(planned, sized(Items.STICK), quantity, true, UltsCraftingMode.ALL);
    assertTrue(plan.available(), plan.problem());
    var executed = before.copy();
    assertTrue(UltsWithdrawalPlanner.run(executed, plan, sized(Items.STICK), quantity, true));
    assertEquals(quantity, plan.outputs().size());
    for (var output : plan.outputs()) assertEquals(1728, output.get(DataComponents.CONTAINER)
        .allItemsCopyStream().mapToInt(ItemStack::getCount).sum());
    assertEquals(1728L * quantity, planned.take(sized(Items.STICK), 1728L * quantity));
    assertEquals(quantity, planned.takePackable(quantity));
    sameContents(planned, executed);
    return plan;
  }
  static void sameContents(UltsCraftPool left, UltsCraftPool right) {
    for (var view : left.views()) assertEquals(view.amount(), right.amount(view.template()));
    for (var view : right.views()) assertEquals(view.amount(), left.amount(view.template()));
  }
  @Test void expiredPreviewIsPendingAndLeavesStockUntouched() {
    try (var catalog = new UltsTestCatalog(vanilla())) {
      var original = pool();
      original.add(sized(Items.OAK_LOG), 218);
      original.add(sized(Items.SHULKER_SHELL), 2);
      var preview = original.copy();
      var plan = UltsWithdrawalPlanner.plan(preview, sized(Items.STICK), 1, true,
          UltsCraftingMode.ALL, System.nanoTime() - 1);
      assertFalse(plan.available());
      assertEquals("pending", plan.problem());
      sameContents(original, preview);
    }
  }

  @Test void addingShellsDoesNotMakeExistingColoredPackagingUnavailable() {
    try (var catalog = new UltsTestCatalog(vanilla())) {
      var pool = pool();
      pool.add(sized(Items.OAK_LOG), 216);
      pool.add(stack(Items.DYED_SHULKER_BOX.blue()), 1);
      packed(pool, 1);
      pool.add(sized(Items.SHULKER_SHELL), 2);
      var plan = packed(pool, 1);
      assertTrue(plan.outputs().getFirst().is(Items.DYED_SHULKER_BOX.blue()));
      assertEquals(1, plan.boxStored());
      assertEquals(0, plan.boxCrafted());
    }
  }
  @Test void plainCraftedPackagingStillWinsWhenItLeavesEnoughForTheContents() {
    try (var catalog = new UltsTestCatalog(vanilla())) {
      var pool = pool();
      pool.add(sized(Items.OAK_LOG), 218);
      pool.add(sized(Items.SHULKER_SHELL), 2);
      pool.add(stack(Items.DYED_SHULKER_BOX.blue()), 1);
      var plan = packed(pool, 1);
      assertTrue(plan.outputs().getFirst().is(Items.SHULKER_BOX));
      assertEquals(1, plan.boxCrafted());
    }
  }
  @Test void boxIngredientChoiceBacktracksWhenContentsNeedItsFirstChoice() {
    try (var catalog = new UltsTestCatalog(
        box("box", 1, Ingredient.of(Items.WHEAT, Items.SUGAR)),
        recipe("sticks", Items.STICK, 64, Ingredient.of(Items.WHEAT)))) {
      var pool = pool();
      pool.add(sized(Items.WHEAT), 27);
      pool.add(sized(Items.SUGAR), 1);
      packed(pool, 1);
    }
  }
  @Test void boxRecipeChoiceBacktracksWhenContentsNeedItsFirstRoutesMaterial() {
    try (var catalog = new UltsTestCatalog(
        box("first", 1, Ingredient.of(Items.WHEAT)),
        box("second", 1, Ingredient.of(Items.SUGAR)),
        recipe("sticks", Items.STICK, 64, Ingredient.of(Items.WHEAT)))) {
      var pool = pool();
      pool.add(sized(Items.WHEAT), 27);
      pool.add(sized(Items.SUGAR), 1);
      packed(pool, 1);
    }
  }
  @Test void surplusBoxOutputsRemainAvailableToLaterGoals() {
    try (var catalog = new UltsTestCatalog(
        box("box", 4, Ingredient.of(Items.SUGAR)),
        recipe("sticks", Items.STICK, 1728, Ingredient.of(Items.SHULKER_BOX), Ingredient.of(Items.WHEAT)))) {
      var pool = pool();
      pool.add(sized(Items.SUGAR), 1);
      pool.add(sized(Items.WHEAT), 2);
      packed(pool, 2);
    }
  }
  @Test void failedJointPlanningRestoresMaterialsAndIncomingReservations() {
    try (var catalog = new UltsTestCatalog(
        box("box", 1, Ingredient.of(Items.WHEAT)),
        recipe("sticks", Items.STICK, 64, Ingredient.of(Items.WHEAT)))) {
      var pool = pool();
      pool.add(sized(Items.WHEAT), 27);
      pool.reserve(sized(Items.CRAFTING_TABLE), 1);
      var before = pool.copy();
      int mark = pool.mark();
      var plan = UltsWithdrawalPlanner.plan(pool, sized(Items.STICK), 1, true, UltsCraftingMode.ALL);
      assertFalse(plan.available());
      sameContents(before, pool);
      assertEquals(mark, pool.mark());
      assertEquals(before.keepState(), pool.keepState());
    }
  }
  @Test void packingUsesTheActualExistingBoxComponents() {
    var pool = pool();
    var box = stack(Items.SHULKER_BOX);
    box.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("owner supplied lore"))));
    pool.add(box, 1);
    pool.add(sized(Items.STICK), 1728);
    var plan = packed(pool, 1);
    assertEquals(box.get(DataComponents.LORE), plan.outputs().getFirst().get(DataComponents.LORE));
    assertEquals(1, plan.boxStored());
    assertEquals(0, plan.boxCrafted());
  }
  @Test void expiredJointSearchDoesNotChangeThePileOrReservations() {
    var pool = pool();
    pool.add(sized(Items.WHEAT), 27);
    var before = pool.copy();
    var resolver = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true);
    assertNull(resolver.planTogether(List.of(new UltsCraftResolver.Goal(sized(Items.STICK), 1728)), () -> true, 0));
    assertTrue(resolver.ranOut());
    sameContents(before, pool);
    assertEquals(before.keepState(), pool.keepState());
  }
}
