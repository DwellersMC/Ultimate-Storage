package com.flwolfy.ults.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import com.flwolfy.ults.data.config.UltsItemVisibility;
import com.flwolfy.ults.data.lang.UltsItemNames;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.util.UltsTextBuilder;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.Test;

class UltsStorageSGuiTest {

  @Test
  @org.junit.jupiter.api.Tag("acceptance")
  void specialRowsAndTakeAllBagTotalsNeverOverflowIntoNegativeAmounts() {
    UltsTestBootstrap.boot();
    var sword = named(Items.DIAMOND_SWORD, "first");
    var rows = List.of(new UltsStoredView(sword, Long.MAX_VALUE, true, 1L),
        new UltsStoredView(named(Items.DIAMOND_SWORD, "second"), 64L, true, 2L));
    var combined = UltsStorageSGUI.specialRows(rows).getFirst();
    assertEquals(Long.MAX_VALUE, combined.amount());
    assertEquals(2L, combined.updatedAt());
    assertEquals(Long.MAX_VALUE, UltsTakeAllSGUI.bagStock(Items.DIAMOND_SWORD, rows).total());
  }

  @Test
  void theSpecialCategoryIsOneRowPerItemNewestFirst() {
    UltsTestBootstrap.boot();
    ItemStack sword = named(Items.DIAMOND_SWORD, "一号剑");
    ItemStack book = new ItemStack(Items.WRITTEN_BOOK);

    List<UltsStoredView> stored = List.of(
        new UltsStoredView(sword, 1, true, 100L),
        // A plain item is not special, so the list it comes from never puts it on a special row.
        new UltsStoredView(new ItemStack(Items.STONE), 64, false, 400L),
        new UltsStoredView(book, 1, true, 200L),
        new UltsStoredView(named(Items.DIAMOND_SWORD, "二号剑"), 2, true, 300L));

    List<UltsStoredView> rows = UltsStorageSGUI.specialRows(stored);
    assertEquals(2, rows.size());
    // The newest arrival of a kind orders its row and stamps it, and the amounts add up.
    assertEquals(Items.DIAMOND_SWORD, rows.getFirst().template().getItem());
    assertEquals(3L, rows.getFirst().amount());
    assertEquals(300L, rows.getFirst().updatedAt());
    assertEquals(Items.WRITTEN_BOOK, rows.get(1).template().getItem());
    assertEquals(200L, rows.get(1).updatedAt());
    assertTrue(rows.stream().allMatch(UltsStoredView::special));
  }

  @Test
  void anEmptyStorageHasNoSpecialRows() {
    assertEquals(List.of(), UltsStorageSGUI.specialRows(List.of()));
    UltsTestBootstrap.boot();
    assertEquals(List.of(), UltsStorageSGUI.specialRows(
        List.of(new UltsStoredView(new ItemStack(Items.STONE), 64, false, 400L))));
  }

  @Test
  void aBagRowIsTheNewestArrivalOfEveryVariantOfOneItem() {
    UltsTestBootstrap.boot();
    List<UltsStoredView> stored = List.of(
        new UltsStoredView(named(Items.DIAMOND_SWORD, "一号剑"), 1, true, 100L),
        new UltsStoredView(named(Items.DIAMOND_SWORD, "二号剑"), 1, true, 300L),
        new UltsStoredView(new ItemStack(Items.WRITTEN_BOOK), 1, true, 200L),
        // A plain item is never a bag: it is a row of its own like any other.
        new UltsStoredView(new ItemStack(Items.STONE), 64, false, 400L));

    Map<Item, List<UltsStoredView>> bags = UltsStorageSGUI.bagRows(stored);
    assertEquals(2, bags.size());
    List<UltsStoredView> swords = bags.get(Items.DIAMOND_SWORD);
    assertEquals(2, swords.size());
    // Newest first: the row is drawn as the thing that arrived last, which is also what a quick click
    // on it hands over.
    assertEquals("二号剑", swords.getFirst().template().get(DataComponents.CUSTOM_NAME).getString());
    assertEquals("一号剑", swords.get(1).template().get(DataComponents.CUSTOM_NAME).getString());
    assertEquals(1, bags.get(Items.WRITTEN_BOOK).size());
    assertFalse(bags.containsKey(Items.STONE));
  }

