package com.flwolfy.ults.data.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class UltsStateTest {

  @Test
  void terminalNamesAreCaseInsensitive() {
    UltsState state = new UltsState();
    assertTrue(state.addTerminal(terminal("Main", 1, 2, 3)));
    assertNotNull(state.terminal(" main "));
    assertFalse(state.addTerminal(terminal("MAIN", 8, 9, 10)));
  }

  @Test
  void protectsBothBlocksUntilDeletion() {
    UltsState state = new UltsState();
    state.addTerminal(terminal("input", 1, 2, 3));
    assertTrue(state.protects("minecraft:overworld", new BlockPos(1, 2, 3)));
    assertTrue(state.protects("minecraft:overworld", new BlockPos(1, 3, 3)));
    assertFalse(state.protects("minecraft:the_nether", new BlockPos(1, 2, 3)));
    assertNotNull(state.removeTerminal("INPUT"));
    assertFalse(state.protects("minecraft:overworld", new BlockPos(1, 2, 3)));
    assertNull(state.removeTerminal("input"));
    assertEquals(0, state.terminals().size());
  }

  @Test
  void storesIndependentPlayerViewProfilesWithoutChangingContentRevision() {
    UltsState state = new UltsState();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UltsViewProfile profile = new UltsViewProfile("minecraft:building_blocks", 2, 4);
    state.setViewProfile(first, profile);
    assertEquals(profile, state.viewProfile(first));
    assertEquals(UltsViewProfile.DEFAULT, state.viewProfile(second));
    assertEquals(0, state.revision());
  }

  private static UltsTerminal terminal(String name, int x, int y, int z) {
    return new UltsTerminal(name, "minecraft:overworld", x, y, z, "creator");
  }
}
