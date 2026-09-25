package com.flwolfy.ults.crafting;

import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.state.UltsStoredView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UltsCraftResolverTest {

  private UltsCraftSource catalogue;

  @BeforeAll
  static void boot() {
    UltsTestBootstrap.boot();
  }

  @BeforeEach
  void keepTheCatalogue() {
    catalogue = UltsCraftResolver.source;
  }

  @AfterEach
  void restoreTheCatalogue() {
    UltsCraftResolver.source = catalogue;
  }

  @Test
  void aChainOfRecipesIsFollowedAllTheWayDown() {
    TestCatalog catalog = new TestCatalog();
    catalog.recipe(1, stack(Items.OAK_PLANKS), Ingredient.of(Items.OAK_LOG));
    catalog.recipe(1, stack(Items.STICK),
        Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS));
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.OAK_LOG), 4L);

    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);
    assertTrue(view.craftable(stack(Items.OAK_PLANKS)));
    assertEquals(4L, view.capacity(stack(Items.OAK_PLANKS)));
    // Each run of the stick recipe needs two planks, so four planks are two sticks.
    assertEquals(2L, view.capacity(stack(Items.STICK)));
    assertFalse(view.craftable(stack(Items.DIAMOND_BLOCK)));
    assertEquals(0L, view.capacity(stack(Items.DIAMOND_BLOCK)));
  }

  @Test
  void aRecipeNeedsItsStationToBeStored() {
    TestCatalog catalog = new TestCatalog();
    catalog.recipe(4, stack(Items.OAK_PLANKS), Ingredient.of(Items.OAK_LOG));
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.OAK_LOG), 1L);

    assertEquals(0L, UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true)
        .capacity(stack(Items.OAK_PLANKS)));
    assertEquals(4L, UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false)
        .capacity(stack(Items.OAK_PLANKS)));

    pool.add(stack(Items.CRAFTING_TABLE), 1L);
    assertEquals(4L, UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true)
        .capacity(stack(Items.OAK_PLANKS)));
  }

  @Test
  void onlyABoxMayBeCraftedWhenOnlyABoxIsAskedFor() {
    TestCatalog catalog = new TestCatalog();
    catalog.recipe(1, stack(Items.CHEST), Ingredient.of(Items.OAK_PLANKS));
    catalog.recipe(1, stack(Items.SHULKER_BOX), Ingredient.of(Items.CHEST));
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.OAK_PLANKS), 8L);

    UltsCraftResolver view =
        UltsCraftResolver.of(pool, UltsCraftingMode.SHULKER_BOXES_ONLY, false);
    // A chest may be made on the way to a box, but never asked for on its own in this mode.
    assertFalse(view.craftable(stack(Items.CHEST)));
    assertEquals(0L, view.capacity(stack(Items.CHEST)));
    assertTrue(view.craftable(stack(Items.SHULKER_BOX)));
    assertEquals(8L, view.capacity(stack(Items.SHULKER_BOX)));
  }

  @Test
  void craftingIsOffWhileTheModeIsOff() {
    TestCatalog catalog = new TestCatalog();
    catalog.recipe(4, stack(Items.OAK_PLANKS), Ingredient.of(Items.OAK_LOG));
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.OAK_LOG), 1L);

    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.DISABLED, false);
    assertFalse(view.craftable(stack(Items.OAK_PLANKS)));
    assertEquals(0L, view.capacity(stack(Items.OAK_PLANKS)));
    assertNull(view.plan(stack(Items.OAK_PLANKS), 1L));
  }

  @Test
  void anItemNoChainOfRecipesCanReachIsNeverCraftable() {
    TestCatalog catalog = new TestCatalog();
    List<Item> decoys = pick(Items.BEDROCK, 300, Items.CHEST, Items.OAK_PLANKS);
    for (Item decoy : decoys) {
      // Nothing in the pile can ever lead to these, so none of them is worth walking into.
      catalog.recipe(1, stack(decoy), Ingredient.of(Items.DRAGON_EGG));
    }
    catalog.recipe(1, stack(Items.CHEST), Ingredient.of(Items.OAK_PLANKS));
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.OAK_PLANKS), 1L);

    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);
    for (Item decoy : decoys) {
      assertFalse(view.craftable(stack(decoy)));
      assertEquals(0L, view.capacity(stack(decoy)));
    }
    assertTrue(view.craftable(stack(Items.CHEST)));
    assertEquals(1L, view.capacity(stack(Items.CHEST)));
  }

  @Test
  void aChainDeeperThanTheSearchIsNotFollowed() {
    TestCatalog catalog = new TestCatalog();
    List<Item> ladder = pick(Items.STONE, 14, Items.OAK_LOG, Items.DRAGON_EGG);
    UltsCraftResolver.source = catalog;

    Item previous = Items.OAK_LOG;
    for (Item next : ladder) {
      catalog.recipe(1, stack(next), Ingredient.of(previous));
      previous = next;
    }

    UltsCraftPool pool = new UltsCraftPool(4);
    pool.add(stack(Items.OAK_LOG), 1L);
    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);

    // A handful of steps are followed all the way down to the log...
    assertTrue(view.craftable(stack(ladder.get(4))));
    assertEquals(1L, view.capacity(stack(ladder.get(4))));
    // ...while a chain far longer than the search is never offered at all.
    assertFalse(view.craftable(stack(ladder.getLast())));
    assertEquals(0L, view.capacity(stack(ladder.getLast())));
  }

  @Test
  void askingForAnAmountNeverChangesThePile() {
    TestCatalog catalog = new TestCatalog();
    catalog.recipe(4, stack(Items.OAK_PLANKS), Ingredient.of(Items.OAK_LOG));
    catalog.recipe(4, stack(Items.STICK),
        Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS));
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.OAK_LOG), 4L);
    pool.add(stack(Items.STICK), 1L);
    List<UltsStoredView> before = pool.views();
    int mark = pool.mark();

    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);
    view.capacity(stack(Items.OAK_PLANKS));
    view.capacity(stack(Items.STICK));
    view.capacity(stack(Items.DIAMOND_BLOCK));
    view.capacity(stack(Items.STICK));

    assertEquals(before.size(), pool.views().size());
    for (int index = 0; index < before.size(); index++) {
      assertEquals(before.get(index).amount(), pool.views().get(index).amount());
    }
    // Every attempt the search made was rolled back, so the pile carries no leftovers.
    assertEquals(mark, pool.mark());
  }

  @Test
  void aPlanLeavesThePileHoldingWhatItsRunsMade() {
    TestCatalog catalog = new TestCatalog();
    catalog.recipe(4, stack(Items.OAK_PLANKS), Ingredient.of(Items.OAK_LOG));
    catalog.recipe(4, stack(Items.STICK),
        Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS));
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.OAK_LOG), 2L);

    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);
    UltsCraftPlan plan = view.plan(stack(Items.STICK), 16L);
    assertNotNull(plan);
    assertEquals(16L, plan.output());
    assertEquals(16L, pool.amount(stack(Items.STICK)));
    assertEquals(0L, pool.amount(stack(Items.OAK_LOG)));
    assertEquals(0L, pool.amount(stack(Items.OAK_PLANKS)));
  }

  @Test
  void aPlanThatCannotBeMadeLeavesThePileAlone() {
    TestCatalog catalog = new TestCatalog();
    catalog.recipe(4, stack(Items.OAK_PLANKS), Ingredient.of(Items.OAK_LOG));
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.OAK_LOG), 1L);

    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);
    assertNull(view.plan(stack(Items.OAK_PLANKS), 64L));
    assertEquals(1L, pool.amount(stack(Items.OAK_LOG)));
    assertEquals(0L, pool.amount(stack(Items.OAK_PLANKS)));
  }

  @Test
  void aWideGraphWithManyDeadEndsIsStillAnsweredAtOnce() {
    // This is the shape that used to kill the server: every recipe slot also names hundreds of items
    // that no chain of recipes can reach from this pile. A search that walks them all is hopeless;
    // one that drops them beforehand answers immediately.
    TestCatalog catalog = new TestCatalog();
    List<Item> deadEnds = pick(Items.BEDROCK, 600, Items.OAK_LOG, Items.DRAGON_EGG);
    for (Item deadEnd : deadEnds) {
      catalog.recipe(1, stack(deadEnd), Ingredient.of(Items.DRAGON_EGG));
    }
    List<Item> ladder = pick(Items.DIAMOND, 9 * 6, Items.OAK_LOG, Items.DRAGON_EGG);

    UltsCraftPool pool = new UltsCraftPool(1024);
    for (Item material : pick(Items.GOLD_INGOT, 500, Items.OAK_LOG, Items.DRAGON_EGG)) {
      pool.add(stack(material), 5L);
    }
    pool.add(stack(Items.OAK_LOG), 640L);

    Item[] previous = null;
    for (int level = 0; level < 9; level++) {
      Item[] current = new Item[6];
      for (int slot = 0; slot < current.length; slot++) {
        current[slot] = ladder.get(level * current.length + slot);
      }
      if (previous == null) {
        for (Item produced : current) {
          catalog.recipe(1, stack(produced), Ingredient.of(Items.OAK_LOG));
        }
      } else {
        // One slot that also names six hundred items nothing can make, every step of the way.
        List<Item> named = new ArrayList<>(List.of(previous));
        named.addAll(deadEnds);
        Ingredient wide = Ingredient.of(named.stream());
        for (Item produced : current) {
          catalog.recipe(1, stack(produced), wide);
        }
      }
      previous = current;
    }

    UltsCraftResolver.source = catalog;
    long started = System.nanoTime();
    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);
    long total = 0L;
    for (int index = 0; index < ladder.size(); index++) {
      total += view.capacity(stack(ladder.get(index)));
    }
    long millis = (System.nanoTime() - started) / 1_000_000L;

    assertTrue(total > 0L, "the ladder has to be craftable at all");
    // Every one of these asks the hardest question there is: nine recipes deep, with six hundred dead
    // ends named at every step of the way. A search that walks the dead ends never finishes at all; a
    // bounded one finishes, and the bound is what this pins down.
    assertTrue(millis < 2_000L, "a wide graph took " + millis + "ms to answer");
  }

  @Test
  void anItemThatWantsMoreThanThePileHoldsIsNotCraftable() {
    TestCatalog catalog = new TestCatalog();
    Ingredient planks = Ingredient.of(Items.OAK_PLANKS);
    catalog.recipe(1, stack(Items.CHEST),
        planks, planks, planks, planks, planks, planks, planks, planks);
    UltsCraftResolver.source = catalog;

    // Planks are reachable either way, but one run wants eight of them and three is not eight.
    UltsCraftPool three = new UltsCraftPool(8);
    three.add(stack(Items.OAK_PLANKS), 3L);
    UltsCraftResolver short_ = UltsCraftResolver.of(three, UltsCraftingMode.ALL, false);
    assertFalse(short_.craftable(stack(Items.CHEST)));
    assertEquals(0L, short_.capacity(stack(Items.CHEST)));

    UltsCraftPool eight = new UltsCraftPool(8);
    eight.add(stack(Items.OAK_PLANKS), 8L);
    UltsCraftResolver plenty = UltsCraftResolver.of(eight, UltsCraftingMode.ALL, false);
    assertTrue(plenty.craftable(stack(Items.CHEST)));
    assertEquals(1L, plenty.capacity(stack(Items.CHEST)));
  }

  @Test
  void aListingAsksAboutEveryRowAtOnce() {
    // The shape of opening the storage screen on a large pack: one question per catalogue row, every
    // one of them cheap enough that a screen can ask them all without the server noticing.
    TestCatalog catalog = new TestCatalog();
    List<Item> universe = pick(Items.AIR, 900, Items.OAK_LOG, Items.DRAGON_EGG);
    UltsCraftPool pool = new UltsCraftPool(1024);
    for (int index = 0; index < 300; index++) {
      pool.add(stack(universe.get(index)), 8L);
    }
    for (int index = 300; index < universe.size(); index++) {
      catalog.recipe(1, stack(universe.get(index)),
          Ingredient.of(universe.get(index * 7 % 300)),
          Ingredient.of(universe.get(index * 13 % 300)));
    }
    UltsCraftResolver.source = catalog;

    long started = System.nanoTime();
    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);
    int craftable = 0;
    for (int index = 300; index < universe.size(); index++) {
      if (view.craftable(stack(universe.get(index)))) {
        craftable++;
      }
    }
    long millis = (System.nanoTime() - started) / 1_000_000L;

    assertEquals(universe.size() - 300, craftable);
    assertTrue(millis < 400L, "a listing took " + millis + "ms");
  }

  @Test
  void aRecipeTheServerAddedIsCraftedLikeAnyOther() {
    // A recipe no version of the game ships, the way a data pack adds one: one dragon egg turns into
    // one budding amethyst. It is catalogued like every other recipe, so a stored egg makes it real.
    TestCatalog catalog = new TestCatalog();
    catalog.recipe(1, stack(Items.BUDDING_AMETHYST), Ingredient.of(Items.DRAGON_EGG));
    UltsCraftResolver.source = catalog;

    UltsCraftPool eggs = new UltsCraftPool(8);
    eggs.add(stack(Items.DRAGON_EGG), 3L);
    UltsCraftResolver view = UltsCraftResolver.of(eggs, UltsCraftingMode.ALL, false);
    assertTrue(view.craftable(stack(Items.BUDDING_AMETHYST)));
    assertEquals(3L, view.capacity(stack(Items.BUDDING_AMETHYST)));

    // The same recipe with nothing to feed it stays out of reach.
    UltsCraftPool stones = new UltsCraftPool(8);
    stones.add(stack(Items.STONE), 5L);
    assertFalse(UltsCraftResolver.of(stones, UltsCraftingMode.ALL, false)
        .craftable(stack(Items.BUDDING_AMETHYST)));

    // And a crafting recipe still needs its station while the storage is asked like the screen asks.
    UltsCraftPool one = new UltsCraftPool(8);
    one.add(stack(Items.DRAGON_EGG), 1L);
    assertEquals(0L, UltsCraftResolver.of(one, UltsCraftingMode.ALL, true)
        .capacity(stack(Items.BUDDING_AMETHYST)));
    one.add(stack(Items.CRAFTING_TABLE), 1L);
    assertEquals(1L, UltsCraftResolver.of(one, UltsCraftingMode.ALL, true)
        .capacity(stack(Items.BUDDING_AMETHYST)));
  }

  @Test
  void aRecipeThatFeedsOnWhatItMakesComesBackWithAnAnswer() {
    TestCatalog catalog = new TestCatalog();
    // A ring of three, where the last one wants what the first one makes.
    catalog.recipe(1, stack(Items.GOLD_BLOCK), Ingredient.of(Items.DIAMOND_BLOCK));
    catalog.recipe(1, stack(Items.DIAMOND_BLOCK), Ingredient.of(Items.IRON_BLOCK));
    catalog.recipe(1, stack(Items.IRON_BLOCK), Ingredient.of(Items.GOLD_BLOCK));
    // And a recipe that names the very thing it makes.
    catalog.recipe(1, stack(Items.EMERALD_BLOCK), Ingredient.of(Items.EMERALD_BLOCK));
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.IRON_BLOCK), 1L);
    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);

    // The ring is walked once and then closes on itself, which is where the walk stops.
    assertEquals(1L, view.capacity(stack(Items.GOLD_BLOCK)));
    assertEquals(1L, view.capacity(stack(Items.DIAMOND_BLOCK)));
    assertEquals(1L, view.capacity(stack(Items.IRON_BLOCK)));
    // Nothing can make what it is made of out of nothing, so it is never reached at all.
    assertFalse(view.craftable(stack(Items.EMERALD_BLOCK)));
    assertEquals(0L, view.capacity(stack(Items.EMERALD_BLOCK)));
  }

  @Test
  void aCycleLongerThanTheSearchEndsToo() {
    TestCatalog catalog = new TestCatalog();
    List<Item> ring = pick(Items.LAPIS_BLOCK, 40, Items.OAK_LOG, Items.DRAGON_EGG);
    for (int index = 0; index < ring.size(); index++) {
      catalog.recipe(1, stack(ring.get(index)),
          Ingredient.of(ring.get((index + 1) % ring.size())));
    }
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(ring.getFirst()), 1L);
    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);

    // Whatever it answers, it has to come back: a ring of forty is walked a handful of steps deep and
    // then given up on, rather than followed until the stack runs out.
    for (Item item : ring) {
      view.craftable(stack(item));
      view.capacity(stack(item));
    }
  }

  @Test
  void aQuestionOutOfTimeSaysWhatThePileCanReachAndOwesTheRest() {
    TestCatalog catalog = new TestCatalog();
    Ingredient planks = Ingredient.of(Items.OAK_PLANKS);
    catalog.recipe(1, stack(Items.CHEST),
        planks, planks, planks, planks, planks, planks, planks, planks);
    UltsCraftResolver.source = catalog;

    UltsCraftPool pool = new UltsCraftPool(8);
    pool.add(stack(Items.OAK_PLANKS), 3L);
    UltsCraftResolver view = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, false);
    long past = System.nanoTime() - 1L;

    // Out of time the row is given the answer the pile's reach gives it, and the exact one is owed...
    assertTrue(view.craftable(stack(Items.CHEST), past));
    assertTrue(view.ranOut());
    assertEquals(UltsCraftResolver.UNKNOWN, view.capacity(stack(Items.CHEST), past));

    // ...so a later tick still gets to work out that eight planks out of three is not a chest.
    assertFalse(view.craftable(stack(Items.CHEST)));
    assertFalse(view.ranOut());
    assertEquals(0L, view.capacity(stack(Items.CHEST)));
  }

  /** Items of the vanilla registry that a test may use as its own, never air or a reserved one. */
  private static List<Item> pick(Item start, int count, Item... reserved) {
    Set<Item> skip = Set.of(reserved);
    List<Item> picked = new ArrayList<>(count);
    boolean started = false;
    for (Item item : BuiltInRegistries.ITEM) {
      if (!started) {
        started = item == start;
        continue;
      }
      if (item != Items.AIR && !skip.contains(item) && !picked.contains(item)) {
        picked.add(item);
      }
      if (picked.size() == count) {
        break;
      }
    }
    assertEquals(count, picked.size(), "the vanilla registry has to be big enough for this test");
    return picked;
  }

  /** A catalogue of a handful of recipes, so the search can be tested without a server. */
  private static final class TestCatalog implements UltsCraftSource {

    private final Map<Item, List<UltsCraftRecipe>> byResult = new HashMap<>();
    private final List<UltsCraftRecipe> all = new ArrayList<>();
    private final Map<String, ItemStack> producible = new LinkedHashMap<>();
    private final Map<Ingredient, List<ItemStack>> candidates = new HashMap<>();

    void recipe(int outputCount, ItemStack result, Ingredient... ingredients) {
      UltsCraftRecipe recipe = new UltsCraftRecipe(
          false, "test:" + all.size(), List.of(ingredients), result, outputCount);
      byResult.computeIfAbsent(result.getItem(), key -> new ArrayList<>()).add(recipe);
      all.add(recipe);
      producible.putIfAbsent(
          BuiltInRegistries.ITEM.getKey(result.getItem()) + "|" + result.getComponentsPatch(),
          recipe.result());
    }

    @Override
    public List<UltsCraftRecipe> recipes(ItemStack template) {
      List<UltsCraftRecipe> matching = new ArrayList<>();
      for (UltsCraftRecipe recipe : byResult.getOrDefault(template.getItem(), List.of())) {
        if (ItemStack.isSameItemSameComponents(recipe.result(), template)) {
          matching.add(recipe);
        }
      }
      return List.copyOf(matching);
    }

    @Override
    public List<ItemStack> candidates(Ingredient ingredient) {
      return candidates.computeIfAbsent(ingredient, slot -> {
        List<ItemStack> matching = new ArrayList<>();
        for (ItemStack stack : producible.values()) {
          if (slot.test(stack)) {
            matching.add(stack);
          }
        }
        return List.copyOf(matching);
      });
    }

    @Override
    public List<UltsCraftRecipe> everything() {
      return List.copyOf(all);
    }
  }
}
