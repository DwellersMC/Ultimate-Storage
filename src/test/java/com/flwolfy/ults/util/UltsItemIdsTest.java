package com.flwolfy.ults.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UltsItemIdsTest {

  @BeforeAll
  static void boot() {
    UltsTestBootstrap.boot();
  }

  @Test
  void anIdMayOmitTheNamespaceAndIgnoreCase() {
    assertEquals(Items.IRON_SWORD, UltsItemIds.resolve("minecraft:iron_sword").orElseThrow());
    assertEquals(Items.IRON_SWORD, UltsItemIds.resolve("iron_sword").orElseThrow());
    assertEquals(Items.IRON_SWORD, UltsItemIds.resolve("  Minecraft:Iron_Sword ").orElseThrow());
  }

  @Test
  void anIdThatNamesNoItemOfThisGameIsRejected() {
    assertTrue(UltsItemIds.resolve("no_such_item_here").isEmpty());
    assertTrue(UltsItemIds.resolve("minecraft:water").isEmpty());
    assertTrue(UltsItemIds.resolve("").isEmpty());
    assertTrue(UltsItemIds.resolve("   ").isEmpty());
    assertTrue(UltsItemIds.resolve(null).isEmpty());
    assertTrue(UltsItemIds.resolve("not a valid id").isEmpty());
  }
}
