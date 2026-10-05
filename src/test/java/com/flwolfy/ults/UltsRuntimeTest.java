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

  static void expireCraftingBudget(UltsRuntime runtime, long tick) throws Exception {
    var frame = UltsRuntime.class.getDeclaredField("craftableBudgetTick"); frame.setAccessible(true); frame.setLong(runtime, tick);
    var deadline = UltsRuntime.class.getDeclaredField("craftableDeadline"); deadline.setAccessible(true); deadline.setLong(runtime, Long.MIN_VALUE);
    var deferred = UltsRuntime.class.getDeclaredField("craftableDeferred"); deferred.setAccessible(true); deferred.setBoolean(runtime, false);
  }

  @Test void aCalculationStillRefreshesAfterMoreThanTwoHundredBusyTicks() throws Exception {
    var defaults = UltsConfigData.DEFAULT;
    var config = new UltsConfigData(defaults.general(), new UltsConfigData.Input(
        2, 0, 2, List.of(), UltsCraftingMode.ALL, true, true, 36, 64), defaults.special());
    var recipe = new UltsCraftRecipe(false, "test:planks", List.of(Ingredient.of(Items.OAK_LOG)), stack(Items.OAK_PLANKS), 4);
    try (var active = new UltsTestConfig(config); var catalog = new UltsTestCatalog(recipe)) {
      var state = new UltsState(); state.deposit(stack(Items.OAK_LOG).copyWithCount(40_000_003)); state.deposit(stack(Items.CRAFTING_TABLE));
      long[] tick = {1}; var runtime = new UltsRuntime(null, state, () -> tick[0]);
      for (int frame = 0; frame < 250; frame++) {
        tick[0]++; expireCraftingBudget(runtime, tick[0]);
        assertTrue(runtime.craftingAmount(stack(Items.OAK_PLANKS), state.items()).pending());
        assertTrue(runtime.craftablePending(), "the screen must keep refreshing at frame " + frame);
      }
      tick[0]++;
      var answer = runtime.craftingAmount(stack(Items.OAK_PLANKS), state.items());
      assertFalse(answer.pending()); assertEquals(160_000_012, answer.amount());
      assertEquals(40_000_003, UltsRuntime.storedAmount(stack(Items.OAK_LOG), state.items()));
    }
  }

  @Test void quantityStabilityIgnoresRowOrderAndEquivalentRowSplits() {
    var runtime = new UltsRuntime(null, new UltsState(), () -> 1);
    var stone = stack(Items.STONE);
    var dirt = stack(Items.DIRT);
    assertFalse(runtime.stockPending(List.of(new UltsStoredView(stone, 10, false),
        new UltsStoredView(dirt, 1, false))));
    assertFalse(runtime.stockPending(List.of(new UltsStoredView(dirt, 1, false),
        new UltsStoredView(stone, 5, false), new UltsStoredView(stone, 5, false))));
    assertTrue(runtime.stockPending(List.of(new UltsStoredView(stone, 9, false),
        new UltsStoredView(dirt, 1, false))));
    assertTrue(runtime.stockPending(stone, List.of(new UltsStoredView(stone, 9, false), new UltsStoredView(dirt, 1, false))));
    assertFalse(runtime.stockPending(dirt, List.of(new UltsStoredView(stone, 9, false), new UltsStoredView(dirt, 1, false))));
  }

  @Test void configuredIntervalsDetermineLocalAndRemoteQuietWindows() throws Exception {
    for (int interval = 1; interval <= UltsConfigData.MAX_DRAIN_INTERVAL; interval++) {
      var defaults = UltsConfigData.DEFAULT;
      var input = new UltsConfigData.Input(2, 0, interval, List.of(), UltsCraftingMode.DISABLED, true, true, 36, 64);
      for (var mode : UltsStorageMode.values()) {
        var config = new UltsConfigData(new UltsConfigData.General("en_us", UltsItemVisibility.AVAILABLE, mode), input, defaults.special());
        try (var active = new UltsTestConfig(config)) {
          var runtime = new UltsRuntime(null, new UltsState(), () -> 0);
          assertEquals(interval + (mode == UltsStorageMode.REMOTE ? 33L : 1L), runtime.stabilityQuietTicks());
          assertTrue(runtime.stabilityQuietTicks() > interval);
        }
      }
    }
  }

  @Test void existingPlanksDoNotBlockLogsUntilCraftingActuallyConsumesThem() throws Exception {
    var defaults = UltsConfigData.DEFAULT;
    var config = new UltsConfigData(defaults.general(), new UltsConfigData.Input(
        2, 0, 20, List.of(), UltsCraftingMode.ALL, true, true, 36, 100), defaults.special());
    var planks = stack(Items.OAK_PLANKS);
    planks.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64);
    var recipe = new UltsCraftRecipe(false, "test:planks", List.of(Ingredient.of(Items.OAK_LOG)), planks, 4);
    try (var active = new UltsTestConfig(config); var catalog = new UltsTestCatalog(recipe)) {
      var state = new UltsState();
      state.deposit(planks.copyWithCount(2000));
      state.deposit(stack(Items.OAK_LOG).copyWithCount(1000));
      state.deposit(stack(Items.STONE).copyWithCount(64));
      state.deposit(stack(Items.CRAFTING_TABLE));
      long[] tick = {1};
      var runtime = new UltsRuntime(null, state, () -> tick[0]);
      assertFalse(runtime.craftingAmount(stack(Items.OAK_LOG), state.items()).pending());
      assertFalse(runtime.craftingAmount(stack(Items.STONE), state.items()).pending());
      for (int batch = 0; batch < 20; batch++) {
        tick[0]++;
        var result = runtime.takeBatch(planks, 100, false, UltsCraftingMode.ALL, Long.MAX_VALUE);
        assertEquals(100, result.outputs().stream().mapToLong(s -> s.getCount()).sum());
        assertTrue(runtime.stockPending(planks, state.items()));
        assertFalse(runtime.craftingAmountFresh(stack(Items.OAK_LOG), state.items()).pending(), "stored prefix must not block logs");
        assertFalse(runtime.craftingAmount(stack(Items.STONE), state.items()).pending(), "unrelated rows retain their exact answers");
      }
      assertEquals(1000L, UltsRuntime.storedAmount(stack(Items.OAK_LOG), state.items()));
      tick[0]++;
      var crafted = runtime.takeBatch(planks, 100, false, UltsCraftingMode.ALL, Long.MAX_VALUE);
      assertEquals(100, crafted.outputs().stream().mapToLong(s -> s.getCount()).sum());
      assertTrue(runtime.craftingAmountFresh(stack(Items.OAK_LOG), state.items()).pending());
      assertFalse(runtime.craftingAmount(stack(Items.STONE), state.items()).pending());
      assertEquals(975L, UltsRuntime.storedAmount(stack(Items.OAK_LOG), state.items()));
      assertEquals(0L, UltsRuntime.storedAmount(planks, state.items()));
    }
  }

  @Test void componentVariantsAndBagTotalsUseTheStorageStackingRule() {
    var first = stack(Items.DIAMOND_SWORD);
    first.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("first"));
    var second = stack(Items.DIAMOND_SWORD);
    second.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("second"));
    long[] tick = {0};
    var runtime = new UltsRuntime(null, new UltsState(), () -> tick[0]);
    runtime.stockPending(List.of(new UltsStoredView(first, 3, true), new UltsStoredView(second, 2, true)));
    tick[0]++;
    var current = List.of(new UltsStoredView(first, 2, true), new UltsStoredView(second, 2, true));
    assertTrue(runtime.stockPending(first, current));
    assertFalse(runtime.stockPending(second, current));
    assertTrue(runtime.stockPending(Items.DIAMOND_SWORD, current));
    assertFalse(runtime.stockPending(Items.STONE, current));
  }

  @Test void plainStockChangesDoNotBlockSpecialBagsOfTheSameItem() {
    var plain = stack(Items.DIAMOND_SWORD);
    var named = plain.copy();
    named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("bag"));
    var runtime = new UltsRuntime(null, new UltsState(), () -> 1);
    runtime.stockPending(List.of(new UltsStoredView(plain, 2, false), new UltsStoredView(named, 2, true)));
    var current = List.of(new UltsStoredView(plain, 1, false), new UltsStoredView(named, 2, true));
    assertTrue(runtime.stockPending(plain, current));
    assertFalse(runtime.stockPending(named, current));
    assertFalse(runtime.stockPending(Items.DIAMOND_SWORD, current));
  }

  @Test void changesToNamedOrFilledBoxesDoNotBlockAvailablePackingBoxes() throws Exception {
    try (var active = new UltsTestConfig(UltsConfigData.DEFAULT)) {
      var plain = stack(Items.SHULKER_BOX);
      var named = plain.copy();
      named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("owner"));
      var filled = plain.copy();
      filled.set(net.minecraft.core.component.DataComponents.CONTAINER,
          net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(stack(Items.STONE))));
      var runtime = new UltsRuntime(null, new UltsState(), () -> 1);
      runtime.stockPending(List.of(new UltsStoredView(plain, 2, false), new UltsStoredView(named, 2, true), new UltsStoredView(filled, 2, true)));
      assertFalse(runtime.packagingPending(List.of(new UltsStoredView(plain, 2, false), new UltsStoredView(named, 1, true), new UltsStoredView(filled, 1, true))));
      assertTrue(runtime.packagingPending(List.of(new UltsStoredView(plain, 1, false), new UltsStoredView(named, 1, true), new UltsStoredView(filled, 1, true))));
    }
  }

  @Test void stockedOutputsStillShowAdditionalCraftingRatherThanStoredPlusCrafted() throws Exception {
    var defaults = UltsConfigData.DEFAULT;
    var config = new UltsConfigData(defaults.general(), new UltsConfigData.Input(
        2, 0, 2, List.of(), UltsCraftingMode.ALL, false, true, 36, 64), defaults.special());
    var recipe = new UltsCraftRecipe(false, "test:planks", List.of(
        Ingredient.of(Items.OAK_LOG)), stack(Items.OAK_PLANKS), 4);
    try (var active = new UltsTestConfig(config); var catalog = new UltsTestCatalog(recipe)) {
      var state = new UltsState();
      state.deposit(stack(Items.OAK_PLANKS).copyWithCount(100));
      state.deposit(stack(Items.OAK_LOG));
      state.deposit(stack(Items.CRAFTING_TABLE));
      long[] tick = {1};
      var runtime = new UltsRuntime(null, state, () -> tick[0]);
      var amount = runtime.craftingAmount(stack(Items.OAK_PLANKS), state.items());
      assertEquals(4L, amount.amount());
      assertFalse(amount.pending());
      assertFalse(amount.noStation());
      assertEquals(100L, UltsRuntime.storedAmount(stack(Items.OAK_PLANKS), state.items()));
      state.deposit(stack(Items.OAK_LOG));
      var shown = runtime.craftingAmount(stack(Items.OAK_PLANKS), state.items());
      assertTrue(shown.pending(), "a listing must mark its old answer as pending");
      var current = runtime.craftingAmountFresh(stack(Items.OAK_PLANKS), state.items());
      assertTrue(current.pending(), "a fresh click must not bypass the storage's stability window");
      tick[0] += runtime.stabilityQuietTicks();
      current = runtime.craftingAmountFresh(stack(Items.OAK_PLANKS), state.items());
      assertEquals(8L, current.amount(), "settled materials are included immediately");
      assertFalse(current.pending());
    }
  }

  @Test void partialCraftingBatchUsesWhatRemainsAfterAnotherRequest() throws Exception {
    var defaults = UltsConfigData.DEFAULT;
    var config = new UltsConfigData(defaults.general(), new UltsConfigData.Input(
        2, 0, 2, List.of(), UltsCraftingMode.ALL, false, true, 36, 64), defaults.special());
    var recipe = new UltsCraftRecipe(false, "test:planks", List.of(
        Ingredient.of(Items.OAK_LOG)), stack(Items.OAK_PLANKS), 4);
    try (var active = new UltsTestConfig(config); var catalog = new UltsTestCatalog(recipe)) {
      var state = new UltsState();
      state.deposit(stack(Items.OAK_LOG));
      state.deposit(stack(Items.CRAFTING_TABLE));
      var runtime = new UltsRuntime(null, state, () -> 1);
      var result = runtime.takeBatchUpTo(stack(Items.OAK_PLANKS), 8, false,
          UltsCraftingMode.ALL, Long.MAX_VALUE);
      assertFalse(result.pending());
      assertEquals(4L, result.outputs().stream().mapToLong(s -> s.getCount()).sum());
      assertEquals(0L, UltsRuntime.storedAmount(stack(Items.OAK_LOG), state.items()));
    }
  }

  @Test void anExpiredStreamBudgetDoesNotWithdrawOrClaimShortage() {
    var state = new UltsState();
    state.deposit(stack(Items.STONE).copyWithCount(10));
    var runtime = new UltsRuntime(null, state, () -> 1);
    var result = runtime.takeBatch(stack(Items.STONE), 10, false,
        UltsCraftingMode.DISABLED, System.nanoTime() - 1);
    assertTrue(result.pending());
    assertTrue(result.outputs().isEmpty());
    assertEquals(10L, UltsRuntime.storedAmount(stack(Items.STONE), state.items()));
  }

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
      assertEquals(4L, runtime.craftingAmount(stack(Items.OAK_PLANKS), stock).amount());
      long revision = state.revision();
      try (var updated = new UltsTestCatalog(changed)) {
        runtime.reload(() -> {});
        assertTrue(state.revision() > revision);
        assertEquals(2L, runtime.craftableFresh(stack(Items.OAK_PLANKS), stock));
        assertEquals(2L, runtime.craftingAmount(stack(Items.OAK_PLANKS), stock).amount());
        assertEquals(2L, runtime.craftableWithoutStation(stack(Items.OAK_PLANKS), stock));
        assertEquals(2L, runtime.withdrawalPlan(stack(Items.OAK_PLANKS), 2, false).craftItems());
      }
      try (var removed = new UltsTestCatalog()) {
        runtime.reload(() -> {});
        assertEquals(0L, runtime.craftableFresh(stack(Items.OAK_PLANKS), stock));
        assertEquals(0L, runtime.craftingAmount(stack(Items.OAK_PLANKS), stock).amount());
        assertFalse(runtime.craftablePending());
      }
    }
  }

  @Test void transitiveAndAlternativeMaterialsRefreshOnlyChangedCraftingAmounts() throws Exception {
    var defaults = UltsConfigData.DEFAULT;
    var config = new UltsConfigData(defaults.general(), new UltsConfigData.Input(
        2, 0, 2, List.of(), UltsCraftingMode.ALL, true, true, 36, 64), defaults.special());
    var planks = new UltsCraftRecipe(false, "test:planks", List.of(Ingredient.of(Items.OAK_LOG, Items.BIRCH_LOG)), stack(Items.OAK_PLANKS), 4);
    var sticks = new UltsCraftRecipe(false, "test:sticks", List.of(Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS)), stack(Items.STICK), 4);
    try (var active = new UltsTestConfig(config); var catalog = new UltsTestCatalog(planks, sticks)) {
      var state = new UltsState();
      state.deposit(stack(Items.OAK_LOG));
      state.deposit(stack(Items.CRAFTING_TABLE));
      long[] tick = {0};
      var runtime = new UltsRuntime(null, state, () -> tick[0]);
      assertEquals(8L, runtime.craftingAmount(stack(Items.STICK), state.items()).amount());
      state.deposit(stack(Items.STONE));
      tick[0]++;
      var unchanged = runtime.craftingAmount(stack(Items.STICK), state.items());
      assertEquals(8L, unchanged.amount());
      assertFalse(unchanged.pending(), "an unrelated deposit must preserve the exact answer");
      state.deposit(stack(Items.BIRCH_LOG));
      tick[0]++;
      var changed = runtime.craftingAmount(stack(Items.STICK), state.items());
      assertEquals(16L, changed.amount());
      assertTrue(changed.pending(), "a transitive alternative input changes this target's capacity");
      assertFalse(runtime.craftingAmount(stack(Items.OAK_LOG), state.items()).pending(), "unchanged raw material remains independent");
      tick[0] += runtime.stabilityQuietTicks();
      assertFalse(runtime.craftingAmount(stack(Items.STICK), state.items()).pending());
    }
  }
}