  @Test
  void everyVariantOfOneItemSharesItsBag() {
    UltsTestBootstrap.boot();
    // Two differently enchanted pumpkins are two stacks, and one item: they belong to one bag, which
    // is what lets a row say "52 in this bag" and open onto both of them.
    ItemStack cursed = named(Items.PUMPKIN, "绑定诅咒的南瓜");
    ItemStack other = named(Items.PUMPKIN, "别的附魔南瓜");
    List<UltsStoredView> stored = List.of(
        new UltsStoredView(cursed, 3, true, 100L),
        new UltsStoredView(other, 12, true, 200L));

    Map<Item, List<UltsStoredView>> bags = UltsStorageSGUI.bagRows(stored);
    List<UltsStoredView> pumpkins = bags.get(Items.PUMPKIN);
    assertEquals(2, pumpkins.size());
    assertEquals(12L, pumpkins.getFirst().amount());
  }

  @Test
  void theLinesABagRowShowsAreTheNewestStacksOwn() {
    UltsTestBootstrap.boot();
    // The row is drawn as the stack that arrived last, and what it says about itself is that stack's own
    // tooltip: its name first and then what it carries. It is the same list a search reads, which is what
    // keeps the row, the search and the hover from ever describing one stack in three different ways.
    ItemStack newest = named(Items.NETHERITE_SWORD, "我的剑");
    newest.set(DataComponents.LORE,
        new ItemLore(List.of(Component.literal("第一行"), Component.literal("第二行"))));

    List<Component> lines = UltsItemNames.tooltip(newest);
    assertEquals(3, lines.size());
    assertEquals("我的剑", lines.getFirst().getString());
    assertEquals("第一行", lines.get(1).getString());
    assertEquals("第二行", lines.get(2).getString());
    assertEquals(UltsItemNames.tooltip(newest).size(), lines.size());
  }

  @Test
  void aFilteredBagRowCountsWhatOpeningTheBagWillLayOut() {
    UltsTestBootstrap.boot();
    List<UltsStoredView> group = List.of(
        new UltsStoredView(named(Items.ENCHANTED_BOOK, "锋利五"), 1, true, 100L),
        new UltsStoredView(named(Items.ENCHANTED_BOOK, "保护四"), 1, true, 200L),
        new UltsStoredView(named(Items.ENCHANTED_BOOK, "锋利三"), 1, true, 300L));

    // No search: the row counts the whole bag.
    assertEquals(3, UltsStorageSGUI.keptInBag(group, "", "zh_cn"));
    // A search: the number the row says is the number of stacks the bag screen lays out, because it is
    // the very code the bag screen draws with that answers it.
    assertEquals(UltsBagSGUI.shown(group, "锋利", "zh_cn").size(),
        UltsStorageSGUI.keptInBag(group, "锋利", "zh_cn"));
    assertEquals(2, UltsStorageSGUI.keptInBag(group, "锋利", "zh_cn"));
    assertEquals(0, UltsStorageSGUI.keptInBag(group, "南瓜", "zh_cn"));
  }

  @Test
  void theBagRowNamesItsItemInThatItemsOwnRarityColour() {
    UltsTestBootstrap.boot();
    // The item's name inside the title is drawn the way the game draws that item's name anywhere else: in
    // the colour of its rarity. The bag's own words keep the bag's colour, which is a different thing.
    assertEquals(Rarity.COMMON.color(), UltsStorageSGUI.nameColour(Items.DIAMOND_SWORD));

    // And it is the *item's* rarity, not the newest stack's: the title names the kind of thing the bag
    // holds, which does not change because the stack that arrived last happens to be enchanted or to carry
    // a rarity of its own.
    ItemStack dressed = new ItemStack(Items.DIAMOND_SWORD);
    dressed.set(DataComponents.RARITY, Rarity.EPIC);
    assertEquals(Rarity.EPIC, dressed.getRarity());
    assertEquals(Rarity.COMMON.color(), UltsStorageSGUI.nameColour(dressed.getItem()));
  }

