package com.flwolfy.ults.display;

import com.flwolfy.ults.crafting.UltsCraftMath;
import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.config.UltsItemVisibility;
import com.flwolfy.ults.data.lang.UltsItemNames;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.data.state.UltsStackKinds;
import com.flwolfy.ults.data.state.UltsViewProfile;
import com.flwolfy.ults.data.state.UltsWithdrawalOutput;
import com.flwolfy.ults.data.state.UltsWithdrawalPlan;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;

public final class UltsStorageSGUI extends SimpleGui {

  private static final int CATEGORY_UP_SLOT = 0;
  private static final int[] CATEGORY_SLOTS = {9, 18, 27, 36};
  private static final int CATEGORY_PAGE_SIZE = CATEGORY_SLOTS.length;
  private static final int CATEGORY_DOWN_SLOT = 45;
  private static final int PREVIOUS_PAGE_SLOT = 2;
  private static final int STATUS_SLOT = 5;
  private static final int NEXT_PAGE_SLOT = 8;
  /**
   * The colour of a bag row's title: a bright pink of the mod's own, not one of the game's named colours.
   *
   * <p>It has to be a colour nothing else on the row is drawn in, or the bag's name reads as one more fact
   * about the stack rather than as the name of the row. Every colour the row uses is therefore spoken for:
   * yellow for the label, the rule and every other label, green for every number, dark green for the lines
   * the game writes about a weapon, grey for the hint. Green was tried as the title's colour and failed
   * exactly that way — the block under the title is full of greens, so the title read as another number.
   *
   * <p>The game's own colours are spoken for as well, by the name of the stack underneath: it is painted
   * from the stack's rarity, which is white, yellow, aqua or light purple and nothing else. A named pink
   * would be the light purple an epic stack already wears, so the pink is mixed here instead:
   * {@code #FF88CC}, redder and softer than that purple, and not a colour any vanilla text uses.
   */
  static final TextColor TITLE_COLOUR = TextColor.fromRgb(0xFF88CC);

  private static final int[] CONTENT_SLOTS = {
      11, 12, 13, 14, 15, 16, 17,
      20, 21, 22, 23, 24, 25, 26,
      29, 30, 31, 32, 33, 34, 35,
      38, 39, 40, 41, 42, 43, 44,
      47, 48, 49, 50, 51, 52, 53
  };
  private static final long SHULKER_SLOTS = UltsTakeHints.SHULKER_SLOTS;
  /** Newest first, which is the order the special category shows its rows in. */
  private static final Comparator<UltsStoredView> NEWEST_FIRST =
      Comparator.comparingLong(UltsStoredView::updatedAt).reversed();
  private static final List<WeakReference<UltsStorageSGUI>> OPEN_MENUS = new ArrayList<>();
  private final UltsRuntime runtime;
  private UltsItemCategory category;
  private int categoryPage;
  private int itemPage;
  private String filter;
  /** The visibility this player picked for themselves, or {@code null} while the default applies. */
  private UltsItemVisibility chosenVisibility;
  private long renderedRevision = -1;

