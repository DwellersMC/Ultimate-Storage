package com.flwolfy.ults.display;

import static org.junit.jupiter.api.Assertions.*;
import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;
import com.flwolfy.ults.crafting.UltsTestBootstrap;
import com.flwolfy.ults.data.state.UltsWithdrawalOutput;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.*;

@Tag("acceptance")
class UltsGuiGiveTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }
  static ItemStack stone(int count) {
    var result = stack(Items.STONE).copyWithCount(count);
    result.set(DataComponents.MAX_STACK_SIZE, 64);
    return result;
  }
  static List<ItemStack> full() {
    var slots = new ArrayList<ItemStack>();
    for (int i = 0; i < 36; i++) slots.add(stone(64));
    return slots;
  }
  static final class World {
    List<ItemStack> slots = full();
    final List<ItemStack> restored = new ArrayList<>(), dropped = new ArrayList<>();
    boolean reject = true;
    int dropCalls;
    UltsGuiGive.Delivery deliver(List<ItemStack> outputs, boolean overflow) {
      return UltsGuiGive.deliver(outputs, output -> UltsBackpack.fill(slots, output), leftover -> {
        dropCalls++;
        if (reject) return false;
        dropped.add(leftover.copy());
        leftover.setCount(0);
        return true;
      }, leftover -> { restored.add(leftover.copy()); leftover.setCount(0); }, overflow);
    }
  }
  @Test void acceptedInventoryDeliveryCountsOnlyTheActualItems() {
    var world = new World();
    world.slots.set(0, ItemStack.EMPTY);
    var output = stone(64);
    var result = world.deliver(List.of(output), true);
    assertEquals(64, result.delivered());
    assertTrue(result.complete());
    assertEquals(64, result.backpack());
    assertEquals(0, world.dropCalls);
    assertEquals(64, output.getCount());
  }
  @Test void rejectedWorldDeliveryIsRestoredAndCannotReportSuccess() {
    var world = new World();
    var output = stone(64);
    var result = world.deliver(List.of(output), true);
    assertEquals(0, result.delivered());
    assertEquals(64, result.rejected());
    assertFalse(result.complete());
    assertEquals(64, world.restored.getFirst().getCount());
    assertEquals(64, output.getCount());
  }
  @Test void partialDeliveryAndItsRollbackAreCountedBeforeTheRemainderIsCleared() {
    var world = new World();
    world.slots.set(0, stone(60));
    var result = world.deliver(List.of(stone(64)), true);
    assertEquals(4, result.delivered());
    assertEquals(60, result.rejected());
    assertEquals(60, world.restored.getFirst().getCount());
    assertEquals(64, world.slots.getFirst().getCount());
  }
  @Test void successfulOverflowIsCountedSeparatelyFromTheBackpack() {
    var world = new World();
    world.slots.set(0, stone(60));
    world.reject = false;
    var result = world.deliver(List.of(stone(64)), true);
    assertTrue(result.complete());
    assertEquals(64, result.delivered());
    assertEquals(4, result.backpack());
    assertEquals(60, result.ground());
    assertTrue(world.restored.isEmpty());
  }
  @Test void overflowDisabledNeverCallsTheDropOperation() {
    var world = new World();
    world.reject = false;
    var result = world.deliver(List.of(stone(64)), false);
    assertEquals(0, world.dropCalls);
    assertEquals(0, result.delivered());
    assertEquals(64, result.rejected());
  }
  @Test void aPackedBoxIsOneDeliveryUnitAndRetainsEveryContainedItemWhenRejected() {
    var world = new World();
    var box = UltsWithdrawalOutput.packedBox(stack(Items.SHULKER_BOX), stone(1));
    var result = world.deliver(List.of(box), true);
    assertEquals(0, result.delivered());
    assertEquals(1, result.rejected());
    assertEquals(box.get(DataComponents.CONTAINER), world.restored.getFirst().get(DataComponents.CONTAINER));
    assertEquals(1728, box.get(DataComponents.CONTAINER).allItemsCopyStream().mapToInt(ItemStack::getCount).sum());
  }
  @Test void successiveStacksUseTheInventorySpaceRemainingAfterEarlierDelivery() {
    var world = new World();
    world.slots.set(0, ItemStack.EMPTY);
    var result = world.deliver(List.of(stone(64), stone(64)), false);
    assertEquals(64, result.delivered());
    assertEquals(64, result.rejected());
    assertEquals(1, world.restored.size());
  }
  @Test void streamedProgressUsesTheSameDeliveryTransaction() {
    var world = new World();
    world.slots.set(0, stone(60));
    var batch = new UltsTakeAllBatch(List.of(stone(1)), List.of(64L));
    var stopped = batch.advance(64, true, () -> world.slots,
        (template, count) -> List.of(template.copyWithCount(count)),
        outputs -> world.deliver(outputs, true).rejected());
    assertEquals(UltsTakeAllStream.Stop.BACKPACK, stopped);
    assertEquals(4, batch.taken());
    assertEquals(60, batch.remaining());
  }
}
