package com.flwolfy.ults;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("acceptance")
class UltsStockStabilityTest {
  @Test void continuousTickChangesNeverAlternateBetweenPendingAndSettled() {
    var view = new UltsStockStability<String>();
    assertFalse(view.observe(Map.of("planks", 1000L, "stone", 64L), 0, 3));
    for (int tick = 1; tick < 200; tick++) {
      assertTrue(view.observe(Map.of("planks", 1000L - tick, "stone", 64L), tick, 3));
      assertTrue(view.pending("planks"));
      assertFalse(view.pending("stone"));
    }
    assertTrue(view.observe(Map.of("planks", 801L, "stone", 64L), 201, 3));
    long revision = view.revision();
    assertFalse(view.observe(Map.of("planks", 801L, "stone", 64L), 202, 3));
    assertEquals(revision + 1, view.revision(), "settling changes the GUI revision without a stock mutation");
  }

  @Test void evenSlowerChangesRestartTheWholeQuietWindow() {
    for (int interval = 1; interval <= 20; interval++) {
      var view = new UltsStockStability<String>();
      int quiet = interval + 1;
      assertFalse(view.observe("planks", 1000, 0, quiet));
      for (int tick = 1; tick <= interval * 20; tick++) {
        long value = 1000 - (tick - 1) / interval - 1;
        assertTrue(view.observe("planks", value, tick, quiet), "interval=" + interval + ",tick=" + tick);
      }
      long lastChanged = interval * 19L + 1;
      assertFalse(view.observe("planks", 980, lastChanged + quiet, quiet));
    }
  }

  @Test void stableContentsDoNotInventPendingOrChangeRevisions() {
    var view = new UltsStockStability<String>();
    assertFalse(view.observe("stone", 1, 0, 3));
    for (long tick = 0; tick < 100; tick++) assertFalse(view.observe("stone", 1, tick, 3));
    assertEquals(0, view.revision());
  }

  @Test void removingAndReaddingOneKindDoesNotBlockAnotherKind() {
    var view = new UltsStockStability<String>();
    view.observe(Map.of("log", 1L, "stone", 1L), 0, 6);
    view.observe(Map.of("stone", 1L), 1, 6);
    assertTrue(view.pending("log"));
    assertFalse(view.pending("stone"));
    view.observe(Map.of("log", 2L, "stone", 1L), 5, 6);
    assertTrue(view.observe(Map.of("log", 2L, "stone", 1L), 10, 6));
    assertFalse(view.observe(Map.of("log", 2L, "stone", 1L), 11, 6));
  }

  @Test void increasingConfiguredIntervalExtendsAnExistingWait() {
    var view = new UltsStockStability<String>();
    view.observe("stone", 1, 0, 3);
    view.observe("stone", 2, 1, 3);
    assertTrue(view.observe("stone", 2, 4, 21));
    assertTrue(view.observe("stone", 2, 21, 21));
    assertFalse(view.observe("stone", 2, 22, 21));
  }
}