  @Test
  void theBagRowTitleWearsAColourNothingElseOnTheRowCanBe() {
    // The title is the row's identity; the lines under it are what the row reports. A title drawn in one of
    // the colours those facts are drawn in reads as another fact about the stack rather than as the bag's
    // name — green did exactly that, because every number the row reports is green and a weapon's own
    // attribute lines are dark green — so the bag's own colour is kept outside all of them.
    int title = UltsStorageSGUI.TITLE_COLOUR.getValue();
    assertNotEquals(rgb(UltsTextBuilder.TEXT), title);
    assertNotEquals(rgb(UltsTextBuilder.HIGHLIGHT), title);
    assertNotEquals(rgb(UltsTextBuilder.SHADE), title);
    assertNotEquals(rgb(ChatFormatting.DARK_GREEN), title);

    // And outside the colours a stack's own name can be, which are the four rarities': a pink that was one
    // of them would put the bag's name and the stack's name in the same colour on the rows that matter
    // most, which is what the bag's words were given a colour of their own to avoid. The item's name inside
    // the brackets is not the bag's words, and is drawn in its rarity's colour on purpose.
    for (Rarity rarity : Rarity.values()) {
      assertNotEquals(rgb(rarity.color()), title,
          "the bag's colour is not the colour the " + rarity + " rarity paints a name with");
    }
    // The line itself is built through the server's language manager, which only a running game has, so
    // what a test can hold the row to is the colours its title is built from.
  }

  /** The colour a named colour really draws in. */
  private static int rgb(ChatFormatting colour) {
    return TextColor.fromLegacyFormat(colour).getValue();
  }

  private static ItemStack named(Item item, String name) {
    ItemStack stack = UltsTestBootstrap.stack(item);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    return stack;
  }

  @Test
  void aCategoryListsItsOwnEntriesAndNeverTheBags() {
    UltsTestBootstrap.boot();
    ItemStack plainSword = UltsTestBootstrap.stack(Items.IRON_SWORD);
    UltsItemCategory combat = new UltsItemCategory(
        "minecraft:combat", Component.empty(), "", plainSword,
        Map.of(Items.IRON_SWORD, List.of(plainSword)), List.of(plainSword), false);
    UltsItemCategory special = new UltsItemCategory(
        "ultimate-storage:special_nbt", Component.empty(), "", plainSword,
        Map.of(), List.of(), true);
    // A category shows what its own tabs list, and a stack no entry matches is a bag: the special
    // category is the only one that draws it.
    List<UltsStoredView> stored = List.of(
        new UltsStoredView(named(Items.IRON_SWORD, "我的剑"), 1, true, 100L),
        new UltsStoredView(named(Items.PUMPKIN, "袋子"), 1, true, 200L));
    Map<Item, List<UltsStoredView>> byItem = UltsStorageSGUI.index(stored);

    List<UltsStoredView> rows = UltsStorageSGUI.rows(
        combat, byItem, stored, UltsItemVisibility.ALL, "", "zh_cn", template -> false);
    assertEquals(1, rows.size());
    assertTrue(rows.stream().noneMatch(UltsStoredView::special));
    assertEquals(0L, rows.getFirst().amount());

    List<UltsStoredView> bags = UltsStorageSGUI.rows(
        special, byItem, stored, UltsItemVisibility.ALL, "", "zh_cn", template -> false);
    assertEquals(2, bags.size());
    assertTrue(bags.stream().allMatch(UltsStoredView::special));
  }