  private UltsStorageSGUI(ServerPlayer player, UltsRuntime runtime) {
    super(MenuType.GENERIC_9x6, player, false);
    this.runtime = runtime;
    UltsViewProfile profile = runtime.state().viewProfile(player.getUUID());
    category = UltsCreativeCatalog.category(profile.categoryId());
    categoryPage = profile.categoryPage();
    itemPage = profile.itemPage();
    filter = profile.filter();
    chosenVisibility = profile.visibility();
    setTitle(UltsGuiText.text("ults.gui.title"));
    setLockPlayerInventory(true);
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.add(new WeakReference<>(this));
    }
    render();
  }

  /**
   * Opens the listing for a player.
   *
   * @param player the player looking at their storage
   * @param runtime the storage
   * @return the screen that was opened, so a caller can look at what it says
   */
  public static UltsStorageSGUI open(ServerPlayer player, UltsRuntime runtime) {
    UltsStorageSGUI screen = new UltsStorageSGUI(player, runtime);
    screen.open();
    return screen;
  }

  public static void refreshAll(UltsRuntime runtime) {
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.removeIf(reference -> {
        UltsStorageSGUI gui = reference.get();
        if (gui == null || !gui.isOpen()) {
          return true;
        }
        if (gui.runtime == runtime
            && (gui.renderedRevision != runtime.contentRevision()
                || runtime.craftablePending())) {
          gui.render();
        }
        return false;
      });
    }
  }

  private void render() {
    renderedRevision = runtime.contentRevision();
    GuiElementBuilder filler = element(Items.STAINED_GLASS_PANE.gray()).setName(Component.empty());
    for (int slot = 0; slot < getVirtualSize(); slot++) {
      setSlot(slot, filler.build());
    }
    GuiElementBuilder divider = element(Items.STAINED_GLASS_PANE.brown())
        .setName(Component.empty());
    for (int row = 0; row < 6; row++) {
      setSlot(row * 9 + 1, divider.build());
    }
    GuiElementBuilder content = element(Items.STAINED_GLASS_PANE.black())
        .setName(Component.empty());
    for (int slot : CONTENT_SLOTS) {
      setSlot(slot, content.build());
    }
    // Category cells a page does not fill stay black as well.
    for (int slot : CATEGORY_SLOTS) {
      setSlot(slot, content.build());
    }

    UltsItemVisibility visibility = effectiveVisibility();
    List<UltsStoredView> stored = runtime.storedItems();
    Map<Item, List<UltsStoredView>> byItem = index(stored);
    Map<Item, List<UltsStoredView>> bags = bagRows(stored);
    // One question for the whole screen: whether there is a box to fill at all is a fact about the
    // storage, not about a row, so the rows do not each ask it again.
    List<UltsItemCategory> categories = visibleCategories(visibility, byItem, stored);
    if (category == null && !categories.isEmpty()) {
      category = categories.getFirst();
    }
    int selectedIndex = selectedCategoryIndex(categories);
    if (selectedIndex < 0 && !categories.isEmpty()) {
      category = categories.getFirst();
      selectedIndex = 0;
      itemPage = 0;
    }

    int categoryPages = Math.max(
        1, (categories.size() + CATEGORY_PAGE_SIZE - 1) / CATEGORY_PAGE_SIZE);
    categoryPage = Math.floorMod(categoryPage, categoryPages);
    // True paging: every page starts after the previous one, unfilled cells stay black.
    int firstCategory = categoryPage * CATEGORY_PAGE_SIZE;
    int visibleCategories = Math.max(
        0, Math.min(CATEGORY_PAGE_SIZE, categories.size() - firstCategory));
    for (int index = 0; index < visibleCategories; index++) {
      setSlot(CATEGORY_SLOTS[index], categoryButton(categories.get(firstCategory + index)));
    }
    // The category arrows are always shown, whatever the number of categories.
    setSlot(CATEGORY_UP_SLOT, arrow(
        "MHF_ArrowUp", "ults.gui.category.previous", () -> changeCategoryPage(-1, categoryPages),
        "ults.gui.category.page", categoryPage + 1, categoryPages));
    setSlot(CATEGORY_DOWN_SLOT, arrow(
        "MHF_ArrowDown", "ults.gui.category.next", () -> changeCategoryPage(1, categoryPages),
        "ults.gui.category.page", categoryPage + 1, categoryPages));

    List<UltsStoredView> items = listedItems(category, byItem, stored, visibility);
    int itemPages = Math.max(1, (items.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
    itemPage = Math.floorMod(itemPage, itemPages);
    int firstItem = itemPage * CONTENT_SLOTS.length;
    int lastItem = Math.min(firstItem + CONTENT_SLOTS.length, items.size());
    for (int index = firstItem; index < lastItem; index++) {
      setSlot(CONTENT_SLOTS[index - firstItem],
          itemButton(items.get(index), stored, bags));
    }
    if (items.isEmpty()) {
      // Nothing to show: the middle of the item area says so instead of staying empty.
      setSlot(CONTENT_SLOTS[CONTENT_SLOTS.length / 2], element(Items.PAPER)
          .setName(UltsGuiText.text("ults.gui.empty").copy().withStyle(ChatFormatting.GRAY))
          .build());
    }

    // A list that fits in one page has nothing to turn to, so the arrows are left out completely.
    if (itemPages > 1) {
      setSlot(PREVIOUS_PAGE_SLOT, arrow(
          "MHF_ArrowLeft", "ults.gui.previous", () -> changeItemPage(-1, itemPages),
          "ults.gui.page", itemPage + 1, itemPages));
      setSlot(NEXT_PAGE_SLOT, arrow(
          "MHF_ArrowRight", "ults.gui.next", () -> changeItemPage(1, itemPages),
          "ults.gui.page", itemPage + 1, itemPages));
    }
    setSlot(STATUS_SLOT, statusButton(
        selectedIndex, categories.size(), items, stored,
        categoryPage, categoryPages, itemPages));
    saveView();
  }

  private List<UltsItemCategory> visibleCategories(
      UltsItemVisibility visibility,
      Map<Item, List<UltsStoredView>> byItem,
      List<UltsStoredView> stock
  ) {
    List<UltsItemCategory> categories = UltsCreativeCatalog.categories();
    if (visibility == UltsItemVisibility.ALL) {
      return categories;
    }
    // The "everything" overview and the stored data items always stay reachable, whatever the
    // visibility mode hides; an empty one says so with the paper in the middle of its item area.
    return categories.stream()
        .filter(value -> UltsCreativeCatalog.ALL_ID.equals(value.id())
            || UltsCreativeCatalog.SPECIAL_ID.equals(value.id())
            || value.templates().stream().anyMatch(template -> listed(
                visibility, template, amountOf(byItem, template), stock,
                candidate -> runtime.craftableNow(candidate, stock))))
        .toList();
  }

  /**
   * Whether a catalogued entry is listed in this mode, whatever its stock is.
   *
   * <p>Survival shows an item through its base form only: when the catalog holds the item without any
   * component, that is the one row (a painting with a certain picture is not an item, the painting
   * is). An item that only exists as variants is listed through those variants, and which of them
   * survival can really produce is decided per type afterwards.
   */
  private static boolean listed(
      UltsItemVisibility visibility,
      ItemStack template,
      long amount,
      List<UltsStoredView> stock,
      java.util.function.Predicate<ItemStack> craftableNow
  ) {
    if (amount > 0) {
      return true;
    }
    return switch (visibility) {
      case ALL -> true;
      case SURVIVAL -> base(template);
      // Everything the storage can hand over right now: an item that can be crafted at this moment
      // counts as well, which needs the crafting mode to be on and a station to be stored. This is
      // asked about every row of every category, so it is answered from the view of the pile.
      case AVAILABLE -> craftableNow.test(template);
    };
  }

  /** Whether this entry may represent its item in the survival view. */
  private static boolean base(ItemStack template) {
    if (!UltsSurvivalItems.obtainable(template)) {
      return false;
    }
    if (UltsCreativeCatalog.plainTemplate(template.getItem()) != null) {
      // The item has a base form, so a variant may never be used instead of it.
      return plain(template);
    }
    return true;
  }

  /** A plain item: the item itself, without any component that would make it one specific variant. */
  private static boolean plain(ItemStack template) {
    return template.getComponentsPatch().isEmpty();
  }

  /**
   * Keeps one row per item that has a base form, and one row per type of an item that only exists as
   * variants: every brewable potion, every enchantment a book can carry. A variant belongs to a type
   * only when the data knows the values of that type, and then only the values survival can produce
   * are shown (a painting picture, the uncraftable potion). For a type the data knows nothing about
   * no variant can be ruled out, so all of them are shown. Anything that is really stored keeps its
   * own row.
   */
  private static List<UltsStoredView> onePerItem(List<UltsStoredView> views) {
    Set<UltsStoredView> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<Item, UltsStoredView> baseRows = new IdentityHashMap<>();
    Set<String> usedTypes = new HashSet<>();
    for (UltsStoredView view : views) {
      if (view.amount() > 0) {
        continue;
      }
      ItemStack template = view.template();
      Item item = template.getItem();
      if (UltsCreativeCatalog.plainTemplate(item) != null) {
        if (plain(template)) {
          baseRows.putIfAbsent(item, view);
        }
        continue;
      }
      String type = UltsSurvivalItems.typeKey(template);
      if (type == null) {
        chosen.add(view);
        continue;
      }
      // A type belongs to one item: a potion and a tipped arrow of the same potion are two rows.
      if (UltsSurvivalItems.typeObtainable(template)
          && usedTypes.add(BuiltInRegistries.ITEM.getKey(item) + "|" + type)) {
        chosen.add(view);
      }
    }
    chosen.addAll(baseRows.values());
    List<UltsStoredView> result = new ArrayList<>(views.size());
    for (UltsStoredView view : views) {
      if (view.amount() > 0 || chosen.contains(view)) {
        result.add(view);
      }
    }
    return result;
  }

  private static long amountOf(Map<Item, List<UltsStoredView>> byItem, ItemStack template) {
    long amount = 0L;
    for (UltsStoredView view : byItem.getOrDefault(template.getItem(), List.of())) {
      if (ItemStack.isSameItemSameComponents(view.template(), template)) {
        amount = UltsCraftMath.add(amount, view.amount());
      }
    }
    return amount;
  }

  private int selectedCategoryIndex(List<UltsItemCategory> categories) {
    if (category == null) {
      return -1;
    }
    for (int index = 0; index < categories.size(); index++) {
      if (categories.get(index).id().equals(category.id())) {
        return index;
      }
    }
    return -1;
  }

  static Map<Item, List<UltsStoredView>> index(List<UltsStoredView> stored) {
    Map<Item, List<UltsStoredView>> byItem = new HashMap<>();
    for (UltsStoredView view : stored) {
      byItem.computeIfAbsent(view.template().getItem(), key -> new ArrayList<>()).add(view);
    }
    return byItem;
  }

  /**
   * The special stacks of every item that has any, newest first, keyed by item.
   *
   * <p>A row of the special category stands for one item, whatever its stacks carry: every variant of
   * it is kept in the same bag, which is what makes a bag of differently enchanted pumpkins one row
   * rather than a row each. It is worked out once per screen for every item rather than once per row,
   * so a large storage costs one walk of its contents and not one per row.
   */
  static Map<Item, List<UltsStoredView>> bagRows(List<UltsStoredView> stored) {
    Map<Item, List<UltsStoredView>> byItem = new HashMap<>();
    for (UltsStoredView view : stored) {
      if (view.special()) {
        byItem.computeIfAbsent(view.template().getItem(), key -> new ArrayList<>()).add(view);
      }
    }
    Map<Item, List<UltsStoredView>> bags = new HashMap<>();
    for (Map.Entry<Item, List<UltsStoredView>> entry : byItem.entrySet()) {
      List<UltsStoredView> rows = new ArrayList<>(entry.getValue());
      rows.sort(NEWEST_FIRST);
      bags.put(entry.getKey(), List.copyOf(rows));
    }
    return bags;
  }

  private List<UltsStoredView> listedItems(
      UltsItemCategory selected,
      Map<Item, List<UltsStoredView>> byItem,
      List<UltsStoredView> stored,
      UltsItemVisibility visibility
  ) {
    return rows(selected, byItem, stored, visibility, filter, locale(),
        template -> runtime.craftableNow(template, stored));
  }

  /**
   * The rows one category lists: what the screen draws, and what a search is answered from.
   *
   * <p>A category holds its own tabs' entries — the stuff, component for component — and next to each
   * item the stacks of it that carry data no tab describes, such as an enchanted book or a named sword.
   * Those are rows of their item, not bags: an item a category lists can always show what is stored of
   * it here, so nothing that belongs to a category ever needs the special one.
   *
   * <p>The special category is the one that holds what no category can: a stack whose item no visible
   * category lists at all has no row to be drawn on, so it is kept per item in a bag, and that row opens
   * it. The "everything" category is all of the others added up, and it ends with those bags.
   *
   * <p>A search reads only the category it was typed in, and answers by name, by id and by what a stack
   * carries, so `锋利` finds the enchanted book holding it. A bag has no contents of its own to read, so
   * it is kept when anything stored inside it answers the search. What the filter screen counts is this
   * very list, so the number it shows is the number of rows the search leads to.
   *
   * @param selected the category being drawn
   * @param byItem the stored stacks by item
   * @param stored everything the storage holds
   * @param visibility what this player sees
   * @param filter the search text, already trimmed and lower-cased, or empty
   * @param locale the language the player's client is set to
   * @param craftableNow whether an item with nothing stored could be made right now
   * @return the rows, in the order they are drawn
   */
  static List<UltsStoredView> rows(
      UltsItemCategory selected,
      Map<Item, List<UltsStoredView>> byItem,
      List<UltsStoredView> stored,
      UltsItemVisibility visibility,
      String filter,
      String locale,
      java.util.function.Predicate<ItemStack> craftableNow
  ) {
    if (selected == null) {
      return List.of();
    }
    // A caller that has no view of its own — a player who never picked one — means the configured
    // default, which is what the storage screen itself would show them.
    UltsItemVisibility shown = visibility == null ? configVisibility() : visibility;
    List<UltsStoredView> listed;
    if (selected.special()) {
      // Every bag: one row per item whose stacks no category can hold, opening onto the stacks
      // themselves. A stack a category does list is that category's row, never this one.
      listed = specialRows(stored);
    } else {
      List<ItemStack> templates = selected.templates();
      List<UltsStoredView> computed = new ArrayList<>(templates.size());
      for (ItemStack template : templates) {
        long amount = 0L;
        for (UltsStoredView view : byItem.getOrDefault(template.getItem(), List.of())) {
          // The rule decides what counts as this row's stock, exactly as it decides what pools into one
          // stored kind: with the tooltip rule a sword that is merely more worn belongs to this row too.
          if (UltsStackKinds.same(view.template(), template)) {
            amount = UltsCraftMath.add(amount, view.amount());
          }
        }
        computed.add(new UltsStoredView(template, amount, false));
      }
      if (UltsCreativeCatalog.ALL_ID.equals(selected.id())) {
        // The "everything" view is every category added up, and it ends with the bags of the stacks no
        // category can show, each kind on one row that opens the bag holding the things themselves.
        computed.addAll(specialRows(stored));
      }
      listed = computed;
    }
    if (shown != UltsItemVisibility.ALL) {
      // Survival keeps everything a survival player can obtain, the available mode what the storage
      // can hand over right now, crafting included.
      listed = listed.stream()
          .filter(view -> listed(shown, view.template(), view.amount(), stored, craftableNow))
          .toList();
      if (shown == UltsItemVisibility.SURVIVAL) {
        listed = onePerItem(listed);
      }
    }
    if (filter != null && !filter.isEmpty()) {
      listed = listed.stream()
          .filter(view -> matchesRow(view, filter, locale, stored)).toList();
    }
    return listed;
  }

  /**
   * Whether a search keeps a row.
   *
   * <p>A plain row is kept when its own stack answers the search. A bag row is kept when anything in the
   * bag does, so searching for an enchantment finds the bag of enchanted books holding it even though
   * the row itself is drawn bare and only glints — the bag holds a crowd, and a search that can open a
   * bag to look inside it must open the bags that have what was asked for.
   *
   * @param view the row in question
   * @param filter the search text, already trimmed and lower-cased
   * @param locale the language the search is answered in
   * @param stored everything the storage holds
   * @return whether the search keeps this row
   */
  private static boolean matchesRow(
      UltsStoredView view,
      String filter,
      String locale,
      List<UltsStoredView> stored
  ) {
    if (UltsCreativeCatalog.matches(view.template(), filter, locale)) {
      return true;
    }
    if (!view.special()) {
      return false;
    }
    Item item = view.template().getItem();
    for (UltsStoredView candidate : stored) {
      if (candidate.special() && candidate.template().getItem() == item
          && UltsCreativeCatalog.matches(candidate.template(), filter, locale)) {
        return true;
      }
    }
    return false;
  }

  /**
   * One row per item that carries data of its own, newest first.
   *
   * <p>A special row stands for a kind of thing rather than a thing: everything stored of one item
   * shares a row, and the row opens the bag holding the arrivals themselves. It shows when the newest
   * arrival came in, which is also what the rows sort by.
   */
  static List<UltsStoredView> specialRows(List<UltsStoredView> stored) {
    Map<Item, UltsStoredView> rows = new LinkedHashMap<>();
    for (UltsStoredView view : stored) {
      if (view.special()) {
        rows.merge(view.template().getItem(), view, UltsStorageSGUI::newerOf);
      }
    }
    return rows.values().stream().sorted(NEWEST_FIRST).toList();
  }

  /** A row of a kind after another arrival: the amounts add up and the newest time wins. */
  private static UltsStoredView newerOf(UltsStoredView first, UltsStoredView second) {
    boolean secondIsNewer = second.updatedAt() >= first.updatedAt();
    UltsStoredView newest = secondIsNewer ? second : first;
    UltsStoredView older = secondIsNewer ? first : second;
    return new UltsStoredView(
        newest.template(), UltsCraftMath.add(older.amount(), newest.amount()), true, newest.updatedAt());
  }

  private GuiElementBuilder categoryButton(UltsItemCategory value) {
    boolean selected = category != null && category.id().equals(value.id());
    GuiElementBuilder builder = element(value.icon())
        .setName(value.displayName().copy().withStyle(
            selected ? ChatFormatting.GREEN : ChatFormatting.GRAY))
        .addLoreLine(UltsGuiText.text("ults.gui.category.select"))
        .setCallback(() -> {
          UltsGuiSound.click(player);
          category = value;
          itemPage = 0;
          render();
        });
    if (selected) {
      builder.glow();
    }
    return builder;
  }

  /**
   * One row of the listing.
   *
   * <p>A bag row is drawn as the stack that arrived last, exactly as it was stored — a named netherite
   * sword looks like that sword, an enchanted book lists the enchantment it holds — and two yellow rules
   * under it say which of the bag's stacks that is and separate it from the bag's own facts. Below them
   * the row says how many stacks are in the bag and what the click does: a right click opens it, and
   * everything inside is taken from inside.
   */
  private GuiElementBuilder itemButton(
      UltsStoredView view,
      List<UltsStoredView> stock,
      Map<Item, List<UltsStoredView>> bags
  ) {
    ItemStack template = view.template();
    long amount = view.amount();
    List<UltsStoredView> group = bags.getOrDefault(template.getItem(), List.of());
    if (view.special()) {
      GuiElementBuilder bag = bagRow(view, group, filter, locale(), player);
      bag.setCallback((slot, type, action, gui) -> {
        if (type == ClickType.MOUSE_RIGHT) {
          UltsGuiSound.click(player);
          UltsBagSGUI.open(player, runtime, template.getItem());
        } else if (type == ClickType.MOUSE_RIGHT_SHIFT && runtime.allowTakeAll()) {
          // Emptying the whole bag is the question the bag screen's own status book asks, so it is answered
          // by the same screen: a player who asks it from the row does not have to open the bag first.
          UltsGuiSound.click(player);
          UltsTakeAllSGUI.openForBag(
              player, runtime, template.getItem(), () -> UltsStorageSGUI.open(player, runtime));
        }
      });
      return bag;
    }

    GuiElementBuilder builder = new GuiElementBuilder(template.copyWithCount(1))
        .addLoreLine(Component.empty())
        .addLoreLine(UltsGuiText.labelled("ults.gui.amount", amount, amount == 0));
    long boxSize = SHULKER_SLOTS * template.getMaxStackSize();
    if (boxSize > 0 && amount >= boxSize) {
      builder.addLoreLine(UltsGuiText.boxes(amount / boxSize, amount % boxSize));
    }
    long craftable = runtime.craftable(template, stock);
    if (craftable > 0) {
      builder.addLoreLine(UltsGuiText.labelled(
          "ults.gui.craftable", UltsGuiText.format(craftable), false));
    } else if (runtime.craftableWithoutStation(template, stock) > 0) {
      // The recipe is there and the material is there, only the station is missing: say so instead of
      // hiding the line, so it is obvious why nothing can be crafted.
      builder.addLoreLine(UltsGuiText.labelled(
          "ults.gui.craftable",
          UltsGuiText.text("ults.gui.craftable.no_station").getString(),
          true));
    }
    // A row can be taken from while the storage holds the item or could craft it, so an item that is
    // not stored but can be made right now is just as usable. The view answers this even while the
    // exact amount is still being worked out for a later tick.
    long obtainable = UltsCraftMath.add(amount, craftable);
    if (obtainable > 0 || runtime.craftableNow(template, stock)) {
      boolean takeAll = runtime.allowTakeAll();
      UltsTakeHints.hints(builder, template, obtainable, "ults.gui.take.choose",
          UltsTakeHints.boxPossible(runtime, template, stock, obtainable), takeAll);
      builder.setCallback((slot, type, action, gui) -> {
        if (type == ClickType.MOUSE_LEFT) {
          takeOneStack(template);
        } else if (type == ClickType.MOUSE_LEFT_SHIFT) {
          takeBox(template);
        } else if (type == ClickType.MOUSE_RIGHT) {
          // How many is always the player's question to answer, even when the storage holds a single
          // piece: the screen says what there is and only hands over what is confirmed.
          UltsGuiSound.click(player);
          UltsWithdrawSGUI.open(player, runtime, template);
        } else if (type == ClickType.MOUSE_RIGHT_SHIFT && takeAll) {
          // A server that does not allow taking everything answers this click with nothing at all — no
          // screen, no sound and no message, because nothing here is offered.
          UltsGuiSound.click(player);
          UltsTakeAllSGUI.openForItem(
              player, runtime, template, () -> UltsStorageSGUI.open(player, runtime));
        }
      });
    }
    return builder;
  }

  /**
   * Hands over as much of an item as one stack holds, crafting what is missing.
   *
   * <p>A left click is the fast way out: it asks for a stack and takes what there is when there is less
   * than one, which is exactly what the line under the row says it does. How much that is is asked of
   * the storage as it stands at the click rather than of the numbers the row was drawn with: the screen
   * shows answers that may be a moment old, and a click must never hand over what is no longer there.
   */
  private void takeOneStack(ItemStack template) {
    int quantity = runtime.stackQuantity(template);
    if (quantity < 1) {
      UltsGuiSound.click(player);
      render();
      return;
    }
    List<ItemStack> wanted = UltsWithdrawalOutput.stacks(template, quantity);
    if (!runtime.allowFullInventory() && !UltsWithdrawSGUI.canFit(player, wanted)) {
      // In the chat, like the box refusals: a backpack with no room is a refusal the player has to be able
      // to read back, not a line that fades above the hotbar.
      UltsGuiChat.failure(player, "ults.withdraw.problem.inventory");
      return;
    }
    List<ItemStack> outputs = runtime.takePlanned(template, quantity, false);
    if (outputs.isEmpty()) {
      // The row was drawn a moment ago and the storage is asked as it stands: nothing left means somebody
      // else took it, so the click says so instead of looking like a click that did not register.
      UltsGuiChat.failure(player, "ults.withdraw.problem.gone");
      render();
      return;
    }
    UltsGuiSound.confirm(player);
    UltsGuiGive.handWithFeedback(player, runtime, outputs, runtime.allowFullInventory());
    render();
  }

  /**
   * Packs one full shulker box of an item and hands it over.
   *
   * <p>A whole box is a big ask — twenty-seven full stacks and an empty box to put them in — so it is
   * only offered while the storage really can: what is stored plus what could be crafted right now. A
   * request that cannot be filled says so instead of taking part of it.
   */
  private void takeBox(ItemStack template) {
    UltsWithdrawalPlan plan = runtime.withdrawalPlan(template, 1, true);
    if (!plan.available()) {
      // Said in the chat and not above the hotbar: a box cannot be packed for reasons the player has to be
      // able to read back — how much is short, or that no box is there to fill — and the line above the
      // hotbar is gone before it can be.
      UltsGuiChat.failure(player, "ults.gui.take.box.failed");
      return;
    }
    if (!runtime.allowFullInventory() && !UltsWithdrawSGUI.canFit(player, plan.outputs())) {
      UltsGuiChat.failure(player, "ults.withdraw.problem.inventory");
      return;
    }
    List<ItemStack> outputs = runtime.takePlanned(template, 1, true);
    if (outputs.isEmpty()) {
      // The row was drawn a moment ago and the storage is asked as it stands: nothing left means somebody
      // else took it, so the click says so instead of looking like a click that did not register.
      UltsGuiChat.failure(player, "ults.withdraw.problem.gone");
      render();
      return;
    }
    UltsGuiSound.confirm(player);
    UltsGuiGive.handWithFeedback(player, runtime, outputs, runtime.allowFullInventory());
    render();
  }

  /** How many of a stack, components and all, the listing says the storage holds. */
  private static long storedAmount(ItemStack template, List<UltsStoredView> stock) {
    return UltsRuntime.storedAmount(template, stock);
  }

  /**
   * The book and quill that says what the listing is showing.
   *
   * <p>It answers for the listing itself: which category, which page of it, how many pages of items there
   * are, how many kinds are listed and how many of those are really in stock, and what the visibility
   * setting and the search are. What the storage holds of the special items is not part of that, so the
   * book says nothing about bags.
   */
  private GuiElementBuilder statusButton(
      int selectedIndex,
      int categoryCount,
      List<UltsStoredView> items,
      List<UltsStoredView> stock,
      int categoryPage,
      int categoryPages,
      int itemPages
  ) {
    long stocked = items.stream().filter(view -> view.amount() > 0).count();
    GuiElementBuilder builder = element(Items.WRITABLE_BOOK)
        .setName(UltsGuiText.text("ults.gui.status").copy().withStyle(ChatFormatting.YELLOW))
        .addLoreLine(UltsGuiText.labelled(
            "ults.gui.status.category",
            category == null ? "-" : category.displayName().getString(),
            category == null))
        .addLoreLine(UltsGuiText.labelled(
            "ults.gui.status.category.index",
            (selectedIndex + 1) + " / " + categoryCount,
            categoryCount == 0))
        .addLoreLine(UltsGuiText.labelled(
            "ults.gui.status.category.page",
            (categoryPage + 1) + " / " + categoryPages,
            false))
        .addLoreLine(UltsGuiText.labelled(
            "ults.gui.status.page", (itemPage + 1) + " / " + itemPages, false))
        .addLoreLine(UltsGuiText.labelled(
            "ults.gui.types", "ults.gui.types.suffix", items.size(), false))
        .addLoreLine(UltsGuiText.labelled(
            "ults.gui.types.stored", "ults.gui.types.stored.suffix", stocked, false))
        .addLoreLine(UltsGuiText.labelled(
            "ults.gui.visibility",
            UltsGuiText.text("ults.config.item_visibility."
                + effectiveVisibility().name().toLowerCase(Locale.ROOT)).getString(),
            false))
        .addLoreLine(filter.isEmpty()
            ? UltsGuiText.label("ults.gui.filter.none")
            : UltsGuiText.labelled("ults.gui.filter", filter, false))
        .addLoreLine(UltsGuiText.text("ults.gui.filter.open").copy()
            .withStyle(ChatFormatting.GRAY))
        .addLoreLine(UltsGuiText.text("ults.gui.visibility.open").copy()
            .withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          if (type == ClickType.MOUSE_LEFT) {
            UltsGuiSound.click(player);
            UltsFilterSGUI.open(player, runtime);
          } else if (type == ClickType.MOUSE_RIGHT) {
            UltsGuiSound.click(player);
            cycleVisibility();
          }
        });
    if (!filter.isEmpty()) {
      builder.glow();
    }
    return builder;
  }

  private GuiElement arrow(
      String profile,
      String key,
      Runnable callback,
      String loreKey,
      Object... loreArguments
  ) {
    return element(Items.PLAYER_HEAD)
        .setProfile(profile)
        .setName(UltsGuiText.text(key).copy().withStyle(ChatFormatting.YELLOW))
        .addLoreLine(UltsGuiText.text(loreKey, loreArguments).copy()
            .withStyle(ChatFormatting.GRAY))
        .setCallback(() -> {
          UltsGuiSound.click(player);
          callback.run();
        })
        .build();
  }

  /** The visibility this player sees: their own choice, or the configured default until they pick one. */
  private UltsItemVisibility effectiveVisibility() {
    return chosenVisibility != null ? chosenVisibility : configVisibility();
  }

  /** The language the player's own client is set to, which is the one a search is answered in. */
  private String locale() {
    return UltsItemNames.normalize(player.clientInformation().language());
  }

  private static UltsItemVisibility configVisibility() {
    return UltsConfigManager.getInstance().data().general().itemVisibility();
  }

  /**
   * Whether this player may switch to a mode.
   *
   * <p>Showing everything is only on offer while the server default already does, or while the player
   * may manage the storage, so a plain player cannot open a view the owner did not want to hand out.
   */
  private boolean mayChoose(UltsItemVisibility value) {
    return value != UltsItemVisibility.ALL
        || configVisibility() == UltsItemVisibility.ALL
        || UltsRuntime.canManage(player.permissions());
  }

  /** Switches to the next mode this player may use and remembers the choice with their view. */
  private void cycleVisibility() {
    UltsItemVisibility next = effectiveVisibility();
    for (int step = 0; step < UltsItemVisibility.values().length; step++) {
      next = next.next();
      if (mayChoose(next)) {
        break;
      }
    }
    chosenVisibility = next;
    render();
  }

  private void changeItemPage(int offset, int pages) {
    itemPage = Math.floorMod(itemPage + offset, pages);
    render();
  }

  private void changeCategoryPage(int offset, int pages) {
    categoryPage = Math.floorMod(categoryPage + offset, pages);
    render();
  }

  private void saveView() {
    runtime.state().setViewProfile(player.getUUID(), new UltsViewProfile(
        category == null ? UltsCreativeCatalog.ALL_ID : category.id(), categoryPage, itemPage,
        filter, chosenVisibility));
  }

  private static GuiElementBuilder element(net.minecraft.world.item.Item item) {
    return new GuiElementBuilder(item).hideDefaultTooltip();
  }

  /**
   * The whole bag row of one item, minus the click it answers: what is drawn, and what it says.
   *
   * <p>The row is drawn as the stack that arrived last, and the lines under its title are that stack's
   * own, exactly as they come: its name, then what it carries, worked out for the player who is reading
   * them so that the numbers on it are the ones the game puts on a hover. Nothing is added between them
   * and nothing is taken out, so a stack whose own lines hold a gap keeps it and one whose lines run
   * straight on runs straight on. They are set into the row rather than left to the client, which is what
   * puts the label above them and the rule under them and lets the title stand apart from them.
   *
   * <p>That title names the bag rather than the stack: the item every stack in it is a kind of, so a bag
   * of differently named netherite swords is a bag of netherite swords however the newest one is called.
   * It is built from the item's own name component rather than from finished words, so the client draws
   * it in the language that client is set to.
   *
   * <p>The title is coloured here, in green, so that it can never be mistaken for the name of the stack
   * under it: the game paints that one from the stack's rarity, which is white, yellow, aqua or light
   * purple and nothing else. Green is the one bright colour none of those four can be. The game wraps the
   * name in a rarity colour of its own before drawing it — the wrapper is what used to show through — but a
   * colour set on the name itself wins over the wrapper, so the row states its own colour rather than
   * borrowing the one its rarity happens to carry.
   *
   * <p>The icon is the newest stack as it is, glint and all: nothing about it is altered for the row, and
   * what the row hands over on a click is the stored stack itself.
   *
   * <p>Under the closing rule come the bag's own facts — how many stacks it holds, or, while a search is
   * on, how many of them that search kept, which is the number opening the bag will lay out and is worked
   * out by the very code the bag screen draws with, so the row and the bag can never disagree — and then
   * when the newest arrived and the one line about the click that opens it.
   *
   * @param view the bag row's own entry, whose template is the newest arrival
   * @param group every stack the bag holds
   * @param filter the search text the player is looking through, or empty
   * @param locale the language the player's client is set to
   * @param player the player who will read the row, whose own values its numbers are worked out from
   * @return the row, ready for its click to be set
   */
  static GuiElementBuilder bagRow(
      UltsStoredView view,
      List<UltsStoredView> group,
      String filter,
      String locale,
      Player player
  ) {
    ItemStack newest = view.template();
    List<Component> content = UltsItemNames.tooltip(newest, player);
    List<Component> lines = new ArrayList<>();
    lines.add(UltsGuiText.text("ults.gui.bag.newest").copy()
        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
    for (Component line : content) {
      lines.add(line.copy());
    }
    lines.add(UltsGuiText.text("ults.gui.bag.divider").copy().withStyle(ChatFormatting.YELLOW));
    boolean searched = filter != null && !filter.isEmpty();
    lines.add(searched
        // One line and not two: what the bag would hand over while the search is on, which is what a
        // player looking at a filtered listing is asking about.
        ? UltsGuiText.labelled(
            "ults.gui.bag.entries.filtered", keptInBag(group, filter, locale), false)
        : UltsGuiText.labelled("ults.gui.bag.entries", group.size(), false));
    lines.add(UltsGuiText.labelled(
        "ults.gui.special.updated", UltsGuiText.stamp(view.updatedAt()), !view.stampKnown()));

    ItemStack icon = newest.copyWithCount(1);
    GuiElementBuilder bag = new GuiElementBuilder(icon)
        .setName(bagTitle(newest))
        .setLore(lines)
        .hideDefaultTooltip();
    return UltsTakeHints.bagHints(bag, "ults.gui.take.bag", takeAllAllowed());
  }

  /**
   * Whether the configuration lets a player take a whole stock out, which is a line a bag row shows for
   * itself as well as one a row answers.
   */
  private static boolean takeAllAllowed() {
    return UltsConfigManager.getInstance().data().input().allowTakeAll();
  }

  /**
   * The title of a bag row: which item the bag holds, in brackets, with the word for a bag after it.
   *
   * <p>Two colours meet on this line, and they mean two different things. The item's own name is drawn the
   * way the game draws the name of that item anywhere else — in its rarity's colour — so a bag of rare
   * things says so in the same words and the same colour a hover would. The bag's own words, the brackets
   * and the name of the bag, keep the bag's colour instead.
   *
   * <p>The rarity here is the <em>item's</em>, not the newest stack's: the title names the kind of thing the
   * bag holds, which does not change because the stack that arrived last happens to be enchanted. The
   * game's own name line, the one under this in the row's body, is the stack's, and that one does follow
   * the stack.
   *
   * @param newest the newest stack in the bag, whose item is what the title names
   * @return the line, ready to be drawn
   */
  static MutableComponent bagTitle(ItemStack newest) {
    Item item = newest.getItem();
    Component named = item.getName(newest).copy().withStyle(nameColour(item));
    return UltsGuiText.text("ults.gui.bag.title", named).withColor(TITLE_COLOUR);
  }

  /**
   * The colour the name of an item is drawn in: its own rarity's, which is the colour the game paints that
   * item's name with wherever it is shown.
   *
   * <p>It is asked of the item's own stack rather than of the one at hand, so an enchanted stack — whose
   * rarity the game raises a step, and which therefore wears a brighter colour than its item does — does
   * not change the colour a bag row names its kind of thing in.
   *
   * @param item the item whose name is being drawn
   * @return the colour that name belongs in
   */
  static ChatFormatting nameColour(Item item) {
    return item.getDefaultInstance().getRarity().color();
  }

  /**
   * How many stacks of one bag a search keeps, which is what opening that bag will lay out.
   *
   * <p>It is the bag screen's own answer and not a second guess at it, so a row that says a search left
   * two stacks opens onto exactly two: the two cannot come apart.
   *
   * @param group every stack the bag holds
   * @param filter the search text, already trimmed and lower-cased, or empty
   * @param locale the language the search is answered in
   * @return how many of them the search keeps
   */
  static long keptInBag(List<UltsStoredView> group, String filter, String locale) {
    return UltsBagSGUI.shown(group, filter, locale).size();
  }

  private static GuiElementBuilder element(ItemStack stack) {
    return new GuiElementBuilder(stack);
  }
}
