package com.flwolfy.ults.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UltsInputManagerTest {

  @Test
  void acceptsTrimmedUnicodeNames() {
    assertEquals("主仓库 一号", UltsInputManager.normalizeName("  主仓库 一号  "));
  }

  @Test
  void rejectsEmptyControlAndOversizedNames() {
    assertNull(UltsInputManager.normalizeName("   "));
    assertNull(UltsInputManager.normalizeName("bad\nname"));
    assertNull(UltsInputManager.normalizeName("x".repeat(65)));
  }

  @Test
  void acceptsBoundaryLength() {
    assertEquals("x".repeat(64), UltsInputManager.normalizeName("x".repeat(64)));
  }
}
