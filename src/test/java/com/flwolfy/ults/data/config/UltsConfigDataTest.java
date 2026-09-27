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
            3, 0, 7, List.of("modid:Big_Chest"), UltsCraftingMode.ALL),
        UltsConfigData.DEFAULT.special());
    assertEquals(7, data.canonicalize().input().drainInterval());
    assertEquals(3, data.canonicalize().input().permissionLevel());
    // Listed block ids are normalised and duplicates are dropped.
    assertEquals(List.of("modid:big_chest"),
        data.canonicalize().input().multiBlockContainers());
    // The crafting mode is carried through untouched.
    assertEquals(UltsCraftingMode.ALL, data.canonicalize().input().crafting());
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
  void theSpecialCapIsTenPagesOfRows() {
    // The setting is a count; the pages a player sees are worked out from it.
    assertEquals(35, UltsConfigData.SPECIAL_PAGE_SIZE);
    assertEquals(10, UltsConfigData.DEFAULT_SPECIAL_PAGES);
    assertEquals(350, UltsConfigData.DEFAULT.special().maxEntries());
    assertEquals(0, UltsConfigData.pagesOf(0));
    assertEquals(1, UltsConfigData.pagesOf(1));
    assertEquals(1, UltsConfigData.pagesOf(35));
    assertEquals(2, UltsConfigData.pagesOf(36));
    assertEquals(10, UltsConfigData.pagesOf(350));
  }

  @Test
  void theSpecialFilterStartsOffAndNamesNothing() {
    assertEquals(UltsSpecialFilter.OFF, UltsConfigData.DEFAULT.special().filterMode());
    assertFalse(UltsConfigData.DEFAULT.special().filterMode().active());
    assertTrue(UltsSpecialFilter.KEEP_FULL_DURABILITY.active());
    assertTrue(UltsSpecialFilter.FILTER_ALL.active());
    assertFalse(UltsConfigData.DEFAULT.special().filterLootEquipment());
    assertEquals(List.of(), UltsConfigData.DEFAULT.special().filters());
  }

  @Test
  void canonicalizeNormalisesTheFilterList() {
    UltsConfigData data = new UltsConfigData(
        UltsConfigData.DEFAULT.general(),
        UltsConfigData.DEFAULT.input(),
        new UltsConfigData.Special(
            12, true, UltsSpecialFilter.FILTER_ALL,
            List.of("  Minecraft:Iron_Sword ", "minecraft:iron_sword", "minecraft:golden_sword")));
    UltsConfigData clean = data.canonicalize();
    assertEquals(List.of("minecraft:iron_sword", "minecraft:golden_sword"),
        clean.special().filters());
    assertEquals(12, clean.special().maxEntries());
    assertTrue(clean.special().filterLootEquipment());
    assertEquals(UltsSpecialFilter.FILTER_ALL, clean.special().filterMode());
  }
}
