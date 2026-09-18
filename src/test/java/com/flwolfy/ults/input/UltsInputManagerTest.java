package com.flwolfy.ults.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UltsInputManagerTest {

  @Test
  void acceptsTrimmedUnicodeNotes() {
    assertEquals("主仓库 一号", UltsInputManager.normalizeNote("  主仓库 一号  "));
  }

  @Test
  void acceptsAnEmptyNote() {
    // A note is optional, unlike the storage name it replaced.
    assertEquals("", UltsInputManager.normalizeNote(""));
    assertEquals("", UltsInputManager.normalizeNote("   "));
  }

  @Test
  void rejectsControlOversizedAndFormattingNotes() {
    assertNull(UltsInputManager.normalizeNote("bad\nname"));
    assertNull(UltsInputManager.normalizeNote("x".repeat(65)));
    assertNull(UltsInputManager.normalizeNote("\u00A7cRed"));
  }

  @Test
  void acceptsBoundaryLength() {
    assertEquals("x".repeat(64), UltsInputManager.normalizeNote("x".repeat(64)));
  }
}
