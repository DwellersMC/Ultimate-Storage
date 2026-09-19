package com.flwolfy.ults.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        new UltsConfigData.Input(3, 0, 7, java.util.List.of("modid:Big_Chest")));
    assertEquals(7, data.canonicalize().input().drainInterval());
    assertEquals(3, data.canonicalize().input().permissionLevel());
    // Listed block ids are normalised and duplicates are dropped.
    assertEquals(java.util.List.of("modid:big_chest"),
        data.canonicalize().input().multiBlockContainers());
  }

  @Test
  void multiBlockContainerListStartsEmpty() {
    assertEquals(java.util.List.of(), UltsConfigData.DEFAULT.input().multiBlockContainers());
  }
}
