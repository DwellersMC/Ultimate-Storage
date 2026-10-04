package com.flwolfy.ults.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import com.flwolfy.ults.data.config.UltsConfigData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * What one confirmation of "take everything" may carry.
 *
 * <p>This is the answer to a stock no backpack can hold: the screen used to ask the storage for all of it
 * at once, which the withdrawal planner refuses past one backpack, and a refused plan left the room with
 * nothing taken and nothing said.
 *
 * <p>The amount is {@code input.bulkWithdrawalStacks} at most, one backpack's worth by default, and the storage
 * pours it out a tick's worth at a time; the plan this class tests is what the screen promises before the
 * click. Under the test bootstrap every stack is one piece, so a piece is a slot.
 */
class UltsTakeAllPlanTest {

  private static final int LIMIT = UltsConfigData.DEFAULT_BULK_WITHDRAWAL_STACKS;

  @Test
  void oneClickCarriesItsLimitAndSaysWhatItLeaves() {
    UltsTestBootstrap.boot();
    ItemStack stone = UltsTestBootstrap.stack(Items.STONE);
    List<ItemStack> wanted = List.of(stone.copyWithCount(1));
    List<ItemStack> empty = empty();

    // A stock one backpack can hold is taken whole, with nothing left and nothing on the ground.
    UltsTakeAllPlan small = UltsTakeAllPlan.of(20L, wanted, List.of(20L), false, LIMIT, empty);
    assertEquals(20L, small.taken());
    assertEquals(20L, small.pack());
    assertEquals(0L, small.ground());
    assertEquals(0L, small.left());
    assertTrue(small.possible());
    assertFalse(small.partial());
    assertFalse(small.blocked());

    // A stock of a thousand is carried one backpack at a time, and the rest is said rather than silently
    // dropped: the click takes what a click can carry, and the storage pours it out tick by tick.
    UltsTakeAllPlan huge = UltsTakeAllPlan.of(1_000L, wanted, List.of(1_000L), false, LIMIT, empty);
    assertEquals(UltsBackpack.SLOTS, huge.taken());
    assertEquals(UltsBackpack.SLOTS, huge.pack());
    assertEquals(0L, huge.ground());
    assertEquals(1_000L - UltsBackpack.SLOTS, huge.left());
    assertTrue(huge.partial());
  }

  @Test
  void theLimitIsTheConfigurationAndBoundsTheGround() {
    UltsTestBootstrap.boot();
    ItemStack stone = UltsTestBootstrap.stack(Items.STONE);
    List<ItemStack> wanted = List.of(stone.copyWithCount(1));

    // A configured limit of three stacks carries three stacks, and no more of the stock is promised.
    UltsTakeAllPlan limited = UltsTakeAllPlan.of(1_000L, wanted, List.of(1_000L), false, 3, empty());
    assertEquals(3L, limited.taken());
    assertEquals(997L, limited.left());

    // With a full backpack and the setting on, a click carries its limit and every piece of it lands on
    // the ground — bounded by the configuration, so emptying a warehouse can never bury the server.
    UltsTakeAllPlan full = UltsTakeAllPlan.of(
        1_000_000L, wanted, List.of(1_000_000L), true, 8, full(stone));
    assertEquals(8L, full.taken());
    assertEquals(0L, full.pack());
    assertEquals(8L, full.ground());
    assertEquals(1_000_000L - 8L, full.left());

    // With the setting off, a full backpack is the barrier the amount screen shows for the same answer.
    UltsTakeAllPlan blocked = UltsTakeAllPlan.of(
        1_000_000L, wanted, List.of(1_000_000L), false, LIMIT, full(stone));
    assertEquals(0L, blocked.taken());
    assertTrue(blocked.blocked());
    assertFalse(blocked.possible());
  }

  @Test
  void nothingStoredIsNotABackpackProblem() {
    UltsTestBootstrap.boot();
    UltsTakeAllPlan nothing = UltsTakeAllPlan.of(
        0L, List.of(UltsTestBootstrap.stack(Items.STONE)), List.of(0L), true, LIMIT, empty());
    assertEquals(0L, nothing.taken());
    assertFalse(nothing.blocked());
    assertFalse(nothing.possible());
  }

