package com.flwolfy.ults.display;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("acceptance")
class UltsQuantityPreviewTest {
  @Test void shortageNeverTurnsIntoAPartialStockWithdrawal() {
    for (boolean overflow : new boolean[]{false, true}) {
      assertEquals(0, UltsQuantityPreview.of(100000, 41470, false, 2304, overflow).selected());
      assertEquals(108, UltsQuantityPreview.of(108, 108, false, 2304, overflow).selected());
      assertEquals(0, UltsQuantityPreview.of(109, 108, false, 2304, overflow).selected());
    }
  }
  @Test void quantitiesBeyondIntAndInventoryLimitsRemainValid() {
    assertEquals(3_000_000_000L, UltsQuantityPreview.parse("3000000000"));
    var preview = UltsQuantityPreview.of(Long.MAX_VALUE, Long.MAX_VALUE, false, 2304, true);
    assertEquals(Long.MAX_VALUE, preview.selected());
    assertEquals(2304, preview.pack());
    assertEquals(Long.MAX_VALUE - 2304, preview.ground());
    assertEquals(0, preview.left());
  }

  @Test void fullInventoryOptionOffSelectsTheFittingPrefixAndLeavesTheRest() {
    var preview = UltsQuantityPreview.of(10_000, 20_000, false, 2304, false);
    assertEquals(2304, preview.selected());
    assertEquals(7696, preview.left());
    assertEquals(0, preview.ground());
    assertEquals(0, UltsQuantityPreview.of(10_000, 20_000, false, 0, false).selected());
    assertEquals(10_000, UltsQuantityPreview.of(10_000, 20_000, false, 0, true).selected());
  }

  @Test void pendingCraftingIsNotTurnedIntoAFalseZeroOrTruncatedRequest() {
    assertEquals(0, UltsQuantityPreview.of(10_000, 100, true, 2304, true).selected());
    assertEquals(0, UltsQuantityPreview.of(10_000, 100, false, 2304, true).selected());
  }

  @Test void invalidOrOutOfRangeInputIsAStableUnavailableState() {
    for (String value : new String[]{null, "", "-1", "0", "1.5", "abc", "9223372036854775808"}) {
      assertEquals(0L, UltsQuantityPreview.parse(value));
    }
    assertEquals(64L, UltsQuantityPreview.parse(" 64 "));
    assertEquals(Long.MAX_VALUE, UltsQuantityPreview.parse("9223372036854775807"));
  }
}
