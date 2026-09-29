package com.flwolfy.ults.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * What a backpack can take: the measurement every screen that hands items over is built on.
 *
 * <p>Under the test bootstrap every stack is one piece, so a slot holds one item and the arithmetic is
 * easy to read. What is being pinned down is the shape of the answer, not the number of a slot.
 */
class UltsBackpackTest {

  @Test
  void roomCountsEmptySlotsAndTheSpaceLeftInStacksOfTheThing() {
    UltsTestBootstrap.boot();
    ItemStack stone = UltsTestBootstrap.stack(Items.STONE);
    ItemStack dirt = UltsTestBootstrap.stack(Items.DIRT);
    assertEquals(1, stone.getMaxStackSize());

    List<ItemStack> slots = empty();
    assertEquals(UltsBackpack.SLOTS, UltsBackpack.room(slots, stone));

    // A stack of the same thing is topped up before a new slot is opened, so its room counts too — and
    // here every slot holds one piece, so the only room left is the slots nothing is in.
    slots.set(0, stone.copyWithCount(1));
    slots.set(1, dirt.copyWithCount(1));
    assertEquals(UltsBackpack.SLOTS - 2, UltsBackpack.room(slots, stone));
    // A backpack with nothing left in it has no room at all.
    assertEquals(0, UltsBackpack.room(full(stone), stone));
  }

  @Test
  void fittingCountsTheStacksThatFitWholeAndStopsAtTheFirstThatDoesNot() {
    UltsTestBootstrap.boot();
    ItemStack stone = UltsTestBootstrap.stack(Items.STONE);

    List<ItemStack> slots = empty();
    List<ItemStack> stacks = new ArrayList<>();
    for (int index = 0; index < UltsBackpack.SLOTS + 4; index++) {
      stacks.add(stone.copyWithCount(1));
    }
    // A backpack takes one stack per slot: the four stacks past that fit nowhere.
    assertEquals(UltsBackpack.SLOTS, UltsBackpack.fitting(slots, stacks));
    assertFalse(UltsBackpack.fitting(empty(), stacks) == stacks.size());

    // A stack that cannot be placed stops the count rather than being skipped over.
    List<ItemStack> oneFree = full(stone);
    oneFree.set(7, ItemStack.EMPTY);
    assertEquals(1, UltsBackpack.fitting(oneFree, stacks));
    assertEquals(0, UltsBackpack.fitting(full(stone), stacks));
    assertTrue(UltsBackpack.fitting(oneFree, List.of(stone.copyWithCount(1))) == 1);
  }

  private static List<ItemStack> empty() {
    List<ItemStack> slots = new ArrayList<>(UltsBackpack.SLOTS);
    for (int slot = 0; slot < UltsBackpack.SLOTS; slot++) {
      slots.add(ItemStack.EMPTY);
    }
    return slots;
  }

  /** A backpack with every slot taken by a stack the same thing cannot be topped up into. */
  private static List<ItemStack> full(ItemStack template) {
    List<ItemStack> slots = empty();
    for (int slot = 0; slot < UltsBackpack.SLOTS; slot++) {
      ItemStack occupied = template.copyWithCount(template.getMaxStackSize());
      occupied.set(DataComponents.CUSTOM_NAME, Component.literal("full " + slot));
      slots.set(slot, occupied);
    }
    return slots;
  }
}
