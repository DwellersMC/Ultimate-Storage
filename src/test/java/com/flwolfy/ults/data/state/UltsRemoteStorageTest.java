package com.flwolfy.ults.data.state;

import static org.junit.jupiter.api.Assertions.*;
import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;

import com.flwolfy.ults.crafting.*;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("acceptance")
class UltsRemoteStorageTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }

  private static ItemStack sized(Item item, int size) {
    ItemStack stack = stack(item);
    stack.set(DataComponents.MAX_STACK_SIZE, size);
    return stack;
  }

  private static final class Access implements UltsRemoteStorage.Access {
    final List<Container> containers;
    final List<ItemStack> ground = new ArrayList<>();
    boolean acceptsDrops;
    Access(Container... containers) { this.containers = List.of(containers); }
    @Override public void forEach(Consumer<Container> visitor) { containers.forEach(visitor); }
    @Override public boolean drop(ItemStack stack) {
      if (!acceptsDrops) { return false; }
      ground.add(stack.copy());
      return true;
    }
  }

  @Test void remoteSurplusSurvivesRejectedDropSaveReloadAndRetry() {
    ItemStack logs = sized(Items.OAK_LOG, 64);
    ItemStack planks = sized(Items.OAK_PLANKS, 64);
    var recipe = new UltsCraftRecipe(false, "minecraft:oak_planks",
        List.of(Ingredient.of(Items.OAK_LOG)), planks, 4);
    var container = new SimpleContainer(logs.copyWithCount(64), stack(Items.CRAFTING_TABLE));
    var access = new Access(container);
    var state = new UltsState();
    try (var catalog = new UltsTestCatalog(recipe)) {
      var output = UltsRemoteStorage.take(access, state, planks, 1, false, UltsCraftingMode.ALL);
      assertEquals(1L, output.stream().mapToLong(ItemStack::getCount).sum());
      assertEquals(63, container.getItem(0).getCount());
      assertEquals(3L, state.remoteRecovery().getFirst().amount());
      assertTrue(state.items().isEmpty());

      var encoded = UltsState.TYPE.codec().encodeStart(NbtOps.INSTANCE, state).getOrThrow();
      UltsState loaded = UltsState.TYPE.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow();
      assertEquals(3L, loaded.remoteRecovery().getFirst().amount());
      assertFalse(loaded.retryRemoteRecovery(stacks -> UltsRemoteStorage.restore(access, stacks)));
      assertEquals(3L, loaded.remoteRecovery().getFirst().amount());
      container.removeItemNoUpdate(0);
      assertTrue(loaded.retryRemoteRecovery(stacks -> UltsRemoteStorage.restore(access, stacks)));
      assertEquals(3, container.getItem(0).getCount());
      assertTrue(loaded.remoteRecovery().isEmpty());
      assertFalse(loaded.retryRemoteRecovery(stacks -> fail("nothing should be retried twice")));
    }
  }

  @Test void partialRestorePersistsOnlyTheUnplacedRemainder() {
    ItemStack planks = sized(Items.OAK_PLANKS, 64);
    var access = new Access(new SimpleContainer(planks.copyWithCount(63)));
    var state = new UltsState();
    ItemStack pending = planks.copyWithCount(3);
    assertFalse(UltsRemoteStorage.restore(access, List.of(pending)));
    assertEquals(64, access.containers.getFirst().getItem(0).getCount());
    assertEquals(2, pending.getCount());
    state.keepRemoteRecovery(List.of(pending));
    assertTrue(pending.isEmpty());
    assertEquals(2L, state.remoteRecovery().getFirst().amount());
  }

  @Test void completeInsertionConsumesTheHandedBackStack() {
    var access = new Access(new SimpleContainer(1));
    ItemStack pending = sized(Items.STONE, 64).copyWithCount(3);
    assertTrue(UltsRemoteStorage.restore(access, List.of(pending)));
    assertTrue(pending.isEmpty());
    assertEquals(3, access.containers.getFirst().getItem(0).getCount());
  }

  @Test void successfulDropDoesNotAlsoEnterTheRecoveryQueue() {
    var access = new Access(new SimpleContainer(stack(Items.DIRT)));
    access.acceptsDrops = true;
    ItemStack pending = sized(Items.STONE, 64).copyWithCount(3);
    assertTrue(UltsRemoteStorage.restore(access, List.of(pending)));
    assertTrue(pending.isEmpty());
    assertEquals(3, access.ground.getFirst().getCount());
  }

  @Test void recoveryInsertionRespectsContainerLimits() {
    var container = new SimpleContainer(1) {
      @Override public int getMaxStackSize() { return 1; }
    };
    var access = new Access(container);
    ItemStack pending = sized(Items.STONE, 64).copyWithCount(3);
    assertFalse(UltsRemoteStorage.restore(access, List.of(pending)));
    assertEquals(1, container.getItem(0).getCount());
    assertEquals(2, pending.getCount());
  }

  @Test void failedExtractionQueuesAnythingRollbackCannotRestore() {
    Item[] refused = new Item[1];
    var container = new SimpleContainer(stack(Items.STONE), stack(Items.DIRT), stack(Items.CRAFTING_TABLE)) {
      @Override public ItemStack removeItem(int slot, int count) {
        return getItem(slot).is(refused[0]) ? ItemStack.EMPTY : super.removeItem(slot, count);
      }
      @Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
    };
    var access = new Access(container);
    var ingredients = UltsRemoteStorage.snapshot(access).items().stream()
        .filter(view -> view.template().is(Items.STONE) || view.template().is(Items.DIRT)).toList();
    Item restored = ingredients.getFirst().template().getItem();
    refused[0] = ingredients.getLast().template().getItem();
    var recipe = new UltsCraftRecipe(false, "test:rollback", List.of(
        Ingredient.of(Items.STONE), Ingredient.of(Items.DIRT)), stack(Items.STICK), 1);
    var state = new UltsState();
    try (var catalog = new UltsTestCatalog(recipe)) {
      assertTrue(UltsRemoteStorage.take(access, state, stack(Items.STICK), 1, false, UltsCraftingMode.ALL).isEmpty());
      assertEquals(1L, state.remoteRecovery().getFirst().amount());
      assertTrue(state.remoteRecovery().getFirst().template().is(restored));
      assertEquals(1, java.util.stream.IntStream.range(0, 2)
          .map(slot -> container.getItem(slot).is(refused[0]) ? container.getItem(slot).getCount() : 0).sum());
      assertTrue(java.util.stream.IntStream.range(0, 2)
          .noneMatch(slot -> container.getItem(slot).is(restored)));
    }
  }

  @Test void remoteCraftingReturnsBucketsInsteadOfDestroyingThem() {
    var recipe = new UltsCraftRecipe(false, "test:milk", List.of(
        Ingredient.of(Items.MILK_BUCKET)), stack(Items.STICK), 1);
    var container = new SimpleContainer(stack(Items.MILK_BUCKET), stack(Items.CRAFTING_TABLE));
    var state = new UltsState();
    try (var catalog = new UltsTestCatalog(recipe)) {
      var output = UltsRemoteStorage.take(new Access(container), state, stack(Items.STICK), 1, false, UltsCraftingMode.ALL);
      assertEquals(1, output.size());
      assertTrue(container.getItem(0).is(Items.BUCKET));
      assertTrue(state.remoteRecovery().isEmpty());
    }
  }
}
