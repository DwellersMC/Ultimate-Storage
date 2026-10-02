package com.flwolfy.ults;

import static org.junit.jupiter.api.Assertions.*;
import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;

import com.flwolfy.ults.crafting.*;
import com.flwolfy.ults.data.config.*;
import com.flwolfy.ults.data.state.*;
import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("acceptance")
class UltsRuntimeTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }

  @Test void stockCountsSaturateAcrossStoredRows() {
    var target = stack(Items.STONE);
    assertEquals(Long.MAX_VALUE, UltsRuntime.storedAmount(target, List.of(
        new UltsStoredView(target, Long.MAX_VALUE, false), new UltsStoredView(target, 64L, false))));
  }

  @Test void packingPreviewUsesSharedIngredientsAndInvalidatesOnContentsAndReload() throws Exception {
    var defaults = UltsConfigData.DEFAULT;
    var config = new UltsConfigData(defaults.general(), new UltsConfigData.Input(
        2, 0, 2, List.of(), UltsCraftingMode.ALL, false, true, 36, 64), defaults.special());
    var contents = stack(Items.STICK);
    contents.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64);
    var contentRecipe = new UltsCraftRecipe(false, "test:sticks", List.of(
        Ingredient.of(Items.WHEAT)), contents, 1728);
    var boxRecipe = new UltsCraftRecipe(false, "test:box", List.of(
        Ingredient.of(Items.WHEAT)), stack(Items.SHULKER_BOX), 1);
    try (var active = new UltsTestConfig(config); var catalog = new UltsTestCatalog(contentRecipe, boxRecipe)) {
      var state = new UltsState();
      state.deposit(stack(Items.WHEAT));
      state.deposit(stack(Items.CRAFTING_TABLE));
      long[] tick = {1};
      var runtime = new UltsRuntime(null, state, () -> tick[0]);
      assertFalse(runtime.canPack(contents, state.items()), "one ingredient cannot supply both goals");
      state.deposit(stack(Items.WHEAT));
      tick[0]++;
      assertTrue(runtime.canPack(contents, state.items()));
      try (var removed = new UltsTestCatalog(contentRecipe)) {
        runtime.reload(() -> {});
        tick[0]++;
        assertFalse(runtime.canPack(contents, state.items()), "reload must discard packing answers");
      }
      assertEquals(2L, UltsRuntime.storedAmount(stack(Items.WHEAT), state.items()), "preview never spends stock");
    }
  }

  @Test void reloadRecomputesCraftingWithIdenticalContentsAndMode() throws Exception {
    var defaults = UltsConfigData.DEFAULT;
    var config = new UltsConfigData(defaults.general(), new UltsConfigData.Input(
        2, 0, 2, List.of(), UltsCraftingMode.ALL, false, true, 36, 64), defaults.special());
    var recipe = new UltsCraftRecipe(false, "test:planks", List.of(
        Ingredient.of(Items.OAK_LOG)), stack(Items.OAK_PLANKS), 4);
    var changed = new UltsCraftRecipe(false, "test:planks", recipe.ingredients(), recipe.result(), 2);
    try (var active = new UltsTestConfig(config); var initial = new UltsTestCatalog(recipe)) {
      UltsState state = new UltsState();
      state.deposit(stack(Items.OAK_LOG));
      state.deposit(stack(Items.CRAFTING_TABLE));
      UltsRuntime runtime = new UltsRuntime(null, state, () -> 1L);
      var stock = state.items();
      assertEquals(4L, runtime.craftableFresh(stack(Items.OAK_PLANKS), stock));
      long revision = state.revision();
      try (var updated = new UltsTestCatalog(changed)) {
        runtime.reload(() -> {});
        assertTrue(state.revision() > revision);
        assertEquals(2L, runtime.craftableFresh(stack(Items.OAK_PLANKS), stock));
        assertEquals(2L, runtime.craftableWithoutStation(stack(Items.OAK_PLANKS), stock));
        assertEquals(2L, runtime.withdrawalPlan(stack(Items.OAK_PLANKS), 2, false).craftItems());
      }
      try (var removed = new UltsTestCatalog()) {
        runtime.reload(() -> {});
        assertEquals(0L, runtime.craftableFresh(stack(Items.OAK_PLANKS), stock));
        assertFalse(runtime.craftablePending());
      }
    }
  }
}
