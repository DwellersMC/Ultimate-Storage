package com.flwolfy.ults.display;

import static org.junit.jupiter.api.Assertions.*;
import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import com.flwolfy.ults.data.state.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("acceptance")
class UltsTakeAllBatchTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }

  private static ItemStack sword(String name) {
    var sword = stack(Items.DIAMOND_SWORD);
    sword.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    return sword;
  }

  private static class Harness {
    final UltsState state = new UltsState();
    final List<ItemStack> slots = new ArrayList<>(Collections.nCopies(36, ItemStack.EMPTY));
    final List<Integer> requests = new ArrayList<>();
    long ground;
    boolean rejectsDrops;

    UltsTakeAllStream.Stop tick(UltsTakeAllBatch batch, int rate, boolean overflow) {
      return batch.advance(rate, overflow, () -> slots, (template, count) -> {
        requests.add(count);
        ItemStack taken = state.takeBagPieces(template.getItem(), template, count);
        return UltsWithdrawalOutput.stacks(taken, taken.getCount());
      }, outputs -> {
        long rejected = 0L;
        for (ItemStack output : outputs) {
          ItemStack pending = UltsBackpack.fill(slots, output);
          if (!pending.isEmpty()) {
            if (overflow && !rejectsDrops) {
              ground += pending.getCount();
            } else {
              rejected += pending.getCount();
              state.deposit(pending);
            }
          }
        }
        return rejected;
      });
    }

    long stock() { return state.items().stream().mapToLong(UltsStoredView::amount).sum(); }
    long pack() { return slots.stream().mapToLong(ItemStack::getCount).sum(); }
    void full() { for (int index = 0; index < slots.size(); index++) { slots.set(index, stack(Items.DIRT)); } }
  }

  @Test void aHugeBagObeysTheStackLimitAndOnePiecePerTick() {
    var h = new Harness();
    var a = sword("A");
    var b = sword("B");
    h.state.deposit(a.copyWithCount(1000));
    h.state.deposit(b);
    var plan = UltsTakeAllPlan.of(1001, List.of(a, b), List.of(1000L, 1L), true, 36, h.slots);
    assertEquals(36L, plan.taken());
    assertEquals(List.of(36L, 0L), plan.amounts());
    var batch = new UltsTakeAllBatch(List.of(a, b), plan.amounts());
    for (int tick = 1; tick <= 36; tick++) {
      var reason = h.tick(batch, 1, true);
      assertEquals(tick, batch.taken());
      assertEquals(tick == 36 ? UltsTakeAllStream.Stop.DONE : null, reason);
    }
    assertEquals(965L, h.stock());
    assertEquals(36L, h.pack());
    assertTrue(h.requests.stream().allMatch(count -> count == 1));
  }

  @Test void theRateIsSharedAcrossRowsAndTheirRemainders() {
    var h = new Harness();
    var a = sword("A");
    var b = sword("B");
    h.state.deposit(a.copyWithCount(2));
    h.state.deposit(b.copyWithCount(5));
    var batch = new UltsTakeAllBatch(List.of(a, b), List.of(2L, 5L));
    assertNull(h.tick(batch, 3, false));
    assertEquals(3L, batch.taken());
    assertNull(h.tick(batch, 3, false));
    assertEquals(6L, batch.taken());
    assertEquals(UltsTakeAllStream.Stop.DONE, h.tick(batch, 3, false));
    assertEquals(7L, batch.taken());
    assertEquals(0L, h.stock());
  }

  @Test void laterArrivalsAreNotTakenByAnEarlierConfirmation() {
    var h = new Harness();
    var a = sword("A");
    var b = sword("B");
    h.state.deposit(a.copyWithCount(2));
    h.state.deposit(b.copyWithCount(2));
    var batch = new UltsTakeAllBatch(List.of(a, b), List.of(2L, 2L));
    h.state.deposit(a.copyWithCount(10));
    assertEquals(UltsTakeAllStream.Stop.DONE, h.tick(batch, 64, false));
    assertEquals(4L, batch.taken());
    assertEquals(10L, h.stock());
  }

  @Test void storageShortageReportsFailureWithTheActualDeliveredCount() {
    var h = new Harness();
    var a = sword("A");
    var b = sword("B");
    h.state.deposit(a.copyWithCount(2));
    h.state.deposit(b.copyWithCount(2));
    var batch = new UltsTakeAllBatch(List.of(a, b), List.of(2L, 2L));
    h.state.takeBagPieces(a.getItem(), a, 1);
    assertEquals(UltsTakeAllStream.Stop.EMPTY, h.tick(batch, 64, false));
    assertEquals(3L, batch.taken());
    assertEquals(1L, batch.remaining());
    assertEquals(0L, h.stock());
  }

  @Test void aRefusedDropIsReturnedAndDoesNotReportSuccess() {
    var h = new Harness();
    var a = sword("A");
    h.state.deposit(a.copyWithCount(3));
    h.full();
    h.rejectsDrops = true;
    var batch = new UltsTakeAllBatch(List.of(a), List.of(3L));
    assertEquals(UltsTakeAllStream.Stop.BACKPACK, h.tick(batch, 64, true));
    assertEquals(0L, batch.taken());
    assertEquals(3L, batch.remaining());
    assertEquals(3L, h.stock());
  }

  @Test void partialDeliveryCountsOnlyWhatThePlayerReceived() {
    var h = new Harness();
    var a = sword("A");
    h.state.deposit(a.copyWithCount(3));
    h.full();
    h.slots.set(0, ItemStack.EMPTY);
    h.rejectsDrops = true;
    var batch = new UltsTakeAllBatch(List.of(a), List.of(3L));
    assertEquals(UltsTakeAllStream.Stop.BACKPACK, h.tick(batch, 64, true));
    assertEquals(1L, batch.taken());
    assertEquals(2L, batch.remaining());
    assertEquals(2L, h.stock());
    assertTrue(h.slots.getFirst().is(Items.DIAMOND_SWORD));
  }

  @Test void overflowOffStopsBeforeTakingItemsThatCannotFit() {
    var h = new Harness();
    var a = sword("A");
    h.state.deposit(a.copyWithCount(3));
    h.full();
    h.slots.set(0, ItemStack.EMPTY);
    var batch = new UltsTakeAllBatch(List.of(a), List.of(3L));
    assertEquals(UltsTakeAllStream.Stop.BACKPACK, h.tick(batch, 64, false));
    assertEquals(1L, batch.taken());
    assertEquals(2L, h.stock());
    assertEquals(0L, h.ground);
    assertEquals(List.of(1), h.requests);
  }

  @Test void highRatesSplitWithdrawalsWithinThePlannerLimit() {
    var h = new Harness();
    var a = sword("A");
    h.state.deposit(a.copyWithCount(100));
    var batch = new UltsTakeAllBatch(List.of(a), List.of(100L));
    assertNull(h.tick(batch, 64, true));
    assertEquals(64L, batch.taken());
    assertEquals(List.of(36, 28), h.requests);
    assertEquals(UltsTakeAllStream.Stop.DONE, h.tick(batch, 64, true));
    assertEquals(100L, h.pack() + h.ground);
    assertEquals(0L, h.stock());
  }

  @Test void freshBagSnapshotUpdatesRowsAndAmountsTogether() {
    var a = sword("A");
    var b = sword("B");
    var h = new Harness();
    h.state.deposit(a.copyWithCount(5));
    h.state.deposit(b.copyWithCount(5));
    var old = UltsTakeAllSGUI.bagStock(a.getItem(), h.state.items());
    assertEquals(10L, old.total());
    h.state.takeBagPieces(a.getItem(), a, 4);
    var fresh = UltsTakeAllSGUI.bagStock(a.getItem(), h.state.items());
    var rows = fresh.rows().stream().map(UltsStoredView::template).toList();
    var counts = fresh.rows().stream().map(UltsStoredView::amount).toList();
    var plan = UltsTakeAllPlan.of(fresh.total(), rows, counts, false, 36, h.slots);
    assertEquals(6L, plan.taken());
    assertEquals(0L, plan.left());
    assertEquals(UltsTakeAllStream.Stop.DONE, h.tick(new UltsTakeAllBatch(rows, plan.amounts()), 64, false));
    assertEquals(6L, h.pack());
    assertEquals(0L, h.stock());
  }

  @Test void inconsistentTotalsCannotPromiseMoreThanCurrentStock() {
    var h = new Harness();
    var plan = UltsTakeAllPlan.of(6, List.of(sword("A"), sword("B")), List.of(5L, 5L), false, 36, h.slots);
    assertEquals(6L, plan.taken());
    assertEquals(0L, plan.left());
  }

  @Test void measuringAPlanDoesNotModifyExistingInventoryStacks() {
    var h = new Harness();
    ItemStack stone = stack(Items.STONE);
    stone.set(DataComponents.MAX_STACK_SIZE, 64);
    h.slots.set(0, stone.copyWithCount(63));
    var plan = UltsTakeAllPlan.of(64, List.of(stone), List.of(64L), false, 36, h.slots);
    assertEquals(64L, plan.taken());
    assertEquals(63, h.slots.getFirst().getCount());
  }
}
