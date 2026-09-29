package com.flwolfy.ults.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import com.flwolfy.ults.data.state.UltsBoxes;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * What a row promises: a box only while one can really be packed, a left click that names the number it
 * takes, and nothing but the right click on a row that only opens a bag.
 */
class UltsTakeHintsTest {

  @Test
  void aBoxNeedsTwentySevenFullStacksOfTheThing() {
    UltsTestBootstrap.boot();
    // Under the test bootstrap every stack is one piece, so a box of an item is twenty-seven pieces.
    ItemStack stone = UltsTestBootstrap.stack(Items.STONE);
    assertEquals(1, stone.getMaxStackSize());

    assertFalse(UltsTakeHints.boxReachable(stone, 26L));
    assertTrue(UltsTakeHints.boxReachable(stone, 27L));
    assertTrue(UltsTakeHints.boxReachable(stone, 4_000L));
  }

  @Test
  void aBoxNeverHoldsAnotherBox() {
    UltsTestBootstrap.boot();
    ItemStack box = UltsTestBootstrap.stack(Items.SHULKER_BOX);
    assertTrue(UltsBoxes.isShulker(box));

    // However many boxes are stored, none of them can be packed into a box.
    assertFalse(UltsTakeHints.boxReachable(box, 100_000L));
  }

  @Test
  void theBoxLineIsLeftOutWhileABoxCannotBePacked() {
    assertEquals(List.of(UltsTakeHints.TAKE, "ults.gui.take.choose", "ults.gui.shift.all"),
        UltsTakeHints.lines("ults.gui.take.choose", false, true));
    assertEquals(
        List.of(UltsTakeHints.TAKE, "ults.gui.take.choose",
            "ults.gui.shift.box", "ults.gui.shift.all"),
        UltsTakeHints.lines("ults.gui.take.choose", true, true));
  }

  @Test
  void theTakeEverythingLineIsLeftOutWhileTheServerDoesNotAllowIt() {
    // With taking everything switched off, a row never mentions it: what it does not answer, it does not
    // promise. The box line stays, because a shift left click has nothing to do with taking everything.
    assertEquals(List.of(UltsTakeHints.TAKE, "ults.gui.take.choose"),
        UltsTakeHints.lines("ults.gui.take.choose", false, false));
    assertEquals(List.of(UltsTakeHints.TAKE, "ults.gui.take.choose", "ults.gui.shift.box"),
        UltsTakeHints.lines("ults.gui.take.choose", true, false));
  }

  @Test
  void aRowThatOnlyOpensABagListsTheRightClickAndNothingElse() {
    // A bag is opened from here rather than emptied from here, so the row lists one line and answers one
    // click: nothing is said about a left click or a shift click, because none of them does anything.
    assertEquals(List.of("ults.gui.take.bag"), UltsTakeHints.bagLines("ults.gui.take.bag"));
    assertFalse(UltsTakeHints.bagLines("ults.gui.take.bag").contains(UltsTakeHints.TAKE));
  }

  @Test
  void aLeftClickNamesHowMuchItTakes() {
    // A whole stack while a stack of it is there...
    assertEquals(64L, UltsTakeHints.takenByLeftClick(64L, 64));
    assertEquals(64L, UltsTakeHints.takenByLeftClick(10_000L, 64));
    // ...and the few that are left while there are fewer than a stack of them.
    assertEquals(12L, UltsTakeHints.takenByLeftClick(12L, 64));
    // A thing that does not stack is one piece whenever there is one of it.
    assertEquals(1L, UltsTakeHints.takenByLeftClick(1L, 1));
    // An amount that is still being worked out is not a small one, and a click takes at least a piece.
    assertEquals(1L, UltsTakeHints.takenByLeftClick(0L, 64));
  }
}
