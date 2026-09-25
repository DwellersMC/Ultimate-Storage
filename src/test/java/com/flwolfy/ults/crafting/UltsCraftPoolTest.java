package com.flwolfy.ults.crafting;

import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.data.state.UltsStoredView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UltsCraftPoolTest {

  @BeforeAll
  static void boot() {
    UltsTestBootstrap.boot();
  }

  @Test
  void matchingCountsEveryEntryAnItemAppearsIn() {
    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.OAK_LOG), 3L);
    pool.add(stack(Items.OAK_PLANKS), 5L);
    pool.add(stack(Items.STICK), 7L);

    Ingredient planks = Ingredient.of(Items.OAK_PLANKS);
    assertEquals(5L, pool.matches(planks));
    assertEquals(15L, pool.total());
    assertTrue(pool.has(Items.OAK_LOG));
    assertFalse(pool.has(Items.BIRCH_LOG));
  }

  @Test
  void keepingAnAmountLeavesItOutOfMatching() {
    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.OAK_PLANKS), 10L);
    pool.keep(stack(Items.OAK_PLANKS), 4L);

    assertEquals(6L, pool.matches(Ingredient.of(Items.OAK_PLANKS)));
    // Only what is not kept may be handed to a recipe.
    assertEquals(6L, pool.takeMatching(Ingredient.of(Items.OAK_PLANKS), 6L));
    assertEquals(4L, pool.amount(stack(Items.OAK_PLANKS)));

    pool.keep(null, 0L);
    assertEquals(4L, pool.matches(Ingredient.of(Items.OAK_PLANKS)));
  }

  @Test
  void aRollbackPutsThePileBackExactly() {
    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.OAK_LOG), 3L);
    pool.add(stack(Items.STICK), 2L);

    int mark = pool.mark();
    pool.takeMatching(Ingredient.of(Items.OAK_LOG), 2L);
    pool.add(stack(Items.OAK_PLANKS), 8L);
    pool.add(stack(Items.STICK), 4L);
    assertEquals(1L, pool.amount(stack(Items.OAK_LOG)));
    assertEquals(8L, pool.amount(stack(Items.OAK_PLANKS)));
    assertEquals(6L, pool.amount(stack(Items.STICK)));

    pool.rollback(mark);

    assertEquals(3L, pool.amount(stack(Items.OAK_LOG)));
    assertEquals(0L, pool.amount(stack(Items.OAK_PLANKS)));
    assertEquals(2L, pool.amount(stack(Items.STICK)));
    assertEquals(0L, pool.matches(Ingredient.of(Items.OAK_PLANKS)));
    assertEquals(5L, pool.total());
    assertEquals(2, pool.size());
    assertEquals(List.of(3L, 2L), amounts(pool));
  }

  @Test
  void anEntryAddedInsideAMarkLeavesNoTraceBehind() {
    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.STICK), 1L);

    int mark = pool.mark();
    pool.add(stack(Items.OAK_PLANKS), 5L);
    assertEquals(6L, pool.total());
    pool.rollback(mark);

    // The entry is gone from the pile, from its totals and from what a slot matches.
    assertEquals(1L, pool.total());
    assertEquals(1, pool.size());
    assertEquals(0L, pool.matches(Ingredient.of(Items.OAK_PLANKS)));
    assertFalse(pool.has(Items.OAK_PLANKS));

    // And the slot it used can be handed to another item without confusing either of them.
    pool.add(stack(Items.BIRCH_PLANKS), 4L);
    assertEquals(5L, pool.total());
    assertEquals(4L, pool.matches(Ingredient.of(Items.BIRCH_PLANKS)));
    assertEquals(0L, pool.matches(Ingredient.of(Items.OAK_PLANKS)));
    assertEquals(2, pool.size());
  }

  @Test
  void marksNestAndOnlyUndoTheirOwnWork() {
    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.STICK), 10L);

    int outer = pool.mark();
    pool.take(stack(Items.STICK), 4L);

    int inner = pool.mark();
    pool.add(stack(Items.OAK_PLANKS), 5L);
    pool.rollback(inner);
    assertEquals(6L, pool.amount(stack(Items.STICK)));
    assertEquals(0L, pool.amount(stack(Items.OAK_PLANKS)));

    pool.rollback(outer);
    assertEquals(10L, pool.amount(stack(Items.STICK)));
    assertEquals(10L, pool.matches(Ingredient.of(Items.STICK)));
  }

  @Test
  void aSlotIsFilledInPileOrder() {
    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.OAK_PLANKS), 1L);
    pool.add(stack(Items.BIRCH_PLANKS), 1L);
    pool.add(stack(Items.OAK_PLANKS), 1L);
    pool.add(stack(Items.SPRUCE_PLANKS), 1L);

    Ingredient anyPlanks = Ingredient.of(
        Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS);
    assertEquals(4L, pool.matches(anyPlanks));
    assertEquals(3L, pool.takeMatching(anyPlanks, 3L));

    // The first three entries of the pile go, whatever item each of them holds.
    assertEquals(0L, pool.amount(stack(Items.OAK_PLANKS)));
    assertEquals(0L, pool.amount(stack(Items.BIRCH_PLANKS)));
    assertEquals(1L, pool.amount(stack(Items.SPRUCE_PLANKS)));
  }

  @Test
  void anEntryThatRanOutIsSkippedByLaterTakes() {
    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.OAK_PLANKS), 2L);
    pool.add(stack(Items.BIRCH_PLANKS), 3L);

    Ingredient anyPlanks = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS);
    assertEquals(5L, pool.matches(anyPlanks));
    assertEquals(2L, pool.takeMatching(anyPlanks, 2L));

    // The entry that ran out is skipped, and the rest of the pile still answers.
    assertEquals(0L, pool.matches(Ingredient.of(Items.OAK_PLANKS)));
    assertEquals(3L, pool.matches(anyPlanks));
    assertEquals(3L, pool.takeMatching(anyPlanks, 5L));
    assertEquals(0L, pool.matches(anyPlanks));
  }

  @Test
  void aCopyCarriesMatchesAndRollsBackOnItsOwn() {
    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.OAK_LOG), 4L);
    pool.add(stack(Items.STICK), 1L);
    pool.keep(stack(Items.STICK), 1L);

    UltsCraftPool copy = pool.copy();
    assertEquals(4L, copy.matches(Ingredient.of(Items.OAK_LOG)));
    assertEquals(0L, copy.matches(Ingredient.of(Items.STICK)));
    assertEquals(5L, copy.total());

    int mark = copy.mark();
    copy.add(stack(Items.OAK_PLANKS), 3L);
    copy.rollback(mark);
    assertEquals(0L, copy.matches(Ingredient.of(Items.OAK_PLANKS)));
    assertEquals(5L, copy.total());
    assertEquals(5L, pool.total());

    copy.copyFrom(pool);
    assertEquals(5L, copy.total());
    assertEquals(0L, copy.matches(Ingredient.of(Items.OAK_PLANKS)));
  }

  @Test
  void viewsOnlyReportWhatIsReallyThere() {
    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.OAK_LOG), 2L);
    pool.add(stack(Items.STICK), 3L);
    pool.take(stack(Items.OAK_LOG), 2L);

    List<UltsStoredView> views = pool.views();
    assertEquals(1, views.size());
    assertEquals(Items.STICK, views.getFirst().template().getItem());
    assertEquals(3L, views.getFirst().amount());
  }

  @Test
  void aPileIsBuiltFromViews() {
    List<UltsStoredView> views = List.of(
        new UltsStoredView(stack(Items.OAK_LOG), 2L, false),
        new UltsStoredView(stack(Items.OAK_LOG), 3L, false),
        new UltsStoredView(stack(Items.STICK), 1L, false));

    UltsCraftPool pool = UltsCraftPool.of(views);
    assertEquals(5L, pool.amount(stack(Items.OAK_LOG)));
    assertEquals(6L, pool.total());
    assertEquals(2, pool.size());
  }

  private static List<Long> amounts(UltsCraftPool pool) {
    List<Long> amounts = new ArrayList<>(pool.size());
    for (int index = 0; index < pool.size(); index++) {
      amounts.add(pool.amountAt(index));
    }
    return amounts;
  }
}