  @Test
  void aWholeBagLeavesRowByRowAndOnlyTheRowsThatFit() {
    UltsTestBootstrap.boot();
    ItemStack sword = UltsTestBootstrap.stack(Items.IRON_SWORD);
    List<ItemStack> bag = new ArrayList<>();
    List<Long> amounts = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      ItemStack row = sword.copyWithCount(1);
      row.set(DataComponents.CUSTOM_NAME, Component.literal("row " + index));
      bag.add(row);
      amounts.add(1L);
    }

    // Every row of a small bag fits, and the whole bag is a click's work.
    UltsTakeAllPlan all = UltsTakeAllPlan.of(5L, bag, amounts, false, LIMIT, empty());
    assertEquals(5, all.units());
    assertEquals(5L, all.taken());
    assertEquals(5L, all.pack());
    assertEquals(0L, all.left());

    // With two slots free, two rows leave and three stay: a row is taken as it is or not at all.
    List<ItemStack> twoFree = full(sword);
    twoFree.set(0, ItemStack.EMPTY);
    twoFree.set(1, ItemStack.EMPTY);
    UltsTakeAllPlan two = UltsTakeAllPlan.of(5L, bag, amounts, false, LIMIT, twoFree);
    assertEquals(2, two.units());
    assertEquals(2L, two.taken());
    assertEquals(3L, two.left());
    assertTrue(two.partial());
  }

  @Test
  void aBagIsMeasuredByWhatItsRowsReallyHold() {
    UltsTestBootstrap.boot();
    // A bag's rows carry what somebody put in, which is not one piece each: a row of eight fills eight
    // places in a backpack, and a screen that counted its rows rather than its pieces would promise far
    // more than the backpack can take and then drop the rest against the configuration.
    ItemStack sword = UltsTestBootstrap.stack(Items.IRON_SWORD);
    List<ItemStack> bag = new ArrayList<>();
    List<Long> amounts = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      ItemStack row = sword.copyWithCount(1);
      row.set(DataComponents.CUSTOM_NAME, Component.literal("row " + index));
      bag.add(row);
      amounts.add(8L);
    }

    // Sixteen free slots hold sixteen pieces, so two rows of eight fit whole and the other two stay.
    List<ItemStack> slots = full(sword);
    for (int slot = 0; slot < 16; slot++) {
      slots.set(slot, ItemStack.EMPTY);
    }
    UltsTakeAllPlan plan = UltsTakeAllPlan.of(32L, bag, amounts, false, LIMIT, slots);
    assertEquals(16, plan.units());
    assertEquals(16L, plan.taken());
    assertEquals(16L, plan.pack());
    assertEquals(0L, plan.ground());
    assertEquals(16L, plan.left());

    // With room for everything, all four rows fit and all four leave.
    UltsTakeAllPlan plenty = UltsTakeAllPlan.of(32L, bag, amounts, false, LIMIT, empty());
    assertEquals(32, plenty.units());
    assertEquals(32L, plenty.taken());
    assertEquals(32L, plenty.pack());
    assertEquals(0L, plenty.left());

    // A group of eight unstackable swords costs eight stacks; a limit of two takes two swords.
    UltsTakeAllPlan limited = UltsTakeAllPlan.of(32L, bag, amounts, false, 2, empty());
    assertEquals(2, limited.units());
    assertEquals(2L, limited.taken());
    assertEquals(30L, limited.left());
  }

  private static List<ItemStack> empty() {
    List<ItemStack> slots = new ArrayList<>(UltsBackpack.SLOTS);
    for (int slot = 0; slot < UltsBackpack.SLOTS; slot++) {
      slots.add(ItemStack.EMPTY);
    }
    return slots;
  }

  /** A backpack with every slot taken by something else. */
  private static List<ItemStack> full(ItemStack template) {
    List<ItemStack> slots = empty();
    for (int slot = 0; slot < UltsBackpack.SLOTS; slot++) {
      ItemStack occupied = UltsTestBootstrap.stack(Items.DIRT).copyWithCount(1);
      occupied.set(DataComponents.CUSTOM_NAME, Component.literal("full " + slot));
      slots.set(slot, occupied);
    }
    return slots;
  }
}