  @Test
  void aPlayerWhoNeverPickedAVisibilityGetsTheConfiguredDefault() {
    UltsTestBootstrap.boot();
    ItemStack plainStone = UltsTestBootstrap.stack(Items.STONE);
    UltsItemCategory blocks = new UltsItemCategory(
        "minecraft:building_blocks", Component.empty(), "", plainStone,
        Map.of(Items.STONE, List.of(plainStone)), List.of(plainStone), false);
    List<UltsStoredView> stored = List.of(new UltsStoredView(plainStone, 64, false, 100L));
    Map<Item, List<UltsStoredView>> byItem = UltsStorageSGUI.index(stored);

    // A saved view with no visibility of its own means the configured default, which is what the storage
    // screen would show that player. It used to be handed to the listing as it was, and every render of
    // a screen that asked — the search screen, on every keystroke — threw on the null.
    List<UltsStoredView> rows = UltsStorageSGUI.rows(
        blocks, byItem, stored, null, "", "en_us", template -> false);
    assertEquals(1, rows.size());
    assertEquals(64L, rows.getFirst().amount());
  }

  @Test
  void aSearchReadsTheCategoryItWasTypedIn() {
    UltsTestBootstrap.boot();
    ItemStack plainSword = UltsTestBootstrap.stack(Items.IRON_SWORD);
    ItemStack plainStone = UltsTestBootstrap.stack(Items.STONE);
    UltsItemCategory combat = new UltsItemCategory(
        "minecraft:combat", Component.empty(), "", plainSword,
        Map.of(Items.IRON_SWORD, List.of(plainSword)), List.of(plainSword), false);
    UltsItemCategory blocks = new UltsItemCategory(
        "minecraft:building_blocks", Component.empty(), "", plainStone,
        Map.of(Items.STONE, List.of(plainStone)), List.of(plainStone), false);
    List<UltsStoredView> stored = List.of(
        new UltsStoredView(named(Items.IRON_SWORD, "我的剑"), 1, false, 100L));
    Map<Item, List<UltsStoredView>> byItem = UltsStorageSGUI.index(stored);

    // "sword" names something in the combat tab, so it is found there and not in the blocks tab.
    List<UltsStoredView> found = UltsStorageSGUI.rows(
        combat, byItem, stored, UltsItemVisibility.ALL, "sword", "en_us", template -> false);
    assertFalse(found.isEmpty());
    assertTrue(found.stream().allMatch(row -> row.template().is(Items.IRON_SWORD)));
    assertEquals(0, UltsStorageSGUI.rows(
        blocks, byItem, stored, UltsItemVisibility.ALL, "sword", "en_us", template -> false).size());
  }

  @Test
  void aSearchInTheSpecialCategoryFindsABagByWhatIsInsideIt() {
    UltsTestBootstrap.boot();
    ItemStack plainSword = UltsTestBootstrap.stack(Items.IRON_SWORD);
    UltsItemCategory special = new UltsItemCategory(
        "ultimate-storage:special_nbt", Component.empty(), "", plainSword, Map.of(), List.of(), true);
    // The bag row is drawn as the newest arrival, whose name this search does not name: the bag is still
    // the one the search was after, because another sword inside it answers. Opening it lays out the
    // swords the search kept, which is the same answer in the same words.
    List<UltsStoredView> stored = List.of(
        new UltsStoredView(named(Items.DIAMOND_SWORD, "二号剑"), 1, true, 100L),
        new UltsStoredView(named(Items.DIAMOND_SWORD, "一号剑"), 1, true, 300L));
    Map<Item, List<UltsStoredView>> byItem = UltsStorageSGUI.index(stored);

    List<UltsStoredView> found = UltsStorageSGUI.rows(
        special, byItem, stored, UltsItemVisibility.ALL, "二号", "zh_cn", template -> false);
    assertEquals(1, found.size());
    assertTrue(found.getFirst().special());
    assertEquals(1, UltsBagSGUI.shown(stored, "二号", "zh_cn").size());
    assertEquals(0, UltsStorageSGUI.rows(
        special, byItem, stored, UltsItemVisibility.ALL, "南瓜", "zh_cn", template -> false).size());
  }
}
