package com.flwolfy.ults.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UltsCraftMathTest {

  @Test
  void roundingUpAndSaturatingHelpersBehave() {
    assertEquals(0L, UltsCraftMath.divideRoundingUp(0L, 4L));
    assertEquals(1L, UltsCraftMath.divideRoundingUp(1L, 4L));
    assertEquals(2L, UltsCraftMath.divideRoundingUp(5L, 4L));
    assertEquals(3L, UltsCraftMath.divideRoundingUp(12L, 4L) - 0L);
    assertEquals(0L, UltsCraftMath.divideRoundingUp(5L, 0L));
    assertEquals(Long.MAX_VALUE, UltsCraftMath.add(Long.MAX_VALUE, 5L));
    assertEquals(Long.MAX_VALUE, UltsCraftMath.multiply(Long.MAX_VALUE, 2L));
    assertEquals(0L, UltsCraftMath.multiply(0L, 5L));
  }
}
