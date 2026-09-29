package com.flwolfy.ults.data.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import com.flwolfy.ults.data.config.UltsStackRule;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * When two stacks of one item are the same kind of thing: every component the same, or the same
 * tooltip, which is what the two rules mean.
 */
class UltsStackKindsTest {

  @Test
  void byComponentsTwoStacksDifferAsSoonAsAnythingDoes() {
    UltsTestBootstrap.boot();
    ItemStack one = sword("我的剑", 0);
    ItemStack two = sword("我的剑", 10);

    // The name is the precondition, and so is everything else: a worn sword is a kind of its own.
    assertEquals(UltsStackKinds.of(one, UltsStackRule.COMPONENTS),
        UltsStackKinds.of(sword("我的剑", 0), UltsStackRule.COMPONENTS));
    assertNotEquals(UltsStackKinds.of(one, UltsStackRule.COMPONENTS),
        UltsStackKinds.of(two, UltsStackRule.COMPONENTS));
    assertNotEquals(UltsStackKinds.of(one, UltsStackRule.COMPONENTS),
        UltsStackKinds.of(sword("别的剑", 0), UltsStackRule.COMPONENTS));
  }

  @Test
  void byTooltipTwoStacksAreOneKindWhileTheyWouldReadTheSame() {
    UltsTestBootstrap.boot();
    // Wear is not on the tooltip, so two swords worn differently are one kind under this rule — and two
    // swords whose names differ still are not.
    assertEquals(UltsStackKinds.of(sword("我的剑", 0), UltsStackRule.TOOLTIP),
        UltsStackKinds.of(sword("我的剑", 10), UltsStackRule.TOOLTIP));
    assertNotEquals(UltsStackKinds.of(sword("我的剑", 0), UltsStackRule.TOOLTIP),
        UltsStackKinds.of(sword("别的剑", 0), UltsStackRule.TOOLTIP));
  }

  @Test
  void theSameWordsOnAnotherItemAreNotTheSameKind() {
    UltsTestBootstrap.boot();
    // Two items given the same name read "我的剑" on the tooltip, and they are still two things: the item
    // is the precondition of every rule, and a tooltip answer is remembered per item because of it.
    assertEquals(UltsStackKinds.of(sword("我的剑", 0), UltsStackRule.TOOLTIP),
        UltsStackKinds.of(sword("我的剑", 0), UltsStackRule.TOOLTIP));
    assertNotEquals(UltsStackKinds.of(sword("我的剑", 0), UltsStackRule.TOOLTIP),
        UltsStackKinds.of(namedPickaxe("我的剑"), UltsStackRule.TOOLTIP));
  }

  @Test
  void aPlainStackOfAnItemIsOneKindWhateverTheRule() {
    UltsTestBootstrap.boot();
    ItemStack stone = UltsTestBootstrap.stack(Items.STONE);
    assertTrue(UltsStackKinds.same(stone, UltsTestBootstrap.stack(Items.STONE)));
    assertEquals("", "");
    assertFalse(UltsStackKinds.same(stone, UltsTestBootstrap.stack(Items.DIRT)));
  }

  private static ItemStack sword(String name, int damage) {
    ItemStack stack = UltsTestBootstrap.stack(Items.IRON_SWORD);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    stack.set(DataComponents.DAMAGE, damage);
    return stack;
  }

  private static ItemStack namedPickaxe(String name) {
    ItemStack stack = UltsTestBootstrap.stack(Items.IRON_PICKAXE);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    return stack;
  }
}
