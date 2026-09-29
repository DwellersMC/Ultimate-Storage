package com.flwolfy.ults.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class UltsConfigDataTest {

  @Test
  void defaultsKeepTheBehaviourTheServerHadBefore() {
    assertEquals(2, UltsConfigData.DEFAULT.input().drainInterval());
    assertEquals(0, UltsConfigData.DEFAULT.input().maxBindings());
    // One permission level covers binding, deleting, the highlight and reloading.
    assertEquals(2, UltsConfigData.DEFAULT.input().permissionLevel());
  }

  @Test
  void drainIntervalBoundsAreDeclaredOnce() {
    assertEquals(1, UltsConfigData.MIN_DRAIN_INTERVAL);
    assertEquals(20, UltsConfigData.MAX_DRAIN_INTERVAL);
    // The default has to sit inside the supported range.
    assertTrue(UltsConfigData.DEFAULT.input().drainInterval()
        >= UltsConfigData.MIN_DRAIN_INTERVAL);
    assertTrue(UltsConfigData.DEFAULT.input().drainInterval()
        <= UltsConfigData.MAX_DRAIN_INTERVAL);
  }

  @Test
  void canonicalizeKeepsTheDrainIntervalAndPermission() {
    UltsConfigData data = new UltsConfigData(
        UltsConfigData.DEFAULT.general(),
        new UltsConfigData.Input(
            3, 0, 7, List.of("modid:Big_Chest"), UltsCraftingMode.ALL, true),
        UltsConfigData.DEFAULT.special());
    assertEquals(7, data.canonicalize().input().drainInterval());
    assertEquals(3, data.canonicalize().input().permissionLevel());
    // Listed block ids are normalised and duplicates are dropped.
    assertEquals(List.of("modid:big_chest"),
        data.canonicalize().input().multiBlockContainers());
    // The crafting mode and the full-inventory option are carried through untouched.
    assertEquals(UltsCraftingMode.ALL, data.canonicalize().input().crafting());
    assertTrue(data.canonicalize().input().allowFullInventory());
  }

  @Test
  void multiBlockContainerListStartsEmpty() {
    assertEquals(List.of(), UltsConfigData.DEFAULT.input().multiBlockContainers());
  }

  @Test
  void craftingIsOffByDefault() {
    assertEquals(UltsCraftingMode.DISABLED, UltsConfigData.DEFAULT.input().crafting());
    assertFalse(UltsConfigData.DEFAULT.input().crafting().enabled());
    assertTrue(UltsCraftingMode.SHULKER_BOXES_ONLY.enabled());
    assertTrue(UltsCraftingMode.ALL.enabled());
  }

  @Test
  void aFullBackpackBlocksAWithdrawalUntilItIsAllowed() {
    assertFalse(UltsConfigData.DEFAULT.input().allowFullInventory());
  }

  @Test
  void aBagStartsAtThreePagesOfTheGrid() {
    // The setting is a count of stacks; the pages a player pages through are worked out from it, and
    // the grid is the one the bag screen really draws — forty-five to a page, five rows of nine.
    assertEquals(45, UltsConfigData.BUNDLE_PAGE_SIZE);
    assertEquals(120, UltsConfigData.DEFAULT_BUNDLE_SLOTS);
    assertEquals(120, UltsConfigData.DEFAULT.special().bundleSlots());
    assertEquals(0, UltsConfigData.pagesOf(0));
    assertEquals(1, UltsConfigData.pagesOf(1));
    assertEquals(1, UltsConfigData.pagesOf(45));
    assertEquals(2, UltsConfigData.pagesOf(46));
    assertEquals(3, UltsConfigData.pagesOf(120));
  }

  @Test
  void theSpecialFilterStartsOffAndNamesNothing() {
    assertEquals(UltsSpecialFilter.OFF, UltsConfigData.DEFAULT.special().filterMode());
    assertFalse(UltsConfigData.DEFAULT.special().filterMode().active());
    assertTrue(UltsSpecialFilter.KEEP_FULL_DURABILITY.active());
    assertTrue(UltsSpecialFilter.FILTER_ALL.active());
    // Nothing is filtered unless an id is written down: the equipment switch is gone.
    assertEquals(List.of(), UltsConfigData.DEFAULT.special().filters());
  }

  @Test
  void canonicalizeNormalisesTheFilterList() {
    UltsConfigData data = new UltsConfigData(
        UltsConfigData.DEFAULT.general(),
        UltsConfigData.DEFAULT.input(),
        new UltsConfigData.Special(
            24, UltsStackRule.TOOLTIP, UltsSpecialFilter.FILTER_ALL,
            List.of("  Minecraft:Iron_Sword ", "minecraft:iron_sword", "minecraft:golden_sword")));
    UltsConfigData clean = data.canonicalize();
    assertEquals(List.of("minecraft:iron_sword", "minecraft:golden_sword"),
        clean.special().filters());
    assertEquals(24, clean.special().bundleSlots());
    assertEquals(UltsStackRule.TOOLTIP, clean.special().stackRule());
    assertEquals(UltsSpecialFilter.FILTER_ALL, clean.special().filterMode());
  }
}
