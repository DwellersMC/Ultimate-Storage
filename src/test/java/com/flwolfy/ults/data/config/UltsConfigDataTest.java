package com.flwolfy.ults.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UltsConfigDataTest {

  @Test
  void defaultsSpreadTheDrainWorkOverTwoTicks() {
    // The game behaviour stays as before unless the server owner changes this on purpose.
    assertEquals(2, UltsConfigData.DEFAULT.input().drainInterval());
    assertEquals(0, UltsConfigData.DEFAULT.input().maxBindings());
    assertEquals(2, UltsConfigData.DEFAULT.input().bindPermissionLevel());
    assertEquals(2, UltsConfigData.DEFAULT.input().deletePermissionLevel());
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
  void canonicalizeKeepsTheDrainInterval() {
    UltsConfigData data = new UltsConfigData(
        UltsConfigData.DEFAULT.general(),
        new UltsConfigData.Input(2, 2, 0, 7));
    assertEquals(7, data.canonicalize().input().drainInterval());
  }
}
