package com.flwolfy.ults.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.pb4.sgui.api.ClickType;
import net.minecraft.world.inventory.ContainerInput;
import org.junit.jupiter.api.Test;

/**
 * What a click on a row really arrives as, which is the contract the rows' click branches are written
 * against.
 *
 * <p>A click reaches a row in two steps, and neither of them carries a click type of its own. The client
 * sends the game's own {@link ContainerInput} and the mouse button beside it — a shift click is
 * {@code QUICK_MOVE} whichever button was used, so the button is the only thing that tells the two apart —
 * and SGUI turns that pair back into one of its own {@link ClickType}s. These are the pairs that matter to
 * a row: left, right, and either of them with shift held down.
 */
class UltsClickContractTest {

  @Test
  void aPlainClickArrivesAsTheButtonThatWasUsed() {
    assertEquals(ClickType.MOUSE_LEFT, ClickType.toClickType(ContainerInput.PICKUP, 0, 5));
    assertEquals(ClickType.MOUSE_RIGHT, ClickType.toClickType(ContainerInput.PICKUP, 1, 5));
  }

  @Test
  void aShiftClickKeepsTheButtonItWasMadeWith() {
    // Both shift clicks are QUICK_MOVE; only the button says which one it was, so a row that answers
    // shift and a right click answers two different things and can tell them apart.
    assertEquals(ClickType.MOUSE_LEFT_SHIFT, ClickType.toClickType(ContainerInput.QUICK_MOVE, 0, 5));
    assertEquals(ClickType.MOUSE_RIGHT_SHIFT, ClickType.toClickType(ContainerInput.QUICK_MOVE, 1, 5));
  }

  @Test
  void theOtherClicksARowAnswersMatchWhatTheClientSends() {
    // Middle click, which the client sends as CLONE, and the number keys, which swap a stack into a hotbar
    // slot. Neither is used by a row today; both are here so the table above is known to be the whole one.
    assertEquals(ClickType.MOUSE_MIDDLE, ClickType.toClickType(ContainerInput.CLONE, 2, 5));
    assertEquals(ClickType.NUM_KEY_1, ClickType.toClickType(ContainerInput.SWAP, 0, 5));
    assertEquals(ClickType.NUM_KEY_9, ClickType.toClickType(ContainerInput.SWAP, 8, 5));
    assertEquals(ClickType.OFFHAND_SWAP, ClickType.toClickType(ContainerInput.SWAP, 40, 5));
    // Q and control-Q, and the same two with nothing under the cursor — which is what -999 means.
    assertEquals(ClickType.DROP, ClickType.toClickType(ContainerInput.THROW, 0, 5));
    assertEquals(ClickType.CTRL_DROP, ClickType.toClickType(ContainerInput.THROW, 1, 5));
    assertEquals(ClickType.MOUSE_LEFT_OUTSIDE, ClickType.toClickType(ContainerInput.THROW, 0, -999));
    assertEquals(ClickType.MOUSE_RIGHT_OUTSIDE, ClickType.toClickType(ContainerInput.THROW, 1, -999));
  }
}
