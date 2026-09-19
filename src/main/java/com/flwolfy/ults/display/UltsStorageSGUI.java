package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsItemVisibility;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.data.state.UltsViewProfile;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class UltsStorageSGUI extends SimpleGui {

  private static final int CATEGORY_UP_SLOT = 0;
  private static final int[] CATEGORY_SLOTS = {9, 18, 27, 36};
  private static final int CATEGORY_PAGE_SIZE = CATEGORY_SLOTS.length;
  private static final int CATEGORY_DOWN_SLOT = 45;
  private static final int PREVIOUS_PAGE_SLOT = 2;
  private static final int STATUS_SLOT = 5;
  private static final int NEXT_PAGE_SLOT = 8;
  private static final int[] CONTENT_SLOTS = {
      11, 12, 13, 14, 15, 16, 17,
      20, 21, 22, 23, 24, 25, 26,
      29, 30, 31, 32, 33, 34, 35,
      38, 39, 40, 41, 42, 43, 44,
      47, 48, 49, 50, 51, 52, 53
  };
  private static final long SHULKER_SLOTS = 27L;
  private static final List<WeakReference<UltsStorageSGUI>> OPEN_MENUS = new ArrayList<>();
  private final UltsRuntime runtime;
  private UltsItemCategory category;
  private int categoryPage;
  private int itemPage;
  private String filter;
  private long renderedRevision = -1;

  private UltsStorageSGUI(ServerPlayer player, UltsRuntime runtime) {
    super(MenuType.GENERIC_9x6, player, false);
    this.runtime = runtime;
    UltsViewProfile profile = runtime.state().viewProfile(player.getUUID());
    category = UltsCreativeCatalog.category(profile.categoryId());
    categoryPage = profile.categoryPage();
    itemPage = profile.itemPage();
    filter = profile.filter();
    setTitle(UltsGuiText.text("ults.gui.title"));
    setLockPlayerInventory(true);
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.add(new WeakReference<>(this));
    }
    render();
  }

  public static void open(ServerPlayer player, UltsRuntime runtime) {
    new UltsStorageSGUI(player, runtime).open();
  }

  public static void refreshAll(UltsRuntime runtime) {
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.removeIf(reference -> {
        UltsStorageSGUI gui = reference.get();
        if (gui == null || !gui.isOpen()) {
          return true;
        }
        if (gui.runtime == runtime
            && gui.renderedRevision != runtime.contentRevision()) {
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

    UltsItemVisibility visibility = UltsConfigManager.getInstance().data().general()
        .itemVisibility();
    List<UltsStoredView> stored = runtime.storedItems();
    Map<Item, List<UltsStoredView>> byItem = index(stored);
    List<UltsItemCategory> categories = visibleCategories(visibility, byItem);
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
    // The category paging arrows stay in place no matter how many categories exist.
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
      setSlot(CONTENT_SLOTS[index - firstItem], itemButton(items.get(index)));
    }

    setSlot(PREVIOUS_PAGE_SLOT, arrow(
        "MHF_ArrowLeft", "ults.gui.previous", () -> changeItemPage(-1, itemPages),
        "ults.gui.page", itemPage + 1, itemPages));
    setSlot(NEXT_PAGE_SLOT, arrow(
        "MHF_ArrowRight", "ults.gui.next", () -> changeItemPage(1, itemPages),
        "ults.gui.page", itemPage + 1, itemPages));
    setSlot(STATUS_SLOT, statusButton(
        selectedIndex, categories.size(), items, categoryPage, categoryPages, itemPages));
    saveView();
  }

  private List<UltsItemCategory> visibleCategories(
      UltsItemVisibility visibility,
      Map<Item, List<UltsStoredView>> byItem
  ) {
    List<UltsItemCategory> categories = UltsCreativeCatalog.categories();
    if (visibility == UltsItemVisibility.ALL) {
      return categories;
    }
    // The "everything" overview always stays reachable, whatever the visibility mode hides.
    return categories.stream()
        .filter(value -> UltsCreativeCatalog.ALL_ID.equals(value.id())
            || value.templates().stream().anyMatch(
                template -> listed(visibility, template, amountOf(byItem, template))))
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
  private static boolean listed(UltsItemVisibility visibility, ItemStack template, long amount) {
    if (amount > 0) {
      return true;
    }
    return switch (visibility) {
      case ALL -> true;
      case SURVIVAL -> base(template);
      case STOCKED_COMPACT -> false;
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
        amount += view.amount();
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

  private static Map<Item, List<UltsStoredView>> index(List<UltsStoredView> stored) {
    Map<Item, List<UltsStoredView>> byItem = new HashMap<>();
    for (UltsStoredView view : stored) {
      byItem.computeIfAbsent(view.template().getItem(), key -> new ArrayList<>()).add(view);
    }
    return byItem;
  }

  private List<UltsStoredView> listedItems(
      UltsItemCategory selected,
      Map<Item, List<UltsStoredView>> byItem,
      List<UltsStoredView> stored,
      UltsItemVisibility visibility
  ) {
    if (selected == null) {
      return List.of();
    }
    List<UltsStoredView> listed;
    if (selected.special()) {
      listed = stored.stream().filter(selected::accepts).toList();
    } else {
      List<ItemStack> templates = selected.templates();
      List<UltsStoredView> computed = new ArrayList<>(templates.size());
      for (ItemStack template : templates) {
        long amount = 0L;
        for (UltsStoredView view : byItem.getOrDefault(template.getItem(), List.of())) {
          if (ItemStack.isSameItemSameComponents(view.template(), template)) {
            amount += view.amount();
          }
        }
        computed.add(new UltsStoredView(template, amount, false));
      }
      if (UltsCreativeCatalog.ALL_ID.equals(selected.id())) {
        // The "everything" view also carries the stored NBT specific items, after the catalog.
        stored.stream().filter(UltsStoredView::special).forEach(computed::add);
      }
      listed = computed;
    }
    if (visibility != UltsItemVisibility.ALL) {
      // Survival keeps everything a survival player can obtain, the other mode only what is stocked.
      listed = listed.stream()
          .filter(view -> listed(visibility, view.template(), view.amount()))
          .toList();
      if (visibility == UltsItemVisibility.SURVIVAL) {
        listed = onePerItem(listed);
      }
    }
    if (!filter.isEmpty()) {
      listed = listed.stream()
          .filter(view -> UltsCreativeCatalog.matches(view.template(), filter)).toList();
    }
    return listed;
  }

  private GuiElementBuilder categoryButton(UltsItemCategory value) {
    boolean selected = category != null && category.id().equals(value.id());
    GuiElementBuilder builder = element(value.icon())
        .setName(value.displayName().copy().withStyle(
            selected ? ChatFormatting.GREEN : ChatFormatting.GRAY))
        .addLoreLine(UltsGuiText.text("ults.gui.category.select"))
        .setCallback(() -> {
          category = value;
          itemPage = 0;
          render();
        });
    if (selected) {
      builder.glow();
    }
    return builder;
  }

  private GuiElementBuilder itemButton(UltsStoredView view) {
    ItemStack template = view.template();
    long amount = view.amount();
    GuiElementBuilder builder = new GuiElementBuilder(template.copyWithCount(1))
        .addLoreLine(Component.empty())
        .addLoreLine(UltsGuiText.labelled("ults.gui.amount", amount, amount == 0));
    long boxSize = SHULKER_SLOTS * template.getMaxStackSize();
    if (boxSize > 0 && amount >= boxSize) {
      builder.addLoreLine(UltsGuiText.boxes(amount / boxSize, amount % boxSize));
    }
    if (amount > 0) {
      builder
          .addLoreLine(UltsGuiText.text("ults.gui.open_withdraw").copy()
              .withStyle(ChatFormatting.GRAY))
          .setCallback((slot, type, action, gui) -> {
            if (type == ClickType.MOUSE_LEFT) {
              UltsWithdrawSGUI.open(player, runtime, template);
            }
          });
    }
    return builder;
  }

  private GuiElementBuilder statusButton(
      int selectedIndex,
      int categoryCount,
      List<UltsStoredView> items,
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
        .addLoreLine(filter.isEmpty()
            ? UltsGuiText.label("ults.gui.filter.none")
            : UltsGuiText.labelled("ults.gui.filter", filter, false))
        .addLoreLine(UltsGuiText.text("ults.gui.filter.open").copy()
            .withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          if (type == ClickType.MOUSE_LEFT) {
            UltsFilterSGUI.open(player, runtime);
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
        .setCallback(callback)
        .build();
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
        filter));
  }

  private static GuiElementBuilder element(net.minecraft.world.item.Item item) {
    return new GuiElementBuilder(item).hideDefaultTooltip();
  }

  private static GuiElementBuilder element(ItemStack stack) {
    return new GuiElementBuilder(stack);
  }
}
